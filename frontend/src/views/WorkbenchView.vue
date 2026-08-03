<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError, api, errorMessage, normalizeList } from '../api/client'
import type {
  DataSourceSummary,
  QueryParameter,
  QueryPreviewResponse,
  QueryRequest,
  QueryResponse
} from '../api/types'
import QueryResultTable from '../components/QueryResultTable.vue'
import SqlEditor from '../components/SqlEditor.vue'
import StateChip from '../components/StateChip.vue'

interface EditableParameter {
  jdbcType: string
  value: string
}

const route = useRoute()
const router = useRouter()
const loadingSources = ref(true)
const previewing = ref(false)
const submitting = ref(false)
const cancelling = ref(false)
const refreshing = ref(false)
const dataSources = ref<DataSourceSummary[]>([])
const preview = ref<QueryPreviewResponse | null>(null)
const previewError = ref('')
const previewInvalidated = ref(false)
const formRevision = ref(0)
const previewRevision = ref(-1)
const previewRequestSignature = ref('')
const result = ref<QueryResponse | null>(null)
const requestError = ref('')
const activeQueryId = ref<string | null>(null)
const cancelRequestedId = ref<string | null>(null)
const parameters = ref<EditableParameter[]>([])

const form = reactive({
  dataSourceId: '',
  purpose: '',
  maxRows: 200,
  sql: 'SELECT\n  *\nFROM your_table\nWHERE id = ?'
})

const selectedSource = computed(() => dataSources.value.find((source) => source.id === form.dataSourceId))
const selectableSources = computed(() =>
  dataSources.value.filter((source) => source.enabled && source.readOnlyStatus !== 'BLOCKED')
)
const previewReady = computed(() =>
  preview.value !== null
  && previewRevision.value === formRevision.value
  && previewRequestSignature.value === requestSignature(queryRequest())
  && preview.value.parameterCount === parameters.value.length
)

