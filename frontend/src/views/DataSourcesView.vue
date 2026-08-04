<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage, normalizeList } from '../api/client'
import type {
  CreateDataSourceRequest,
  DatabaseType,
  DataSourceSummary,
  DataSourceTestResult,
  UpdateDataSourceRequest
} from '../api/types'
import LoadState from '../components/LoadState.vue'
import StateChip from '../components/StateChip.vue'

const loading = ref(true)
const error = ref('')
const dataSources = ref<DataSourceSummary[]>([])
const createOpen = ref(false)
const creating = ref(false)
const editOpen = ref(false)
const updating = ref(false)
const editingSource = ref<DataSourceSummary | null>(null)
const testingIds = ref<Set<string>>(new Set())
const selectedIds = ref<string[]>([])
const batchTesting = ref(false)
const deletingId = ref<string | null>(null)

const defaultForm = (): CreateDataSourceRequest => ({
  name: '',
  databaseType: 'MYSQL',
  host: '',
  port: 3306,
  database: '',
  username: '',
  password: '',
  tlsMode: 'REQUIRED',
  timeoutSeconds: 10,
  allowCompatibility: true
})

const form = reactive<CreateDataSourceRequest>(defaultForm())

interface EditForm {
  name: string
  timeoutSeconds: number
  allowCompatibility: boolean
  replaceConnection: boolean
  host: string
  port?: number
  database: string
  username: string
  password: string
  tlsMode: '' | CreateDataSourceRequest['tlsMode']
}

const defaultEditForm = (): EditForm => ({
  name: '',
  timeoutSeconds: 10,
  allowCompatibility: false,
  replaceConnection: false,
  host: '',
  port: undefined,
  database: '',
  username: '',
  password: '',
  tlsMode: ''
})

const editForm = reactive<EditForm>(defaultEditForm())

const statusCounts = computed(() => ({
  strict: dataSources.value.filter((source) => source.readOnlyStatus === 'STRICT').length,
  compatibility: dataSources.value.filter((source) => source.readOnlyStatus === 'COMPATIBILITY').length,
  blocked: dataSources.value.filter((source) => source.readOnlyStatus === 'BLOCKED').length
}))

const selectedCount = computed(() => selectedIds.value.length)
const allSelected = computed(() => dataSources.value.length > 0 && selectedCount.value === dataSources.value.length)

function isTesting(id: string): boolean {
  return testingIds.value.has(id)
}

function setTesting(id: string, active: boolean) {
  const next = new Set(testingIds.value)
  if (active) next.add(id)
  else next.delete(id)
  testingIds.value = next
}

watch(() => form.databaseType, (next, previous) => {
  const previousDefault = previous === 'OCEANBASE_ORACLE' ? 2883 : 3306
  if (form.port === previousDefault) form.port = next === 'OCEANBASE_ORACLE' ? 2883 : 3306
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    dataSources.value = normalizeList(await api.dataSources()).items
    const availableIds = new Set(dataSources.value.map((source) => source.id))
    selectedIds.value = selectedIds.value.filter((id) => availableIds.has(id))
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, defaultForm())
  createOpen.value = true
}

function clearCreateSecret() {
  form.password = ''
}

function applyTestResult(id: string, checked: DataSourceTestResult) {
  const index = dataSources.value.findIndex((item) => item.id === id)
  if (index < 0) return
  const source = dataSources.value[index]
  dataSources.value[index] = {
    ...source,
    readOnlyStatus: checked.readOnlyStatus,
    enabled: checked.enabled,
    detectedVersion: checked.databaseVersion ?? source.detectedVersion,
    lastCheckedAt: new Date().toISOString(),
    riskMessage: checked.message
  }
}

function showTestOutcome(checked: DataSourceTestResult, saved: boolean) {
  const prefix = saved ? '已保存。' : ''
  if (checked.readOnlyStatus === 'COMPATIBILITY' && checked.enabled) {
    ElMessage.success(`${prefix}连接检查通过，查询操作由网关统一执行只读控制`)
  } else if (checked.readOnlyStatus === 'STRICT' && checked.enabled) {
    ElMessage.success(`${prefix}连接检查通过`)
  } else {
    ElMessage.warning(`${prefix}${checked.message || '连接状态无法确认，数据源已保持隔离'}`)
  }
}

