<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage, normalizeList } from '../api/client'
import type { AccessTokenSummary, DataSourceSummary } from '../api/types'
import LoadState from '../components/LoadState.vue'
import StateChip from '../components/StateChip.vue'

const loading = ref(true)
const error = ref('')
const tokens = ref<AccessTokenSummary[]>([])
const dataSources = ref<DataSourceSummary[]>([])
const createOpen = ref(false)
const createdOpen = ref(false)
const scopeOpen = ref(false)
const creating = ref(false)
const updatingScope = ref(false)
const deletingId = ref<string | null>(null)
const rawToken = ref('')
const editingToken = ref<AccessTokenSummary | null>(null)

const form = reactive({
  name: '',
  dataSourceIds: [] as string[],
  expiresInDays: 30,
  rawDataAcknowledged: false
})

const scopeForm = reactive({
  dataSourceIds: [] as string[],
  rawDataAcknowledged: false
})

const activeSources = computed(() =>
  dataSources.value.filter((source) => source.enabled && source.readOnlyStatus !== 'BLOCKED')
)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [tokenPayload, sourcePayload] = await Promise.all([api.tokens(), api.dataSources()])
    tokens.value = normalizeList(tokenPayload).items
    dataSources.value = normalizeList(sourcePayload).items
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, {
    name: '',
    dataSourceIds: [],
    expiresInDays: 30,
    rawDataAcknowledged: false
  })
  createOpen.value = true
}

function openScopeEditor(token: AccessTokenSummary) {
  editingToken.value = token
  scopeForm.dataSourceIds = [...(token.dataSourceIds ?? [])]
  scopeForm.rawDataAcknowledged = false
  scopeOpen.value = true
}

async function create() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写令牌用途名称')
    return
  }
  if (!form.dataSourceIds.length) {
    ElMessage.warning('至少选择一个允许访问的数据源')
    return
  }
  if (!form.rawDataAcknowledged) {
    ElMessage.warning('签发前必须明确确认原始数据传输风险')
    return
  }

  creating.value = true
  try {
    const created = await api.createToken({
      name: form.name.trim(),
      dataSourceIds: form.dataSourceIds,
      expiresInDays: form.expiresInDays,
      rawDataAcknowledged: true
    })
    rawToken.value = created.token ?? ''
    createOpen.value = false
    createdOpen.value = true
    await load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    creating.value = false
  }
}

async function copyToken() {
  if (!rawToken.value) return
  try {
    await navigator.clipboard.writeText(rawToken.value)
    ElMessage.success('令牌已复制，请立即写入 AI 客户端配置')
  } catch {
    ElMessage.error('剪贴板写入失败，请手动选择并复制')
  }
}

