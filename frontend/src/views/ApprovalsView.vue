<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from '../api/client'
import type { QueryResponse } from '../api/types'
import QueryResultTable from '../components/QueryResultTable.vue'
import StateChip from '../components/StateChip.vue'

const route = useRoute()
const router = useRouter()
const pendingCount = ref(0)
const pendingQueries = ref<QueryResponse[]>([])
const queueLoading = ref(false)
const queueError = ref('')
const batchApproving = ref(false)
const selectedIds = ref<string[]>([])
const queryIdInput = ref('')
const query = ref<QueryResponse | null>(null)
const loading = ref(false)
const approving = ref(false)
const executing = ref(false)
const cancelling = ref(false)
const activeExecutionId = ref<string | null>(null)
const cancelRequestedId = ref<string | null>(null)
const error = ref('')
const now = ref(Date.now())
let ticker: number | undefined

const remainingText = computed(() => {
  if (!query.value?.expiresAt) return '批准后 05:00'
  const remaining = Math.max(0, new Date(query.value.expiresAt).getTime() - now.value)
  if (remaining <= 0) return '已过期'
  const seconds = Math.floor(remaining / 1000)
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
})
const approvalPayloadComplete = computed(() =>
  typeof query.value?.sql === 'string'
  && Array.isArray(query.value?.parameters)
  && typeof query.value?.effectiveMaxRows === 'number'
)
const allSelected = computed(() =>
  pendingQueries.value.length > 0
  && pendingQueries.value.every((item) => selectedIds.value.includes(item.queryId))
)

async function loadDashboard() {
  try {
    const dashboard = await api.dashboard()
    pendingCount.value = dashboard.pendingApprovalCount
  } catch {
    // Query lookup remains usable when dashboard telemetry is unavailable.
  }
}

async function loadPending() {
  queueLoading.value = true
  queueError.value = ''
  try {
    const response = await api.queries({ status: 'PENDING_APPROVAL', limit: 100 })
    pendingQueries.value = response.items
    const availableIds = new Set(response.items.map((item) => item.queryId))
    selectedIds.value = selectedIds.value.filter((id) => availableIds.has(id))
  } catch (cause) {
    queueError.value = errorMessage(cause)
  } finally {
    queueLoading.value = false
  }
}

function toggleSelection(queryId: string, checked: boolean) {
  if (checked) {
    if (!selectedIds.value.includes(queryId)) selectedIds.value.push(queryId)
    return
  }
  selectedIds.value = selectedIds.value.filter((id) => id !== queryId)
}

function toggleAll(checked: boolean) {
  selectedIds.value = checked ? pendingQueries.value.map((item) => item.queryId) : []
}

function onSelectionChange(event: Event, queryId: string) {
  const target = event.target
  if (target instanceof HTMLInputElement) toggleSelection(queryId, target.checked)
}

function onSelectAllChange(event: Event) {
  const target = event.target
  if (target instanceof HTMLInputElement) toggleAll(target.checked)
}