async function create() {
  if (!form.name.trim() || !form.host.trim() || !form.database.trim() || !form.username.trim() || !form.password) {
    ElMessage.warning('请填写名称、主机、数据库、用户名和密码')
    return
  }
  if (form.timeoutSeconds < 5 || form.timeoutSeconds > 30) {
    ElMessage.warning('查询超时必须在 5–30 秒之间')
    return
  }
  creating.value = true
  let saved: DataSourceSummary
  try {
    const payload: CreateDataSourceRequest = {
      ...form,
      name: form.name.trim(),
      host: form.host.trim(),
      database: form.database.trim(),
      username: form.username.trim()
    }
    saved = await api.createDataSource(payload)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
    creating.value = false
    return
  }

  form.password = ''
  createOpen.value = false
  setTesting(saved.id, true)
  let checked: DataSourceTestResult | undefined
  try {
    checked = await api.testDataSource(saved.id)
    showTestOutcome(checked, true)
  } catch (cause) {
    ElMessage.error(`数据源已保存但检测失败，系统已保持隔离；请编辑连接字段后重试。${errorMessage(cause)}`)
  } finally {
    await load()
    if (checked) applyTestResult(saved.id, checked)
    setTesting(saved.id, false)
    creating.value = false
  }
}

async function testConnection(source: DataSourceSummary, announce = true): Promise<boolean> {
  if (isTesting(source.id)) return false
  setTesting(source.id, true)
  try {
    const checked = await api.testDataSource(source.id)
    applyTestResult(source.id, checked)
    if (announce) showTestOutcome(checked, false)
    return checked.reachable && checked.enabled
  } catch (cause) {
    if (announce) {
      await load()
      ElMessage.error(`复检失败，数据源已隔离。${errorMessage(cause)}`)
    }
    return false
  } finally {
    setTesting(source.id, false)
  }
}

function toggleSelection(id: string, event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  selectedIds.value = checked
    ? [...new Set([...selectedIds.value, id])]
    : selectedIds.value.filter((selectedId) => selectedId !== id)
}

function toggleAllSelection(event: Event) {
  const checked = (event.target as HTMLInputElement).checked
  selectedIds.value = checked ? dataSources.value.map((source) => source.id) : []
}

async function testSelected() {
  const sources = dataSources.value.filter((source) => selectedIds.value.includes(source.id) && !isTesting(source.id))
  if (!sources.length) {
    ElMessage.warning('请先选择需要复检的数据源')
    return
  }

  batchTesting.value = true
  try {
    const results = await Promise.all(sources.map((source) => testConnection(source, false)))
    await load()
    const passed = results.filter(Boolean).length
    const failed = results.length - passed
    if (failed) {
      ElMessage.warning(`已完成 ${results.length} 个数据源复检：${passed} 个通过，${failed} 个失败并保持隔离`)
    } else {
      ElMessage.success(`已完成 ${passed} 个数据源复检`)
    }
  } finally {
    batchTesting.value = false
  }
}

function openEdit(source: DataSourceSummary) {
  editingSource.value = source
  Object.assign(editForm, {
    ...defaultEditForm(),
    name: source.name,
    timeoutSeconds: source.timeoutSeconds,
    allowCompatibility: true
  })
  editOpen.value = true
}

function clearEditSecrets() {
  editForm.host = ''
  editForm.port = undefined
  editForm.database = ''
  editForm.username = ''
  editForm.password = ''
  editForm.tlsMode = ''
  editForm.replaceConnection = false
  editingSource.value = null
}

function optionalTrimmed(value: string): string | undefined {
  const normalized = value.trim()
  return normalized || undefined
}