async function updateScope() {
  const token = editingToken.value
  if (!token) return
  if (!scopeForm.dataSourceIds.length) {
    ElMessage.warning('至少保留一个允许访问的数据源')
    return
  }
  if (!scopeForm.rawDataAcknowledged) {
    ElMessage.warning('修改范围前必须再次确认原始数据传输风险')
    return
  }
  const previous = new Set(token.dataSourceIds ?? [])
  const next = new Set(scopeForm.dataSourceIds)
  if (previous.size === next.size && [...previous].every((id) => next.has(id))) {
    ElMessage.info('数据源范围没有变化')
    return
  }

  try {
    await ElMessageBox.confirm(
      '保存后原 Token 立即使用新的数据源范围，Token 内容、有效期和 MCP 配置均不会变化。',
      '确认调整令牌范围',
      {
        confirmButtonText: '确认保存',
        cancelButtonText: '继续检查',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  updatingScope.value = true
  try {
    await api.updateTokenScope(token.id, {
      dataSourceIds: scopeForm.dataSourceIds,
      rawDataAcknowledged: true
    })
    ElMessage.success('令牌数据源范围已更新，MCP 无需重新配置')
    scopeOpen.value = false
    editingToken.value = null
    await load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    updatingScope.value = false
  }
}

function closeCreated() {
  rawToken.value = ''
  createdOpen.value = false
}

async function revoke(token: AccessTokenSummary) {
  try {
    await ElMessageBox.confirm(
      `吊销“${token.name}”后，使用该令牌的 AI 客户端将立即失去访问权限。`,
      '确认吊销令牌',
      {
        confirmButtonText: '立即吊销',
        cancelButtonText: '保留令牌',
        type: 'warning'
      }
    )
  } catch {
    return
  }

  deletingId.value = token.id
  try {
    await api.deleteToken(token.id)
    ElMessage.success('访问令牌已吊销')
    await load()
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    deletingId.value = null
  }
}

function tokenStatus(token: AccessTokenSummary): string {
  if (token.revoked || token.status === 'REVOKED') return 'REVOKED'
  if (token.expiresAt && new Date(token.expiresAt).getTime() <= Date.now()) return 'EXPIRED'
  return token.status || 'ACTIVE'
}

function formatTime(value?: string): string {
  if (!value) return '从未'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function sourceNames(ids?: string[]): string {
  if (!ids?.length) return '无数据源作用域'
  return ids.map((id) => dataSources.value.find((source) => source.id === id)?.name ?? id).join(' · ')
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">AI 访问凭证 / 最小作用域</p>
        <h1 class="page-title">访问令牌</h1>
        <p class="page-subtitle">令牌只显示一次，服务端仅保存摘要。每个令牌单独限制数据源范围并默认 30 天失效。</p>
      </div>
      <el-button type="primary" @click="openCreate">签发新令牌</el-button>
    </header>

    <div class="token-warning">
      <div class="warning-mark">原始数据</div>
      <p><strong>查询结果不脱敏。</strong>令牌持有的 AI 客户端可能把生产数据发送到云端模型。只授予解决当前问题所需的数据源。</p>
      <span>每个令牌每分钟 30 次请求</span>
    </div>

    <section class="panel token-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">已签发令牌</p>
          <span class="panel-code">摘要存储 / 作用域受控</span>
        </div>
        <span class="token-count">共 {{ tokens.length }} 个</span>
      </header>

      <LoadState
        :loading="loading"
        :error="error"
        :empty="!tokens.length"
        empty-title="尚未签发访问令牌"
        empty-text="为 Codex、Claude 或其他本地 MCP 客户端签发一个最小作用域令牌。"
        @retry="load"
      >
        <template #action>
          <el-button type="primary" class="empty-action" @click="openCreate">签发第一个令牌</el-button>
        </template>

        <div class="token-list">
          <article v-for="token in tokens" :key="token.id">
            <div class="token-symbol">
              <span>••••</span>
              <i />
            </div>
            <div class="token-main">
              <header>
                <h2>{{ token.name }}</h2>
                <StateChip :value="tokenStatus(token)" />
              </header>
              <p>{{ sourceNames(token.dataSourceIds) }}</p>
              <dl>
                <div>
                  <dt>创建时间</dt>
                  <dd>{{ formatTime(token.createdAt) }}</dd>
                </div>
                <div>
                  <dt>失效时间</dt>
                  <dd>{{ formatTime(token.expiresAt) }}</dd>
                </div>
                <div>
                  <dt>最近使用</dt>
                  <dd>{{ formatTime(token.lastUsedAt) }}</dd>
                </div>
                <div>
                  <dt>令牌 ID</dt>
                  <dd class="mono">{{ token.id }}</dd>
                </div>
              </dl>
            </div>
            <div class="token-action">
              <el-button
                plain
                type="primary"
                :disabled="tokenStatus(token) !== 'ACTIVE'"
                @click="openScopeEditor(token)"
              >
                调整范围
              </el-button>
              <el-button
                plain
                type="danger"
                :disabled="tokenStatus(token) === 'REVOKED'"
                :loading="deletingId === token.id"
                @click="revoke(token)"
              >
                吊销
              </el-button>
            </div>
          </article>
        </div>
      </LoadState>
    </section>

    <el-dialog v-model="createOpen" width="620px" title="签发 AI 访问令牌" :close-on-click-modal="false">
      <el-form label-position="top">
        <el-form-item label="令牌名称">
          <el-input v-model="form.name" maxlength="60" placeholder="例如：Codex · 订单问题核对" />
        </el-form-item>
        <el-form-item label="允许访问的数据源">
          <el-select
            v-model="form.dataSourceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择最小必要范围"
            style="width: 100%"
          >
            <el-option
              v-for="source in activeSources"
              :key="source.id"
              :label="`${source.name} · ${source.readOnlyStatus}`"
              :value="source.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="有效天数">
          <el-input-number v-model="form.expiresInDays" :min="1" :max="365" controls-position="right" style="width: 100%" />
        </el-form-item>

        <label class="risk-confirm" :class="{ checked: form.rawDataAcknowledged }">
          <el-checkbox v-model="form.rawDataAcknowledged" />
          <span>
            <strong>我确认原始数据传输风险</strong>
            查询结果不脱敏，可能由持有此令牌的客户端发送给云端 AI；我已按最小范围选择数据源。
          </span>
        </label>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">签发 256 位随机令牌</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="scopeOpen"
      width="620px"
      title="调整令牌数据源范围"
      :close-on-click-modal="false"
    >
      <div class="scope-preservation">
        <strong>原 Token 保持不变</strong>
        <p>这里只更新服务端的数据源作用域，不修改令牌内容、权限、有效期或 MCP 配置。</p>
      </div>
      <el-form v-if="editingToken" label-position="top">
        <el-form-item label="令牌">
          <el-input :model-value="editingToken.name" disabled />
        </el-form-item>
        <el-form-item label="允许访问的数据源">
          <el-select
            v-model="scopeForm.dataSourceIds"
            multiple
            collapse-tags
            collapse-tags-tooltip
            placeholder="至少选择一个数据源"
            style="width: 100%"
          >
            <el-option
              v-for="source in dataSources"
              :key="source.id"
              :label="`${source.name} · ${source.enabled && source.readOnlyStatus !== 'BLOCKED' ? '可用' : '不可用，请移除'}`"
              :value="source.id"
              :disabled="!source.enabled || source.readOnlyStatus === 'BLOCKED'"
            />
          </el-select>
        </el-form-item>
        <label class="risk-confirm" :class="{ checked: scopeForm.rawDataAcknowledged }">
          <el-checkbox v-model="scopeForm.rawDataAcknowledged" />
          <span>
            <strong>我确认扩大后的原始数据访问风险</strong>
            查询结果不脱敏，可能由持有此令牌的客户端发送给云端 AI；当前选择仍符合最小必要范围。
          </span>
        </label>
      </el-form>
      <template #footer>
        <el-button :disabled="updatingScope" @click="scopeOpen = false">取消</el-button>
        <el-button type="primary" :loading="updatingScope" @click="updateScope">
          保存新范围
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="createdOpen"
      width="650px"
      title="令牌已生成 · 仅显示一次"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      :show-close="false"
    >
      <div class="created-alert">
        <span>仅显示一次</span>
        <p>关闭后无法再次查看。服务端仅保存不可逆摘要，如遗失请吊销并重新签发。</p>
      </div>
      <div class="raw-token">
        <code>{{ rawToken || '服务端未返回明文令牌，请取消该记录后重新签发。' }}</code>
        <el-button type="primary" :disabled="!rawToken" @click="copyToken">复制令牌</el-button>
      </div>
      <template #footer>
        <el-button type="warning" @click="closeCreated">我已安全保存，关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.token-warning {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr) auto;
  align-items: center;
  gap: 18px;
  margin-bottom: 12px;
  padding: 14px 18px;
  border: 1px solid var(--amber-deep);
  background: rgba(245, 185, 76, .045);
}

.warning-mark {
  display: grid;
  min-width: 86px;
  height: 36px;
  padding: 0 12px;
  place-content: center;
  border: 1px solid var(--amber);
  color: var(--amber);
  font: 700 14px/1 var(--font-mono);
  letter-spacing: .06em;
  white-space: nowrap;
}

.token-warning p {
  margin: 0;
  color: #80652d;
  font-size: 13px;
  line-height: 1.55;
}

.token-warning > span {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.token-panel {
  overflow: hidden;
}

.token-count {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.empty-action {
  margin-top: 16px;
}

.token-list {
  display: grid;
}

.token-list article {
  display: grid;
  min-height: 150px;
  grid-template-columns: 92px 1fr 110px;
  border-bottom: 1px solid var(--line);
}

.token-list article:last-child {
  border-bottom: 0;
}

.token-symbol {
  display: grid;
  place-content: center;
  border-right: 1px solid var(--line);
  color: var(--green);
  font: 700  13px/1 var(--font-mono);
  letter-spacing: .15em;
}

.token-symbol span {
  padding: 13px 8px;
  border: 1px solid #2e584b;
  transform: skew(-6deg);
}

.token-symbol i {
  width: 6px;
  height: 6px;
  margin: 11px auto 0;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 10px var(--green);
}

.token-main {
  min-width: 0;
  padding: 20px 24px;
}

.token-main header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.token-main h2 {
  margin: 0;
  color: var(--text);
  font: 650 17px/1.2 var(--font-display);
}

.token-main > p {
  margin: 8px 0 18px;
  overflow: hidden;
  color: var(--text-dim);
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.token-main dl {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1.2fr;
  gap: 18px;
  margin: 0;
}

.token-main dt {
  margin-bottom: 5px;
  color: #5e6e67;
  font:  14px/1 var(--font-mono);
}

.token-main dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-soft);
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.token-action {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 20px;
  justify-content: center;
  border-left: 1px solid var(--line);
}

.token-action .el-button {
  width: 100%;
  margin-left: 0;
}

.risk-confirm {
  display: grid;
  grid-template-columns: 28px 1fr;
  gap: 9px;
  padding: 13px;
  border: 1px solid var(--red-deep);
  color: #9f3f39;
  background: rgba(255, 91, 88, .04);
  cursor: pointer;
  transition: border-color .18s ease;
}

.risk-confirm.checked {
  border-color: var(--amber);
  color: #80652d;
}

.risk-confirm span {
  font-size: 13px;
  line-height: 1.55;
}

.risk-confirm strong {
  display: block;
  margin-bottom: 3px;
  color: var(--amber);
}

.scope-preservation {
  margin-bottom: 18px;
  padding: 14px 16px;
  border: 1px solid var(--green-deep);
  border-left: 3px solid var(--green);
  background: rgba(8, 124, 104, .055);
}

.scope-preservation strong {
  color: var(--green);
  font-size: 15px;
}

.scope-preservation p {
  margin: 5px 0 0;
  color: var(--text-soft);
  font-size: 14px;
  line-height: 1.6;
}

.created-alert {
  margin-bottom: 14px;
  padding: 13px;
  border-left: 3px solid var(--amber);
  background: rgba(245, 185, 76, .05);
}

.created-alert span {
  color: var(--amber);
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.created-alert p {
  margin: 6px 0 0;
  color: #80652d;
  font-size: 13px;
}

.raw-token {
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 10px;
}

.raw-token code {
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--line-strong);
  color: var(--green);
  background: #fffdf7;
  font:  13px/1.6 var(--font-mono);
  word-break: break-all;
}

@media (max-width: 800px) {
  .token-list article {
    grid-template-columns: 70px 1fr;
  }

  .token-action {
    grid-column: 2;
    padding: 12px 20px;
    place-content: end;
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .token-main dl {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 570px) {
  .token-warning {
    grid-template-columns: max-content 1fr;
  }

  .token-warning > span {
    grid-column: 1 / -1;
  }

  .token-list article {
    grid-template-columns: 1fr;
  }

  .token-symbol {
    display: none;
  }

  .token-action {
    grid-column: 1;
  }

  .raw-token {
    grid-template-columns: 1fr;
  }
}

/* 令牌页采用更接近管理台的平面卡片布局，保留原有信息密度和操作入口。 */
.token-warning {
  border-radius: var(--radius);
  background: #fffbeb;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .02);
}

.warning-mark {
  border-radius: 6px;
  color: #b45309;
  background: #fff7ed;
}

.token-panel {
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.token-list article {
  min-height: 136px;
  grid-template-columns: 76px 1fr 128px;
  transition: background .18s ease;
}

.token-list article:hover {
  background: var(--bg-panel-2);
}

.token-symbol {
  border-right: 0;
  background: #f8fafc;
}

.token-symbol span {
  padding: 10px 7px;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  color: var(--blue);
  background: #eff6ff;
  transform: none;
}

.token-symbol i {
  width: 5px;
  height: 5px;
  background: var(--blue);
  box-shadow: none;
}

.token-main {
  padding: 20px 22px;
}

.token-main h2 {
  font-size: 16px;
}

.token-action {
  padding: 18px;
  border-left: 1px solid var(--line);
  background: #fcfdff;
}

.raw-token code {
  border-radius: 6px;
  background: #f8fafc;
}
</style>