async function batchApprove() {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `将一次性批准 ${ids.length} 条查询申请。每条许可仍只绑定自身 SQL、参数和数据源，并分别写入审计记录。`,
      '确认批量批准',
      {
        confirmButtonText: '确认批准',
        cancelButtonText: '返回复核',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  batchApproving.value = true
  try {
    const results = await Promise.allSettled(ids.map((id) => api.approveQuery(id)))
    const successCount = results.filter((result) => result.status === 'fulfilled').length
    const failureCount = results.length - successCount
    selectedIds.value = []
    await refreshPendingState()
    if (failureCount) {
      ElMessage.warning(`已批准 ${successCount} 条，${failureCount} 条未能批准，请展开失败记录复核`)
    } else {
      ElMessage.success(`已批量批准 ${successCount} 条查询申请`)
    }
  } finally {
    batchApproving.value = false
  }
}

async function refreshPendingState() {
  await Promise.all([loadDashboard(), loadPending()])
}

async function resolveQuery(id = queryIdInput.value) {
  const normalized = id.trim()
  if (!normalized) {
    ElMessage.warning('请输入待审批查询编号')
    return
  }
  loading.value = true
  error.value = ''
  try {
    query.value = await api.query(normalized)
    queryIdInput.value = normalized
    void router.replace({ query: { queryId: normalized } })
  } catch (cause) {
    query.value = null
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function valueText(value: unknown): string {
  if (value === null) return 'NULL'
  if (value === undefined) return ''
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return '[无法序列化]'
    }
  }
  return String(value)
}

function formatTime(value?: string): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function sqlBytes(value?: string): number {
  return value ? new TextEncoder().encode(value).length : 0
}

async function approve() {
  if (!query.value) return
  if (!approvalPayloadComplete.value) {
    ElMessage.error('服务端未返回完整 SQL、参数或有效限额，前端拒绝批准')
    return
  }
  const queryId = query.value.queryId
  try {
    await ElMessageBox.confirm(
      '批准将创建只绑定当前数据源、SQL、参数和限额的一次性执行许可。许可 5 分钟内有效且只能使用一次。',
      '确认一次性放行',
      {
        confirmButtonText: '确认批准',
        cancelButtonText: '返回复核',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  approving.value = true
  try {
    query.value = await api.approveQuery(queryId)
    ElMessage.success('查询已批准，等待单次执行')
    await refreshPendingState()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    approving.value = false
  }
}

async function execute() {
  if (!query.value) return
  if (!approvalPayloadComplete.value) {
    ElMessage.error('审批绑定内容不完整，前端拒绝执行')
    return
  }
  const queryId = query.value.queryId
  activeExecutionId.value = queryId
  cancelRequestedId.value = null
  executing.value = true
  try {
    query.value = await api.executeQuery(queryId)
    ElMessage.success('一次性许可已消费，查询执行完成')
  } catch (cause) {
    if (cancelRequestedId.value !== queryId) {
      ElMessage.error(errorMessage(cause))
    }
  } finally {
    await refreshPendingState()
    executing.value = false
    activeExecutionId.value = null
    cancelRequestedId.value = null
  }
}

async function approveAndExecute() {
  await approve()
  if (query.value?.status === 'APPROVED') await execute()
}

async function cancel() {
  if (!query.value) return
  const queryId = query.value.queryId
  const cancellingExecution = activeExecutionId.value === queryId
  if (cancellingExecution) cancelRequestedId.value = queryId
  cancelling.value = true
  try {
    query.value = await api.cancelQuery(queryId)
    ElMessage.success(cancellingExecution ? '执行取消信号已被服务端接受' : '申请已取消')
    await refreshPendingState()
  } catch (cause) {
    if (cancellingExecution) cancelRequestedId.value = null
    ElMessage.error(errorMessage(cause))
  } finally {
    cancelling.value = false
  }
}

onMounted(async () => {
  ticker = window.setInterval(() => { now.value = Date.now() }, 1000)
  await refreshPendingState()
  const queryId = typeof route.query.queryId === 'string' ? route.query.queryId : ''
  if (queryId) {
    queryIdInput.value = queryId
    await resolveQuery(queryId)
  }
})

onBeforeUnmount(() => {
  if (ticker) window.clearInterval(ticker)
})
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">人工审批 / 一次性放行</p>
        <h1 class="page-title">待审批查询</h1>
        <p class="page-subtitle">人工批准不是永久白名单。许可严格绑定本次请求，过期或消费后无法再次执行。</p>
      </div>
      <div class="pending-counter">
        <span>待处理</span>
        <strong>{{ pendingCount }}</strong>
      </div>
    </header>

    <section class="panel queue-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">待审批列表</p>
          <span class="panel-code">待审批 / 最多显示 100 条</span>
        </div>
        <el-button plain size="small" :loading="queueLoading" @click="refreshPendingState">刷新队列</el-button>
      </header>
      <div v-if="queueError" class="queue-error">
        <span>审批队列不可用</span>
        <p>{{ queueError }}</p>
      </div>
      <div v-else-if="queueLoading && !pendingQueries.length" class="skeleton-lines">
        <span /><span /><span />
      </div>
      <div v-else-if="!pendingQueries.length" class="queue-empty">
        <i class="status-dot" />
        当前没有等待人工放行的查询
      </div>
      <div v-else class="queue-list" role="list" aria-label="待审批查询">
        <div class="queue-toolbar">
          <label class="select-all-control">
            <input
              type="checkbox"
              :checked="allSelected"
              :disabled="loading || approving || batchApproving || executing || cancelling"
              @change="onSelectAllChange"
            />
            <span>全选当前 {{ pendingQueries.length }} 条</span>
          </label>
          <span class="selection-count">已选择 {{ selectedIds.length }} 条</span>
          <el-button
            type="primary"
            plain
            size="small"
            :loading="batchApproving"
            :disabled="!selectedIds.length || loading || approving || executing || cancelling"
            @click="batchApprove"
          >
            批量批准
          </el-button>
        </div>
        <button
          v-for="item in pendingQueries"
          :key="item.queryId"
          type="button"
          role="listitem"
          :class="{ selected: query?.queryId === item.queryId }"
          :disabled="loading || approving || batchApproving || executing || cancelling"
          @click="resolveQuery(item.queryId)"
        >
          <span class="queue-check" @click.stop>
            <input
              type="checkbox"
              :checked="selectedIds.includes(item.queryId)"
              :disabled="loading || approving || batchApproving || executing || cancelling"
              :aria-label="`选择查询 ${item.queryId}`"
              @change="onSelectionChange($event, item.queryId)"
            />
          </span>
          <span class="queue-id">{{ item.queryId }}</span>
          <strong>{{ item.purpose || '未提供用途' }}</strong>
          <span>{{ item.dataSourceName || item.dataSourceId || '未知数据源' }}</span>
          <span>{{ item.riskReasons?.length ?? 0 }} 项风险</span>
          <span>{{ item.effectiveMaxRows ?? '—' }} 行</span>
          <time>{{ formatTime(item.requestedAt) }}</time>
          <i>→</i>
        </button>
      </div>
    </section>

    <section class="panel resolver-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">查询申请详情</p>
          <span class="panel-code">查询申请 ID</span>
        </div>
      </header>
      <div class="resolver">
        <el-input
          v-model="queryIdInput"
          class="query-id-input"
          placeholder="输入 AI 或工作台返回的 queryId"
          clearable
          @keyup.enter="resolveQuery()"
        />
        <el-button type="primary" :loading="loading" @click="resolveQuery()">查看查询申请</el-button>
      </div>
    </section>

    <div v-if="error" class="lookup-error">
      <span>查询申请加载失败</span>
      <p>{{ error }}</p>
    </div>

    <section v-if="query" class="panel approval-card" :class="query.status.toLowerCase()">
      <div class="approval-stripe">
        <span v-for="n in 14" :key="n">{{ n % 2 ? '///' : String(n).padStart(2, '0') }}</span>
      </div>
      <header class="approval-header">
        <div>
          <p class="eyebrow">查询申请 / {{ query.queryId }}</p>
          <h2>查询审批详情</h2>
        </div>
        <div class="approval-state">
          <StateChip :value="query.status" />
          <span v-if="query.status === 'PENDING_APPROVAL'">
            审批有效期 <strong>{{ remainingText }}</strong>
          </span>
          <span v-else-if="query.status === 'APPROVED'">剩余有效期 <strong>{{ remainingText }}</strong></span>
        </div>
      </header>

      <div class="approval-body">
        <div class="risk-zone">
          <p class="zone-label">服务端风险信号</p>
          <div v-if="query.riskReasons?.length" class="risk-reasons">
            <div v-for="(reason, index) in query.riskReasons" :key="reason">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <p>{{ reason }}</p>
            </div>
          </div>
          <div v-else class="no-risk">服务端未返回风险原因明细。</div>
        </div>

        <dl class="request-spec">
          <div>
            <dt>查询 ID</dt>
            <dd>{{ query.queryId }}</dd>
          </div>
          <div v-if="query.dataSourceName || query.dataSourceId">
            <dt>数据源</dt>
            <dd>{{ query.dataSourceName || query.dataSourceId }}</dd>
          </div>
          <div>
            <dt>生效限额</dt>
            <dd>{{ query.effectiveMaxRows ?? '由服务端限额' }} 行</dd>
          </div>
          <div>
            <dt>有效期</dt>
            <dd>{{ query.expiresAt ? formatTime(query.expiresAt) : '批准后生成 5 分钟单次许可' }}</dd>
          </div>
          <div v-if="query.purpose">
            <dt>查询用途</dt>
            <dd>{{ query.purpose }}</dd>
          </div>
          <div v-if="query.sqlFingerprint">
            <dt>SQL 指纹</dt>
            <dd class="mono">{{ query.sqlFingerprint }}</dd>
          </div>
        </dl>

        <section class="payload-review sql-review">
          <header>
            <span>绑定的 SQL / 纯文本</span>
            <strong>{{ query.sql ? `${sqlBytes(query.sql)} 字节` : '不可用' }}</strong>
          </header>
          <pre>{{ query.sql || '服务端未返回 SQL；在无法核对完整语句时不要批准此申请。' }}</pre>
        </section>

        <section class="payload-review parameter-review">
          <header>
            <span>绑定参数</span>
            <strong>{{ query.parameters?.length ?? 0 }} 个值</strong>
          </header>
          <div v-if="query.parameters?.length" class="bound-parameters">
            <div v-for="(parameter, index) in query.parameters" :key="index">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <strong>{{ parameter.jdbcType }}</strong>
              <code>{{ valueText(parameter.value) }}</code>
            </div>
          </div>
          <p v-else>该 SQL 没有绑定参数。</p>
        </section>

        <div v-if="!approvalPayloadComplete && ['PENDING_APPROVAL', 'APPROVED'].includes(query.status)" class="payload-missing">
          <strong>当前无法审批</strong>
          服务端未返回完整 SQL、参数或有效限额。为避免盲批，当前申请不能由网页批准或执行。
        </div>

        <div v-if="query.message" class="server-message">
          <span>策略说明</span>
          <p>{{ query.message }}</p>
        </div>
      </div>

      <footer v-if="query.status === 'PENDING_APPROVAL'" class="approval-actions">
        <p><strong>操作员确认：</strong>我已核对风险原因、数据源作用域与查询用途，并理解原始结果可能发送给云端 AI。</p>
        <div>
          <el-button :loading="cancelling" @click="cancel">取消申请</el-button>
          <el-button plain :loading="approving" :disabled="!approvalPayloadComplete" @click="approve">仅批准</el-button>
          <el-button
            type="warning"
            :loading="approving || executing"
            :disabled="!approvalPayloadComplete"
            @click="approveAndExecute"
          >
            批准并执行一次
          </el-button>
        </div>
      </footer>
      <footer v-else-if="query.status === 'APPROVED'" class="approval-actions approved-action">
        <p>一次性许可已就绪。执行后许可立即消费，无自动重试；执行期间可通过独立取消请求中止。</p>
        <div>
          <el-button
            type="danger"
            plain
            :loading="cancelling"
            :disabled="approving"
            @click="cancel"
          >
            {{ executing ? '取消正在执行的查询' : '取消一次性许可' }}
          </el-button>
          <el-button
            type="primary"
            :loading="executing"
            :disabled="cancelling || !approvalPayloadComplete"
            @click="execute"
          >
            执行已批准查询
          </el-button>
        </div>
      </footer>

      <div v-if="query.status === 'EXECUTED' && query.resultAvailable === false" class="result-not-retained">
        <span>结果暂不可查看</span>
        <h3>查询结果已从临时缓存中清除</h3>
        <p>这里只能确认该申请已经执行，不能据此判断结果集为空。结果不写入历史记录，缓存过期或服务重启后不会恢复。</p>
      </div>
      <QueryResultTable v-else-if="query.status === 'EXECUTED'" :result="query" />
    </section>

    <section v-else-if="!loading && !error" class="panel guidance">
      <div class="guidance-mark"><span>审</span></div>
      <div>
        <h2>等待审批信号</h2>
        <p>点击上方待审批队列即可载入，也可以粘贴 AI 客户端收到的 queryId 进行复核。</p>
      </div>
      <ol>
        <li><span>01</span>检查服务端风险原因</li>
        <li><span>02</span>批准创建 5 分钟单次许可</li>
        <li><span>03</span>执行并消费许可</li>
      </ol>
    </section>
  </div>
</template>

<style scoped>
.pending-counter {
  display: flex;
  min-width: 116px;
  align-items: center;
  justify-content: space-between;
  padding: 10px 13px;
  border: 1px solid var(--amber-deep);
  color: var(--amber);
  background: rgba(245, 185, 76, .06);
}

.pending-counter span {
  font:  14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.pending-counter strong {
  font: 650 25px/1 var(--font-mono);
}

.queue-panel,
.resolver-panel {
  margin-bottom: 12px;
}

.queue-panel {
  overflow: hidden;
}

.queue-error,
.queue-empty {
  display: flex;
  min-height: 56px;
  align-items: center;
  gap: 11px;
  padding: 13px 16px;
  color: var(--text-dim);
  font:  14px/1.5 var(--font-mono);
}

.queue-error {
  border-left: 2px solid var(--red);
  color: #9f3f39;
  background: rgba(255, 91, 88, .04);
}

.queue-error span {
  color: var(--red);
  font-weight: 700;
}

.queue-error p {
  margin: 0;
}

.queue-list {
  display: grid;
  max-height: 314px;
  overflow-y: auto;
}

.queue-toolbar {
  display: flex;
  min-height: 46px;
  align-items: center;
  gap: 14px;
  padding: 7px 14px;
  border-bottom: 1px solid var(--line);
  background: #fffefa;
}

.select-all-control,
.selection-count {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-dim);
  font: 13px/1 var(--font-mono);
}

.selection-count {
  color: var(--amber);
}

.queue-toolbar .el-button {
  margin-left: auto;
}

.queue-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 5px;
}

.queue-check input,
.select-all-control input {
  width: 15px;
  height: 15px;
  accent-color: var(--amber);
  cursor: pointer;
}

.queue-list button {
  display: grid;
  min-height: 54px;
  grid-template-columns: 30px minmax(150px, 1.2fr) minmax(180px, 1.5fr) minmax(120px, 1fr) 70px 72px 145px 20px;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border: 0;
  border-bottom: 1px solid var(--line);
  color: var(--text-dim);
  background: transparent;
  cursor: pointer;
  font:  14px/1.4 var(--font-mono);
  text-align: left;
}

.queue-list button:last-child {
  border-bottom: 0;
}

.queue-list button:hover,
.queue-list button.selected {
  background: rgba(245, 185, 76, .055);
}

.queue-list button:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.queue-list button.selected {
  box-shadow: 3px 0 0 var(--amber) inset;
}

.queue-list button > * {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.queue-list button > .queue-check {
  overflow: visible;
  padding: 0;
}

.queue-list strong {
  color: #2c423a;
  font-family: var(--font-display);
  font-size: 13px;
}

.queue-id {
  color: var(--amber);
}

.queue-list button > i {
  color: var(--amber);
  font-size: 13px;
  font-style: normal;
}

.resolver {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
  padding: 16px;
}

.query-id-input {
  font-family: var(--font-mono);
}

.lookup-error {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 12px;
  padding: 15px 17px;
  border: 1px solid var(--red-deep);
  color: #9f3f39;
  background: rgba(255, 91, 88, .05);
}

.lookup-error span {
  color: var(--red);
  font: 700   14px/1 var(--font-mono);
}

.lookup-error p {
  margin: 0;
  font-size: 13px;
}

.approval-card {
  overflow: hidden;
  border-color: #58451e;
}

.approval-stripe {
  display: flex;
  height: 20px;
  align-items: center;
  justify-content: space-around;
  overflow: hidden;
  color: #171107;
  background: var(--amber);
  font: 700 12px/1 var(--font-mono);
}

.approval-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 26px;
  border-bottom: 1px solid var(--line);
}

.approval-header h2 {
  margin: 0;
  color: #5e481f;
  font: 650 24px/1.15 var(--font-display);
}

.approval-state {
  display: flex;
  align-items: center;
  gap: 14px;
}

.approval-state > span:not(.state-chip) {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.approval-state strong {
  margin-left: 5px;
  color: var(--amber);
  font-size: 14px;
}

.approval-body {
  display: grid;
  grid-template-columns: 1.05fr .95fr;
  gap: 28px;
  padding: 26px;
}

.zone-label {
  margin: 0 0 13px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .12em;
}

.risk-reasons {
  display: grid;
  gap: 7px;
}

.risk-reasons > div {
  display: grid;
  min-height: 44px;
  grid-template-columns: 38px 1fr;
  align-items: center;
  border: 1px solid var(--amber-deep);
  background: rgba(245, 185, 76, .045);
}

.risk-reasons span {
  color: var(--amber);
  font:  14px/1 var(--font-mono);
  text-align: center;
}

.risk-reasons p {
  margin: 0;
  padding: 10px;
  border-left: 1px solid var(--amber-deep);
  color: #80652d;
  font-size: 13px;
  line-height: 1.5;
}

.no-risk {
  padding: 20px;
  border: 1px dashed var(--line);
  color: var(--text-dim);
  font-size: 13px;
}

.request-spec {
  display: grid;
  align-content: start;
  gap: 0;
  margin: 0;
  border-top: 1px solid var(--line);
}

.request-spec > div {
  display: grid;
  min-height: 42px;
  grid-template-columns: 125px 1fr;
  align-items: center;
  border-bottom: 1px solid var(--line);
}

.request-spec dt {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.request-spec dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-soft);
  font-size: 13px;
  text-overflow: ellipsis;
}

.payload-review {
  grid-column: 1 / -1;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--line);
  background: #fffdf7;
}

.payload-review > header {
  display: flex;
  min-height: 35px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 0 12px;
  border-bottom: 1px solid var(--line);
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.payload-review > header strong {
  color: #607169;
  font-size: 14px;
}

.sql-review pre {
  max-height: 280px;
  margin: 0;
  overflow: auto;
  padding: 15px;
  color: #2c423a;
  font:  13px/1.65 var(--font-mono);
  tab-size: 2;
  white-space: pre-wrap;
  word-break: break-word;
}

.bound-parameters {
  display: grid;
}

.bound-parameters > div {
  display: grid;
  min-height: 42px;
  grid-template-columns: 44px 120px minmax(0, 1fr);
  align-items: center;
  border-bottom: 1px solid var(--line);
}

.bound-parameters > div:last-child {
  border-bottom: 0;
}

.bound-parameters span {
  color: #607169;
  font:  14px/1 var(--font-mono);
  text-align: center;
}

.bound-parameters strong {
  color: var(--amber);
  font:  14px/1 var(--font-mono);
}

.bound-parameters code {
  min-width: 0;
  overflow: hidden;
  padding: 9px 12px;
  color: #344a42;
  font:  14px/1.5 var(--font-mono);
  text-overflow: ellipsis;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.parameter-review > p {
  margin: 0;
  padding: 16px;
  color: var(--text-dim);
  font:  14px/1.5 var(--font-mono);
}

.payload-missing {
  grid-column: 1 / -1;
  padding: 13px 14px;
  border: 1px solid var(--red-deep);
  color: #9f3f39;
  background: rgba(255, 91, 88, .05);
  font-size: 13px;
  line-height: 1.6;
}

.payload-missing strong {
  display: block;
  margin-bottom: 4px;
  color: var(--red);
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.server-message {
  grid-column: 1 / -1;
  padding: 14px;
  border-left: 2px solid var(--amber);
  color: #80652d;
  background: rgba(245, 185, 76, .035);
}

.server-message span {
  font: 700   14px/1 var(--font-mono);
}

.server-message p {
  margin: 7px 0 0;
  font-size: 13px;
}

.approval-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 26px;
  border-top: 1px solid var(--line);
  background: #fffefa;
}

.approval-actions p {
  max-width: 660px;
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
  line-height: 1.6;
}

.approval-actions > div {
  display: flex;
  gap: 8px;
}

.approved-action {
  border-left: 3px solid var(--green);
}

.result-not-retained {
  padding: 29px 26px;
  border-top: 1px solid var(--line);
  border-left: 3px solid var(--amber);
  background: rgba(245, 185, 76, .045);
}

.result-not-retained span {
  color: var(--amber);
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.result-not-retained h3 {
  margin: 10px 0 6px;
  color: #654818;
  font: 650 16px/1.3 var(--font-display);
}

.result-not-retained p {
  margin: 0;
  color: #80652d;
  font-size: 13px;
  line-height: 1.6;
}

.guidance {
  display: grid;
  min-height: 320px;
  grid-template-columns: 90px 1fr 1fr;
  align-items: center;
  gap: 30px;
  padding: 42px;
}

.guidance-mark {
  display: grid;
  width: 72px;
  height: 72px;
  place-content: center;
  border: 1px solid var(--line-strong);
  transform: rotate(45deg);
}

.guidance-mark span {
  color: var(--text-dim);
  font: 700 14px/1 var(--font-mono);
  transform: rotate(-45deg);
}

.guidance h2 {
  margin: 0 0 9px;
  color: var(--text);
  font: 650 20px/1.2 var(--font-display);
}

.guidance p {
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
  line-height: 1.7;
}

.guidance ol {
  display: grid;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.guidance li {
  padding-bottom: 10px;
  border-bottom: 1px solid var(--line);
  color: var(--text-soft);
  font-size: 13px;
}

.guidance li span {
  margin-right: 12px;
  color: var(--amber);
  font:  14px/1 var(--font-mono);
}

@media (max-width: 820px) {
  .queue-list button {
    min-width: 790px;
  }

  .queue-list {
    overflow-x: auto;
  }

  .approval-body {
    grid-template-columns: 1fr;
  }

  .approval-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .guidance {
    grid-template-columns: 70px 1fr;
  }

  .guidance ol {
    grid-column: 1 / -1;
  }
}

@media (max-width: 570px) {
  .resolver {
    grid-template-columns: 1fr;
  }

  .approval-header,
  .approval-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .approval-state {
    justify-content: space-between;
  }

  .approval-actions > div {
    display: grid;
  }

  .bound-parameters > div {
    grid-template-columns: 34px 90px minmax(0, 1fr);
  }
}
</style>