async function updateDataSource() {
  const source = editingSource.value
  if (!source) return
  const name = editForm.name.trim()
  if (!name) {
    ElMessage.warning('显示名称不能为空')
    return
  }
  if (editForm.timeoutSeconds < 5 || editForm.timeoutSeconds > 30) {
    ElMessage.warning('查询超时必须在 5–30 秒之间')
    return
  }

  const payload: UpdateDataSourceRequest = {
    name,
    timeoutSeconds: editForm.timeoutSeconds,
    allowCompatibility: editForm.allowCompatibility
  }
  if (editForm.replaceConnection) {
    payload.host = optionalTrimmed(editForm.host)
    payload.port = editForm.port
    payload.database = optionalTrimmed(editForm.database)
    payload.username = optionalTrimmed(editForm.username)
    payload.password = editForm.password || undefined
    payload.tlsMode = editForm.tlsMode || undefined
  }
  const connectionChanged = payload.host !== undefined
    || payload.port !== undefined
    || payload.database !== undefined
    || payload.username !== undefined
    || payload.password !== undefined
    || payload.tlsMode !== undefined
  const policyChanged = source.allowCompatibility !== payload.allowCompatibility

  updating.value = true
  try {
    await api.updateDataSource(source.id, payload)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
    updating.value = false
    return
  }

  editForm.password = ''
  editOpen.value = false
  let checked: DataSourceTestResult | undefined
  if (connectionChanged || policyChanged) {
    setTesting(source.id, true)
    try {
      checked = await api.testDataSource(source.id)
      showTestOutcome(checked, true)
    } catch (cause) {
      ElMessage.error(`设置已保存但复检失败，数据源已保持隔离。${errorMessage(cause)}`)
    } finally {
      setTesting(source.id, false)
    }
  } else {
    ElMessage.success('数据源配置已更新')
  }
  await load()
  if (checked) applyTestResult(source.id, checked)
  updating.value = false
}

async function deleteDataSource(source: DataSourceSummary) {
  try {
    await ElMessageBox.confirm(
      `确定删除“${source.name}”吗？服务端保管的连接凭据会一并移除，历史审计仍将保留。`,
      '删除数据源',
      {
        type: 'warning',
        confirmButtonText: '删除并移除凭据',
        cancelButtonText: '取消'
      }
    )
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(errorMessage(cause))
    return
  }

  deletingId.value = source.id
  try {
    await api.deleteDataSource(source.id)
    dataSources.value = dataSources.value.filter((item) => item.id !== source.id)
    selectedIds.value = selectedIds.value.filter((id) => id !== source.id)
    ElMessage.success('数据源及其服务端凭据已删除，历史审计保留')
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    deletingId.value = null
  }
}

function databaseLabel(type: DatabaseType): string {
  return {
    MYSQL: 'MySQL',
    OCEANBASE_MYSQL: 'OceanBase · MySQL',
    OCEANBASE_ORACLE: 'OceanBase · Oracle'
  }[type]
}