const sqlBytes = computed(() => new TextEncoder().encode(form.sql).length)
const clientRiskHints = computed(() => {
  const sql = form.sql.trim()
  if (!sql) return []
  const upper = sql.toUpperCase()
  const hints: string[] = []
  if (/\bSELECT\s+\*/i.test(sql) || /,\s*\*/.test(sql)) hints.push('包含 SELECT *')
  if (!/\bWHERE\b/i.test(sql)) hints.push('查询未包含 WHERE')
  if (form.maxRows > 200) hints.push('请求行数超过自动上限 200')
  if (/\b(INFORMATION_SCHEMA|MYSQL|SYS|PERFORMANCE_SCHEMA|DBA_|ALL_)/i.test(upper)) hints.push('引用系统 Schema')
  const tableCount = (sql.match(/\b(?:FROM|JOIN)\s+[\w.`"$]+/gi) ?? []).length
  if (tableCount > 3) hints.push(`引用对象数量较多（${tableCount}）`)
  return hints
})

const referencedObjects = computed(() => {
  const matches = form.sql.matchAll(/\b(?:FROM|JOIN)\s+([A-Za-z0-9_.$`"]+)/gi)
  return [...new Set(Array.from(matches, (match) => match[1].replaceAll(/[`"]/g, '')))].slice(0, 12)
})

watch(
  () => [
    form.dataSourceId,
    form.purpose,
    form.maxRows,
    form.sql,
    parameters.value.map((parameter) => [parameter.jdbcType, parameter.value])
  ],
  () => {
    formRevision.value += 1
    if (preview.value) previewInvalidated.value = true
    preview.value = null
    previewError.value = ''
    previewRevision.value = -1
    previewRequestSignature.value = ''
  },
  // 同步失效可以封住 v-model 更新与下一次点击之间的同 tick 提交窗口。
  { deep: true, flush: 'sync' }
)

function addParameter() {
  parameters.value.push({ jdbcType: 'VARCHAR', value: '' })
}

function toParameter(parameter: EditableParameter): QueryParameter {
  if (parameter.jdbcType === 'NULL') return { jdbcType: 'NULL', value: null }
  // 类型转换统一在 Java/JDBC 边界完成，避免 JavaScript Number 破坏 BIGINT/DECIMAL 精度，
  // 也避免把非法布尔文本静默转换为 false。
  return { jdbcType: parameter.jdbcType, value: parameter.value }
}

function validateRequest(): boolean {
  if (!form.dataSourceId) {
    ElMessage.warning('请选择可用数据源')
    return false
  }
  if (!form.purpose.trim()) {
    ElMessage.warning('请填写本次生产查询的核对用途')
    return false
  }
  if (!form.sql.trim()) {
    ElMessage.warning('请输入 SELECT 查询')
    return false
  }
  if (sqlBytes.value > 32 * 1024) {
    ElMessage.warning('SQL 超过 32 KiB 硬限制')
    return false
  }
  if (form.maxRows < 1 || form.maxRows > 1000) {
    ElMessage.warning('请求行数必须在 1–1,000 之间')
    return false
  }
  return true
}

function queryRequest(requestId?: string): QueryRequest {
  return {
    dataSourceId: form.dataSourceId,
    sql: form.sql,
    parameters: parameters.value.map(toParameter),
    purpose: form.purpose.trim(),
    maxRows: form.maxRows,
    requestId
  }
}

function requestSignature(request: QueryRequest): string {
  return JSON.stringify({
    dataSourceId: request.dataSourceId,
    sql: request.sql,
    parameters: request.parameters,
    purpose: request.purpose,
    maxRows: request.maxRows
  })
}

async function loadSources() {
  loadingSources.value = true
  try {
    dataSources.value = normalizeList(await api.dataSources()).items
    if (!form.dataSourceId && selectableSources.value.length) {
      form.dataSourceId = selectableSources.value[0].id
      form.maxRows = Math.min(selectableSources.value[0].maxRows || 200, 1000)
    }
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    loadingSources.value = false
  }
}

async function previewQuery() {
  if (!validateRequest()) return

  const revision = formRevision.value
  const request = queryRequest()
  const signature = requestSignature(request)
  previewing.value = true
  previewError.value = ''
  try {
    const checked = await api.previewQuery(request)
    if (revision !== formRevision.value || signature !== requestSignature(queryRequest())) {
      previewInvalidated.value = true
      ElMessage.warning('表单在预检期间发生变化，请重新预检')
      return
    }
    preview.value = checked
    previewRevision.value = revision
    previewRequestSignature.value = signature
    previewInvalidated.value = false
    if (checked.parameterCount !== parameters.value.length) {
      previewError.value = `服务端识别到 ${checked.parameterCount} 个占位符，当前提供 ${parameters.value.length} 个参数`
      ElMessage.error(previewError.value)
    } else if (checked.riskReasons.length) {
      ElMessage.warning('服务端预检完成：存在需要关注的风险信号')
    } else {
      ElMessage.success('服务端 AST 预检完成，当前修订已锁定')
    }
  } catch (cause) {
    preview.value = null
    previewRevision.value = -1
    previewRequestSignature.value = ''
    previewError.value = errorMessage(cause)
  } finally {
    previewing.value = false
  }
}

async function submit() {
  requestError.value = ''
  result.value = null

  if (!validateRequest()) return
  if (!previewReady.value) {
    ElMessage.warning('当前表单尚未通过服务端预检，或预检已因修改失效')
    return
  }

  const requestId = crypto.randomUUID()
  const request = queryRequest(requestId)
  if (previewRequestSignature.value !== requestSignature(request)) {
    ElMessage.warning('表单内容与服务端预检版本不一致，请重新预检')
    return
  }
  activeQueryId.value = requestId
  cancelRequestedId.value = null
  submitting.value = true
  try {
    const submittedResult = await api.submitQuery(request)
    // 独立取消请求已经返回时，迟到的原提交响应不得覆盖 CANCELLED 终态。
    if (cancelRequestedId.value === requestId) return
    result.value = submittedResult
    if (submittedResult.status === 'PENDING_APPROVAL') {
      ElMessage.warning('服务端判定需要一次性人工审批')
    } else if (submittedResult.status === 'EXECUTED') {
      ElMessage.success('只读查询执行完成')
    }
  } catch (cause) {
    if (cancelRequestedId.value !== requestId) {
      requestError.value = errorMessage(cause)
    }
  } finally {
    submitting.value = false
    activeQueryId.value = null
    cancelRequestedId.value = null
    preview.value = null
    previewRevision.value = -1
    previewRequestSignature.value = ''
    previewInvalidated.value = false
  }
}

async function refreshQuery() {
  if (!result.value?.queryId) return
  refreshing.value = true
  try {
    result.value = await api.query(result.value.queryId)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    refreshing.value = false
  }
}

function delay(milliseconds: number) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds))
}

async function cancelById(queryId: string, retryNotFound: boolean): Promise<QueryResponse> {
  const delays = retryNotFound ? [100, 150, 250, 400, 650, 900] : []
  for (let attempt = 0; ; attempt += 1) {
    try {
      return await api.cancelQuery(queryId)
    } catch (cause) {
      if (!(cause instanceof ApiError) || cause.status !== 404 || attempt >= delays.length) throw cause
      await delay(delays[attempt])
    }
  }
}

async function cancelQuery() {
  const queryId = activeQueryId.value ?? result.value?.queryId
  if (!queryId) return
  cancelling.value = true
  cancelRequestedId.value = queryId
  try {
    result.value = await cancelById(queryId, activeQueryId.value === queryId)
    requestError.value = ''
    ElMessage.success('取消信号已被服务端接受')
  } catch (cause) {
    cancelRequestedId.value = null
    if (cause instanceof ApiError && cause.status === 404) {
      ElMessage.error('查询尚未登记或已结束，未能确认取消；请刷新审计与查询状态')
    } else {
      ElMessage.error(errorMessage(cause))
    }
  } finally {
    cancelling.value = false
  }
}

function loadExample() {
  form.sql = 'SELECT\n  order_id,\n  order_status,\n  updated_at\nFROM order_info\nWHERE order_id = ?\nORDER BY updated_at DESC'
  parameters.value = [{ jdbcType: 'VARCHAR', value: '' }]
}

onMounted(async () => {
  await loadSources()
  const queryId = typeof route.query.queryId === 'string' ? route.query.queryId : ''
  if (queryId) {
    refreshing.value = true
    try {
      result.value = await api.query(queryId)
    } catch (cause) {
      requestError.value = errorMessage(cause)
    } finally {
      refreshing.value = false
    }
  }
})
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">查询工作区 / 只读执行通道</p>
        <h1 class="page-title">SQL 工作台</h1>
        <p class="page-subtitle">查询先写审计，再由服务端解析 AST。界面中的风险提示仅供预览，不代表最终放行。</p>
      </div>
      <div class="heading-actions">
        <el-button plain @click="loadExample">载入模板</el-button>
        <el-button
          data-testid="preview-query"
          plain
          :loading="previewing"
          :disabled="submitting"
          @click="previewQuery"
        >
          服务端预检
        </el-button>
        <el-button
          data-testid="submit-query"
          type="primary"
          :loading="submitting"
          :disabled="!previewReady || previewing || submitting"
          @click="submit"
        >
          执行已预检查询
        </el-button>
        <el-button
          v-if="activeQueryId"
          data-testid="cancel-active-query"
          type="danger"
          plain
          :loading="cancelling"
          @click="cancelQuery"
        >
          取消执行
        </el-button>
      </div>
    </header>

    <div v-if="!loadingSources && !selectableSources.length" class="source-alert">
      <span>没有可用数据源</span>
      <p>当前没有可用数据源，请先完成连接检查并启用数据源。</p>
      <el-button plain @click="router.push('/datasources')">前往数据源</el-button>
    </div>

    <div class="workbench-grid">
      <section class="panel editor-panel">
        <header class="panel-header query-toolbar">
          <div class="source-control">
            <span>数据源</span>
            <el-select
              v-model="form.dataSourceId"
              :loading="loadingSources"
              placeholder="选择数据源"
              filterable
            >
              <el-option
                v-for="source in selectableSources"
                :key="source.id"
                :label="source.name"
                :value="source.id"
              >
                <span>{{ source.name }}</span>
                <span class="option-status">{{ source.readOnlyStatus }}</span>
              </el-option>
            </el-select>
          </div>
          <StateChip v-if="selectedSource" :value="selectedSource.readOnlyStatus" />
        </header>

        <div class="editor-wrap">
          <SqlEditor v-model="form.sql" />
        </div>
        <footer class="editor-footer">
          <span>UTF-8</span>
          <span>{{ form.sql.split('\n').length }} 行</span>
          <span :class="{ 'text-red': sqlBytes > 32 * 1024 }">{{ sqlBytes }} / 32768 字节</span>
        </footer>
      </section>

      <aside class="side-stack">
        <section class="panel request-panel">
          <header class="panel-header">
            <p class="panel-title">请求参数</p>
            <span class="panel-code">必填</span>
          </header>
          <div class="panel-body">
            <el-form label-position="top">
              <el-form-item label="核对用途">
                <el-input
                  data-testid="query-purpose"
                  v-model="form.purpose"
                  type="textarea"
                  :rows="3"
                  maxlength="300"
                  show-word-limit
                  placeholder="例如：核对工单 #123 的生产状态异常"
                />
              </el-form-item>
              <el-form-item label="最大返回行数">
                <el-input-number v-model="form.maxRows" :min="1" :max="1000" controls-position="right" style="width: 100%" />
              </el-form-item>
            </el-form>
          </div>
        </section>

        <section class="panel risk-panel">
          <header class="panel-header">
            <p class="panel-title">预检雷达</p>
            <span class="panel-code">
              {{ previewing ? '正在扫描' : previewReady ? '服务端已锁定' : '需要预检' }}
            </span>
          </header>
          <div class="panel-body">
            <p v-if="previewInvalidated" class="preview-invalid">
              表单已修改 / 上一次服务端预检已失效
            </p>
            <div v-if="preview" class="server-preview">
              <div class="verdict-row">
                <StateChip :value="preview.readOnlyStatus" />
                <span>
                  生效限额
                  <strong>{{ preview.effectiveMaxRows }}</strong>
                </span>
                <span>
                  参数数量
                  <strong :class="{ 'text-red': preview.parameterCount !== parameters.length }">
                    {{ preview.parameterCount }} / {{ parameters.length }}
                  </strong>
                </span>
              </div>
              <div v-if="preview.riskReasons.length" class="hint-list server-hints">
                <span v-for="hint in preview.riskReasons" :key="hint"><i />{{ hint }}</span>
              </div>
              <p v-else class="clear-hint"><i class="status-dot" />服务端未返回风险信号</p>
              <div class="server-object-groups">
                <div>
                  <small>服务端识别的 Schema</small>
                  <span v-for="schema in preview.schemas" :key="schema">{{ schema }}</span>
                  <em v-if="!preview.schemas.length">无显式 Schema</em>
                </div>
                <div>
                  <small>服务端识别的表</small>
                  <span v-for="table in preview.tables" :key="table">{{ table }}</span>
                  <em v-if="!preview.tables.length">未识别对象</em>
                </div>
              </div>
              <p class="fingerprint">
                <span>SQL 指纹</span>
                {{ preview.sqlFingerprint }}
              </p>
            </div>
            <template v-else>
              <p v-if="previewError" class="preview-error">{{ previewError }}</p>
              <div v-if="clientRiskHints.length" class="hint-list local-hints">
                <span v-for="hint in clientRiskHints" :key="hint"><i />{{ hint }}</span>
              </div>
              <p v-else class="local-clear">前端估算未发现信号；执行资格仍以服务端预检为准。</p>
              <div class="object-list">
                <small>本地估算 / 仅供参考</small>
                <span v-for="object in referencedObjects" :key="object">{{ object }}</span>
                <em v-if="!referencedObjects.length">等待输入</em>
              </div>
            </template>
            <div v-if="previewError && preview" class="preview-error">
              {{ previewError }}
            </div>
          </div>
        </section>
      </aside>
    </div>

    <section class="panel parameter-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">预编译参数</p>
          <span class="panel-code">按位置绑定 / JDBC 类型</span>
        </div>
        <el-button plain size="small" @click="addParameter">添加参数</el-button>
      </header>
      <div v-if="parameters.length" class="parameter-list">
        <div v-for="(parameter, index) in parameters" :key="index">
          <span class="param-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <el-select v-model="parameter.jdbcType" aria-label="JDBC 参数类型">
            <el-option v-for="type in ['VARCHAR', 'INTEGER', 'BIGINT', 'DECIMAL', 'DOUBLE', 'BOOLEAN', 'DATE', 'TIME', 'TIMESTAMP', 'NULL']" :key="type" :label="type" :value="type" />
          </el-select>
          <el-input
            v-model="parameter.value"
            :disabled="parameter.jdbcType === 'NULL'"
            :placeholder="parameter.jdbcType === 'NULL' ? 'NULL' : '参数值'"
            autocomplete="off"
          />
          <el-button text type="danger" @click="parameters.splice(index, 1)">移除</el-button>
        </div>
      </div>
      <div v-else class="parameter-empty">当前没有绑定参数。SQL 中每个 <code>?</code> 应按顺序对应一个参数。</div>
    </section>

    <section v-if="requestError || result || refreshing || activeQueryId" class="panel execution-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">执行回传</p>
          <span class="panel-code">{{ result?.queryId || '查询响应' }}</span>
        </div>
        <div v-if="result || activeQueryId" class="result-actions">
          <StateChip :value="result?.status || 'EXECUTING'" />
          <el-button
            v-if="activeQueryId || ['REQUESTED', 'PENDING_APPROVAL', 'APPROVED', 'EXECUTING'].includes(result?.status || '')"
            plain
            size="small"
            :loading="cancelling"
            @click="cancelQuery"
          >
            取消
          </el-button>
          <el-button plain size="small" :loading="refreshing" @click="refreshQuery">刷新状态</el-button>
        </div>
      </header>

      <div v-if="refreshing && !result" class="skeleton-lines"><span /><span /><span /></div>
      <div v-else-if="activeQueryId && !result" class="waiting-state active-query">
        <span class="waiting-pulse" />
        <div>
          <p>服务端正在执行已预检查询，可使用独立取消请求中止。</p>
          <code>{{ activeQueryId }}</code>
        </div>
      </div>
      <div v-else-if="requestError" class="query-error">
        <span>已拒绝 / 执行失败</span>
        <h3>查询没有进入结果通道</h3>
        <p>{{ requestError }}</p>
      </div>
      <template v-else-if="result">
        <div v-if="result.status === 'PENDING_APPROVAL'" class="approval-signal">
          <div class="signal-mark">!</div>
          <div>
            <p class="eyebrow">需要人工审批</p>
            <h3>需要一次性人工审批</h3>
            <p>{{ result.message || '风险规则命中，审批只绑定本次 SQL、参数、数据源和限额，5 分钟内单次有效。' }}</p>
            <div class="risk-tags">
              <span v-for="reason in result.riskReasons ?? []" :key="reason">{{ reason }}</span>
            </div>
          </div>
          <el-button type="warning" @click="router.push(`/approvals?queryId=${result.queryId}`)">前往审批舱</el-button>
        </div>
        <div v-else-if="result.status === 'FAILED' || result.status === 'REJECTED'" class="query-error">
          <span>{{ result.errorCode || result.status }}</span>
          <h3>{{ result.status === 'REJECTED' ? '查询被安全策略拒绝' : '查询执行失败' }}</h3>
          <p>{{ result.message || '服务端未返回更多错误信息。' }}</p>
        </div>
        <QueryResultTable v-else-if="result.status === 'EXECUTED'" :result="result" />
        <div v-else class="waiting-state">
          <span class="waiting-pulse" />
          <p>当前状态：{{ result.status }}。可刷新获取最新终态。</p>
        </div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.heading-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 9px;
}

.source-alert {
  display: grid;
  grid-template-columns: 90px 1fr auto;
  align-items: center;
  gap: 18px;
  margin-bottom: 12px;
  padding: 13px 16px;
  border: 1px solid var(--red-deep);
  background: rgba(255, 91, 88, .06);
}

.source-alert > span {
  color: var(--red);
  font: 700   14px/1 var(--font-mono);
}

.source-alert p {
  margin: 0;
  color: #9f3f39;
  font-size: 13px;
  line-height: 1.5;
}

.workbench-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 310px;
  gap: 12px;
}

.editor-panel {
  overflow: hidden;
}

.query-toolbar {
  min-height: 68px;
}

.source-control {
  display: flex;
  align-items: center;
  gap: 13px;
}

.source-control > span {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .09em;
}

.source-control .el-select {
  width: 250px;
}

.option-status {
  float: right;
  margin-left: 30px;
  color: var(--text-dim);
  font:  14px/2.3 var(--font-mono);
}

.editor-wrap {
  min-height: 475px;
  background: #fffefa;
}

.editor-wrap :deep(.sql-editor) {
  height: 475px;
}

.editor-footer {
  display: flex;
  height: 32px;
  align-items: center;
  justify-content: flex-end;
  gap: 17px;
  padding: 0 14px;
  border-top: 1px solid var(--line);
  color: var(--text-dim);
  background: #f2f4ef;
  font:  14px/1 var(--font-mono);
}

.side-stack {
  display: grid;
  align-content: start;
  gap: 12px;
}

.request-panel .panel-body,
.risk-panel .panel-body {
  padding-bottom: 12px;
}

.preview-invalid,
.preview-error,
.local-clear {
  margin: 0 0 11px;
  padding: 9px 10px;
  font:  14px/1.5 var(--font-mono);
}

.preview-invalid {
  border: 1px solid var(--amber-deep);
  color: var(--amber);
  background: rgba(245, 185, 76, .05);
}

.preview-error {
  border-left: 2px solid var(--red);
  color: #9f3f39;
  background: rgba(255, 91, 88, .05);
}

.local-clear {
  color: #52645d;
  background: #f2f4ef;
}

.server-preview {
  display: grid;
  gap: 13px;
}

.verdict-row {
  display: grid;
  grid-template-columns: auto 1fr 1fr;
  align-items: center;
  gap: 8px;
}

.verdict-row > span {
  padding: 8px;
  border: 1px solid var(--line);
  color: var(--text-dim);
  font: 12px/1.3 var(--font-mono);
}

.verdict-row strong {
  display: block;
  margin-top: 4px;
  color: var(--green);
  font-size: 14px;
}

.server-object-groups {
  display: grid;
  gap: 9px;
}

.server-object-groups > div {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  padding-top: 9px;
  border-top: 1px solid var(--line);
}

.server-object-groups small {
  width: 100%;
  margin-bottom: 2px;
  color: var(--text-dim);
  font: 12px/1 var(--font-mono);
  letter-spacing: .08em;
}

.server-object-groups span {
  max-width: 100%;
  overflow: hidden;
  padding: 4px 6px;
  border: 1px solid #75877f;
  color: #40554d;
  font:  14px/1 var(--font-mono);
  text-overflow: ellipsis;
}

.server-object-groups em {
  color: #607169;
  font:  14px/1.4 var(--font-mono);
}

.fingerprint {
  margin: 0;
  overflow-wrap: anywhere;
  color: #52645d;
  font:  14px/1.55 var(--font-mono);
}

.fingerprint span {
  display: block;
  margin-bottom: 4px;
  color: #5e6e67;
  letter-spacing: .08em;
}

.hint-list {
  display: grid;
  gap: 8px;
}

.hint-list span {
  padding: 9px 10px;
  border-left: 2px solid var(--amber);
  color: #80652d;
  background: rgba(245, 185, 76, .055);
  font-size: 13px;
}

.hint-list i {
  display: inline-block;
  width: 4px;
  height: 4px;
  margin-right: 8px;
  border-radius: 50%;
  background: var(--amber);
}

.clear-hint {
  margin: 0;
  color: var(--green);
  font:  14px/1.4 var(--font-mono);
}

.object-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 18px;
  padding-top: 15px;
  border-top: 1px solid var(--line);
}

.object-list small {
  width: 100%;
  margin-bottom: 3px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .09em;
}

.object-list span {
  max-width: 100%;
  overflow: hidden;
  padding: 5px 7px;
  border: 1px solid var(--line);
  color: var(--text-soft);
  font:  14px/1 var(--font-mono);
  text-overflow: ellipsis;
}

.object-list em {
  color: #5e6e67;
  font:  14px/1.5 var(--font-mono);
}

.parameter-panel,
.execution-panel {
  margin-top: 12px;
  overflow: hidden;
}

.parameter-list {
  display: grid;
}

.parameter-list > div {
  display: grid;
  min-height: 55px;
  grid-template-columns: 52px 190px 1fr 75px;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--line);
}

.parameter-list > div:last-child {
  border-bottom: 0;
}

.param-index {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  text-align: center;
}

.parameter-empty {
  padding: 22px;
  color: var(--text-dim);
  font-size: 13px;
}

.parameter-empty code {
  color: var(--green);
  font-family: var(--font-mono);
}

.result-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.approval-signal {
  display: grid;
  grid-template-columns: 52px 1fr auto;
  align-items: center;
  gap: 20px;
  padding: 26px;
  background: linear-gradient(90deg, rgba(245, 185, 76, .08), transparent 60%);
}

.signal-mark {
  display: grid;
  width: 46px;
  height: 46px;
  place-content: center;
  border: 1px solid var(--amber);
  color: var(--amber);
  font: 700 20px/1 var(--font-mono);
  transform: rotate(45deg);
}

.approval-signal > div:nth-child(2) > * {
  transform: none;
}

.approval-signal h3 {
  margin: 0 0 7px;
  color: #654818;
  font: 650 18px/1.2 var(--font-display);
}

.approval-signal p:not(.eyebrow) {
  margin: 0;
  color: #80652d;
  font-size: 13px;
  line-height: 1.6;
}

.risk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 12px;
}

.risk-tags span {
  padding: 5px 7px;
  border: 1px solid var(--amber-deep);
  color: var(--amber);
  font:  14px/1 var(--font-mono);
}

.query-error {
  padding: 28px;
  border-left: 3px solid var(--red);
  background: rgba(255, 91, 88, .045);
}

.query-error > span {
  color: var(--red);
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.query-error h3 {
  margin: 10px 0 6px;
  color: #8f332e;
  font: 650 17px/1.2 var(--font-display);
}

.query-error p {
  margin: 0;
  color: #9f3f39;
  font-size: 13px;
}

.waiting-state {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 28px;
  color: var(--text-soft);
  font:  13px/1.5 var(--font-mono);
}

.active-query code {
  display: block;
  margin-top: 5px;
  color: var(--amber);
  font:  14px/1.4 var(--font-mono);
}

.waiting-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--amber);
  box-shadow: 0 0 12px var(--amber);
  animation: blink 1.2s steps(2, end) infinite;
}

@media (max-width: 1050px) {
  .workbench-grid {
    grid-template-columns: 1fr;
  }

  .side-stack {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 680px) {
  .heading-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .source-alert {
    grid-template-columns: 1fr;
  }

  .source-control {
    align-items: flex-start;
    flex-direction: column;
  }

  .source-control .el-select {
    width: min(65vw, 280px);
  }

  .side-stack {
    grid-template-columns: 1fr;
  }

  .parameter-list > div {
    grid-template-columns: 32px 1fr;
  }

  .parameter-list .el-input {
    grid-column: 2;
  }

  .approval-signal {
    grid-template-columns: 45px 1fr;
  }

  .approval-signal .el-button {
    grid-column: 1 / -1;
  }
}

.source-alert {
  border-radius: var(--radius);
  background: #fff7ed;
}

.workbench-grid {
  gap: 16px;
}

.editor-panel,
.request-panel,
.risk-panel,
.parameter-panel,
.execution-panel {
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.editor-wrap {
  background: #fff;
}

.editor-footer {
  background: #f8fafc;
  font-size: 12px;
}

.server-object-groups span,
.object-list span {
  border-radius: 5px;
  border-color: var(--line);
  background: #f8fafc;
}

.approval-signal {
  border-radius: 0 0 var(--radius) var(--radius);
  background: linear-gradient(90deg, #fff7ed, #fff);
}

.signal-mark {
  border-radius: 10px;
  background: #fff7ed;
  transform: none;
}
</style>