function formatTime(value?: string): string {
  if (!value) return '尚未检测'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">数据源管理 / 连接状态</p>
        <h1 class="page-title">数据源</h1>
        <p class="page-subtitle">管理数据库连接，查看连接状态并执行连接复检。凭据由本机服务安全保存，网页不会显示。</p>
      </div>
      <el-button type="primary" @click="openCreate">接入数据源</el-button>
    </header>

    <div class="status-bar">
      <span>已接入 <strong>{{ dataSources.length }}</strong></span>
      <span class="text-green">数据库只读 <strong>{{ statusCounts.strict }}</strong></span>
      <span class="text-green">网关只读 <strong>{{ statusCounts.compatibility }}</strong></span>
      <span class="text-red">已阻断 <strong>{{ statusCounts.blocked }}</strong></span>
    </div>

    <section class="panel source-panel">
      <LoadState
        :loading="loading"
        :error="error"
        :empty="!dataSources.length"
        empty-title="尚未接入数据源"
        empty-text="先添加数据库连接。系统只检查连通性，SQL 只读由网关强制执行。"
        @retry="load"
      >
        <template #action>
          <el-button type="primary" class="empty-action" @click="openCreate">添加第一个数据源</el-button>
        </template>

        <div class="source-toolbar">
          <label class="select-all-control">
            <input
              type="checkbox"
              :checked="allSelected"
              :indeterminate="selectedCount > 0 && !allSelected"
              @change="toggleAllSelection"
            />
            <span>全选</span>
          </label>
          <span class="selection-count">已选择 {{ selectedCount }} 个</span>
          <el-button
            type="primary"
            plain
            :loading="batchTesting"
            :disabled="selectedCount === 0 || batchTesting"
            @click="testSelected"
          >
            批量连接复检
          </el-button>
          <span class="toolbar-note">可以同时复检多个数据源</span>
        </div>

        <div class="source-list">
          <article v-for="(source, index) in dataSources" :key="source.id" class="source-card">
            <div class="source-index">
              <span>{{ String(index + 1).padStart(2, '0') }}</span>
              <input
                type="checkbox"
                :checked="selectedIds.includes(source.id)"
                :aria-label="`选择数据源 ${source.name}`"
                @change="toggleSelection(source.id, $event)"
              />
            </div>
            <div class="source-main">
              <header>
                <div class="source-identity">
                  <h2>{{ source.name }}</h2>
                  <span>{{ databaseLabel(source.databaseType) }}</span>
                </div>
                <div class="source-state">
                  <StateChip :value="source.readOnlyStatus" />
                  <span class="accepted">网关只读策略</span>
                </div>
              </header>
              <dl>
                <div>
                  <dt>数据库版本</dt>
                  <dd>{{ source.detectedVersion || '等待检测' }}</dd>
                </div>
                <div>
                  <dt>查询限制</dt>
                  <dd>{{ source.maxRows }} 行 / {{ source.timeoutSeconds }} 秒</dd>
                </div>
                <div>
                  <dt>凭据状态</dt>
                  <dd>服务端管理 / 不向网页公开</dd>
                </div>
                <div>
                  <dt>最近检查</dt>
                  <dd>{{ formatTime(source.lastCheckedAt) }}</dd>
                </div>
              </dl>
              <p v-if="source.riskMessage" class="risk-message" :class="source.readOnlyStatus.toLowerCase()">
                <strong>{{ source.readOnlyStatus === 'BLOCKED' ? '异常' : '状态' }}</strong>{{ source.riskMessage }}
              </p>
            </div>
            <div class="source-action">
              <span :class="{ disabled: !source.enabled }">{{ source.enabled ? '已启用' : '已隔离' }}</span>
              <div class="action-grid">
                <el-button
                  plain
                  :loading="isTesting(source.id)"
                  :disabled="deletingId === source.id"
                  @click="testConnection(source)"
                >
                  连接复检
                </el-button>
                <el-button
                  plain
                  :disabled="isTesting(source.id) || deletingId === source.id"
                  @click="openEdit(source)"
                >
                  编辑 / 轮换
                </el-button>
                <el-button
                  plain
                  type="danger"
                  :loading="deletingId === source.id"
                  :disabled="isTesting(source.id)"
                  @click="deleteDataSource(source)"
                >
                  删除
                </el-button>
              </div>
            </div>
          </article>
        </div>
      </LoadState>
    </section>

    <el-dialog
      v-model="createOpen"
      width="720px"
      title="接入受控数据源"
      :close-on-click-modal="false"
      @closed="clearCreateSecret"
    >
      <div class="dialog-note">
        <span>只读</span>
        <p><strong>查询统一通过网关执行。</strong>SQL AST、只读事务和 executeQuery 等多层控制会将操作限制为只读查询。</p>
      </div>
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="显示名称">
            <el-input v-model="form.name" maxlength="60" placeholder="例如：生产订单库" />
          </el-form-item>
          <el-form-item label="数据库类型">
            <el-select v-model="form.databaseType" style="width: 100%">
              <el-option label="MySQL" value="MYSQL" />
              <el-option label="OceanBase · MySQL 模式" value="OCEANBASE_MYSQL" />
              <el-option label="OceanBase · Oracle 模式" value="OCEANBASE_ORACLE" />
            </el-select>
          </el-form-item>
          <el-form-item label="主机地址">
            <el-input v-model="form.host" autocomplete="off" placeholder="数据库主机名或 IP" />
          </el-form-item>
          <el-form-item label="端口">
            <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="数据库 / Schema">
            <el-input v-model="form.database" autocomplete="off" placeholder="目标业务库" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input
              v-model="form.username"
              autocomplete="off"
              :placeholder="form.databaseType === 'MYSQL' ? '数据库账号' : '按 OceanBase 部署填写 user@tenant#cluster'"
            />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.password" type="password" autocomplete="new-password" placeholder="不会写入 SQLite" />
          </el-form-item>
          <el-form-item label="TLS 模式">
            <el-select v-model="form.tlsMode" style="width: 100%">
              <el-option label="必须使用 TLS" value="REQUIRED" />
              <el-option label="校验证书与主机名" value="VERIFY_IDENTITY" />
              <el-option label="关闭 TLS（不推荐）" value="DISABLED" />
            </el-select>
          </el-form-item>
          <el-form-item label="查询超时（秒）">
            <el-input-number v-model="form.timeoutSeconds" :min="5" :max="30" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">保存并检查连接</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="editOpen"
      width="720px"
      title="编辑数据源 / 轮换凭据"
      :close-on-click-modal="false"
      @closed="clearEditSecrets"
    >
      <div class="redaction-note">
        <span>敏感信息已隐藏</span>
        <p>
          服务端不会向网页回传主机、数据库、用户名、密码或 TLS 配置。下方连接字段始终为空；
          仅填写需要替换的项目，其余值由服务端凭据存储保持不变。
        </p>
      </div>
      <el-form v-if="editingSource" label-position="top">
        <div class="form-grid">
          <el-form-item label="显示名称">
            <el-input v-model="editForm.name" maxlength="60" />
          </el-form-item>
          <el-form-item label="数据库类型（不可变更）">
            <el-input :model-value="databaseLabel(editingSource.databaseType)" disabled />
          </el-form-item>
          <el-form-item label="查询超时（秒）">
            <el-input-number
              v-model="editForm.timeoutSeconds"
              :min="5"
              :max="30"
              controls-position="right"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item class="full-span" label="连接信息与凭据">
            <div class="replace-control">
              <div>
                <strong>重新输入连接字段</strong>
                <small>开启后也不会读取或显示旧值</small>
              </div>
              <el-switch
                v-model="editForm.replaceConnection"
                inline-prompt
                active-text="重填"
                inactive-text="保留"
              />
            </div>
          </el-form-item>
          <template v-if="editForm.replaceConnection">
            <el-form-item label="新主机地址（可选）">
              <el-input
                v-model="editForm.host"
                autocomplete="off"
                placeholder="留空保持原地址；旧值不会回显"
              />
            </el-form-item>
            <el-form-item label="新端口（可选）">
              <el-input-number
                v-model="editForm.port"
                :min="1"
                :max="65535"
                controls-position="right"
                placeholder="保持原端口"
                style="width: 100%"
              />
            </el-form-item>
            <el-form-item label="新数据库 / Schema（可选）">
              <el-input
                v-model="editForm.database"
                autocomplete="off"
                placeholder="留空保持原数据库"
              />
            </el-form-item>
            <el-form-item label="新用户名（可选）">
              <el-input
                v-model="editForm.username"
                autocomplete="off"
                placeholder="留空保持原用户名"
              />
            </el-form-item>
            <el-form-item label="新密码（可选）">
              <el-input
                v-model="editForm.password"
                type="password"
                autocomplete="new-password"
                placeholder="留空保持服务端凭据存储中的原密码"
              />
            </el-form-item>
            <el-form-item label="新 TLS 模式（可选）">
              <el-select v-model="editForm.tlsMode" style="width: 100%">
                <el-option label="保持原 TLS 配置" value="" />
                <el-option label="必须使用 TLS" value="REQUIRED" />
                <el-option label="校验证书与主机名" value="VERIFY_IDENTITY" />
                <el-option label="关闭 TLS（不推荐）" value="DISABLED" />
              </el-select>
            </el-form-item>
          </template>
        </div>
        <p class="edit-risk">
          查询操作由网关通过 SQL AST、只读事务和 JDBC 查询接口统一执行只读控制。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="editOpen = false">取消</el-button>
        <el-button type="primary" :loading="updating" @click="updateDataSource">
          保存{{ editForm.replaceConnection ? '并复检' : '' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.status-bar {
  display: flex;
  gap: 24px;
  margin-bottom: 12px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  background: var(--bg-panel-2);
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .04em;
}

.status-bar strong {
  margin-left: 4px;
  color: currentColor;
  font-size: 14px;
}

.source-panel {
  overflow: hidden;
}

.source-toolbar {
  display: flex;
  min-height: 58px;
  align-items: center;
  gap: 14px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-panel-2);
}

.select-all-control {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--text-soft);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

.selection-count,
.toolbar-note {
  color: var(--text-dim);
  font: 13px/1.3 var(--font-mono);
}

.toolbar-note {
  margin-left: auto;
}

.source-toolbar input,
.source-index input {
  width: 16px;
  height: 16px;
  margin: 0;
  accent-color: var(--green);
  cursor: pointer;
}

.empty-action {
  margin-top: 16px;
}

.source-list {
  display: grid;
}

.source-card {
  display: grid;
  min-height: 177px;
  grid-template-columns: 58px 1fr 178px;
  border-bottom: 1px solid var(--line);
}

.source-card:last-child {
  border-bottom: 0;
}

.source-index {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 14px;
  padding: 23px 0;
  border-right: 1px solid var(--line);
  color: var(--text-dim);
  font:  13px/1 var(--font-mono);
  text-align: center;
}

.source-main {
  padding: 21px 24px;
}

.source-main header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
}

.source-main h2 {
  margin: 0;
  color: #1b2925;
  font: 650 18px/1.2 var(--font-display);
}

.source-identity > span {
  display: block;
  margin-top: 5px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .09em;
}

.source-state {
  display: grid;
  justify-items: end;
  gap: 8px;
}

.source-state > span {
  color: var(--text-dim);
  font: 12px/1 var(--font-mono);
  letter-spacing: .08em;
}

.source-state > span.accepted {
  color: var(--green);
}

dl {
  display: grid;
  grid-template-columns: 1.2fr 1fr 1fr 1.2fr;
  gap: 18px;
  margin: 0;
}

dt {
  margin-bottom: 6px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .09em;
}

dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-soft);
  font:  14px/1.4 var(--font-mono);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.risk-message {
  margin: 15px 0 0;
  padding: 8px 10px;
  border-left: 2px solid var(--green);
  color: #176353;
  background: rgba(8, 124, 104, .045);
  font-size: 13px;
  line-height: 1.5;
}

.risk-message.blocked {
  border-color: var(--red);
  color: #9f3f39;
  background: rgba(255, 91, 88, .05);
}

.risk-message strong {
  margin-right: 9px;
  font:  14px/1 var(--font-mono);
}

.source-action {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 15px;
  padding: 20px;
  border-left: 1px solid var(--line);
}

.source-action > span {
  color: var(--green);
  font:  14px/1 var(--font-mono);
  letter-spacing: .12em;
  text-align: center;
}

.source-action > span::before {
  display: inline-block;
  width: 5px;
  height: 5px;
  margin-right: 7px;
  border-radius: 50%;
  content: "";
  background: currentColor;
}

.source-action > span.disabled {
  color: var(--red);
}

.action-grid {
  display: grid;
  gap: 7px;
}

.action-grid :deep(.el-button) {
  width: 100%;
  margin: 0;
}

.dialog-note {
  display: grid;
  grid-template-columns: 52px 1fr;
  gap: 10px;
  margin-bottom: 21px;
  padding: 12px;
  border: 1px solid var(--green-deep);
  color: #176353;
  background: rgba(8, 124, 104, .045);
}

.dialog-note > span {
  display: grid;
  height: 27px;
  place-content: center;
  border: 1px solid var(--green);
  color: var(--green);
  font: 700 12px/1 var(--font-display);
}

.dialog-note p {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
}

.redaction-note {
  display: grid;
  grid-template-columns: 78px 1fr;
  gap: 13px;
  margin-bottom: 21px;
  padding: 13px;
  border: 1px solid #75877f;
  color: #52645d;
  background:
    repeating-linear-gradient(
      -45deg,
      rgba(65, 79, 75, .07) 0,
      rgba(65, 79, 75, .07) 6px,
      transparent 6px,
      transparent 12px
    ),
    #f2f4ef;
}

.redaction-note > span {
  display: grid;
  place-content: center;
  border: 1px solid #52645d;
  color: #52645d;
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .12em;
}

.redaction-note p {
  margin: 0;
  font-size: 13px;
  line-height: 1.65;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 18px;
}

.full-span {
  grid-column: 1 / -1;
}

.replace-control {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 12px 13px;
  border: 1px solid var(--line);
  background: #f2f4ef;
}

.replace-control strong {
  display: block;
  color: #2c423a;
  font-size: 13px;
  font-weight: 600;
}

.replace-control small {
  display: block;
  margin-top: 4px;
  color: #607169;
  font:  14px/1.4 var(--font-mono);
}

.field-alert,
.edit-risk {
  width: 100%;
  margin: 7px 0 0;
  color: #8c5f21;
  font-size: 14px;
  line-height: 1.55;
}

.edit-risk {
  padding: 9px 11px;
  border-left: 2px solid var(--green);
  color: #176353;
  background: rgba(8, 124, 104, .045);
}

@media (max-width: 900px) {
  .source-card {
    grid-template-columns: 44px 1fr;
  }

  .source-action {
    grid-column: 2;
    display: grid;
    grid-template-columns: auto 1fr;
    align-items: center;
    border-top: 1px solid var(--line);
    border-left: 0;
  }

  .action-grid {
    grid-template-columns: repeat(3, 1fr);
  }

  dl {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 620px) {
  .status-bar {
    overflow-x: auto;
  }

  .status-bar span {
    white-space: nowrap;
  }

  .source-card {
    grid-template-columns: 1fr;
  }

  .source-index {
    flex-direction: row;
    justify-content: flex-start;
    min-height: 42px;
    padding: 12px 15px;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }

  .source-index > span {
    display: none;
  }

  .source-main {
    padding: 18px 15px;
  }

  .source-action {
    grid-column: 1;
    grid-template-columns: 1fr;
    padding: 13px 15px;
  }

  .action-grid {
    grid-template-columns: 1fr;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }

  .source-toolbar {
    flex-wrap: wrap;
  }

  .toolbar-note {
    width: 100%;
    margin-left: 0;
  }

  .full-span {
    grid-column: 1;
  }
}

/* 数据源列表使用清晰的表格式卡片，突出状态和操作，不使用装饰性边框。 */
.status-bar {
  gap: 28px;
  border-radius: var(--radius);
  background: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .02);
}

.source-panel {
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.source-card {
  min-height: 164px;
  grid-template-columns: 62px 1fr 170px;
  transition: background .18s ease;
}

.source-card:hover {
  background: var(--bg-panel-2);
}

.source-index {
  color: var(--text-dim);
  background: var(--bg-panel-2);
}

.source-main {
  padding: 20px 22px;
}

.source-main h2 {
  color: var(--text);
  font-size: 16px;
}

.source-action {
  padding: 18px;
  background: var(--bg-panel-2);
}

.action-grid :deep(.el-button) {
  border-radius: 6px;
}

.dialog-note,
.redaction-note,
.edit-risk {
  border-radius: 6px;
}
</style>
