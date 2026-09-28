<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from '../api/client'
import type { ServerSummary, ServerConnection, ServerResult, SshEndpoint } from '../api/types'
import LoadState from '../components/LoadState.vue'

const servers = ref<ServerSummary[]>([])
const loading = ref(true)
const error = ref('')
const editingId = ref<string | null>(null)
const editorOpen = ref(false)
const saving = ref(false)
const testingConfiguration = ref(false)
const connectionVerified = ref(false)
const connectionMessage = ref('')
let formRevision = 0
const workingId = ref('')
const jumpEnabled = ref(false)
const result = ref<ServerResult | null>(null)
const executionError = ref('')
const executing = ref(false)
const execution = reactive({ serverId: '', command: 'uname -a | head -n 5', purpose: '', timeoutSeconds: 15 })
const endpoint = (): SshEndpoint => ({ host: '', port: 22, username: '', authType: 'PASSWORD', password: '', privateKey: '', passphrase: '', fingerprint: '' })
const form = reactive({ name: '', target: endpoint(), jump: endpoint() })
const endpoints = computed(() => jumpEnabled.value
  ? [{ label: '目标服务器', value: form.target }, { label: '跳板机', value: form.jump }]
  : [{ label: '目标服务器', value: form.target }])
const selected = computed(() => servers.value.find(s => s.id === execution.serverId))

// 修改任意配置都撤销上次测试结果，防止旧连接的成功状态用于新参数。
watch([form, jumpEnabled, editingId], () => {
  formRevision++
  connectionVerified.value = false
  connectionMessage.value = ''
}, { deep: true, flush: 'sync' })

async function load() {
  loading.value = true
  error.value = ''
  try { servers.value = await api.servers() }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}

async function edit(server?: ServerSummary) {
  connectionVerified.value = false
  connectionMessage.value = ''
  editingId.value = server?.id ?? null
  Object.assign(form, { name: server?.name ?? '', target: endpoint(), jump: endpoint() })
  jumpEnabled.value = false
  try {
    if (server) {
      const connection = await api.serverConnection(server.id)
      Object.assign(form.target, connection.target)
      if (connection.jump) { Object.assign(form.jump, connection.jump); jumpEnabled.value = true }
    }
    editorOpen.value = true
  } catch (cause) { ElMessage.error(errorMessage(cause)) }
}

function clearSecrets() {
  connectionVerified.value = false
  connectionMessage.value = ''
  // 关闭配置框立即移除页面内存中的认证材料，接口永不回显已有密码或私钥。
  for (const value of [form.target, form.jump]) {
    value.password = ''; value.privateKey = ''; value.passphrase = ''
  }
}

function validateForm(): boolean {
  if (!form.name.trim()) { ElMessage.warning('请填写服务器名称'); return false }
  for (const { label, value } of endpoints.value) {
    if (!value.host.trim() || !value.username.trim()) { ElMessage.warning(`请填写${label}的地址和账号`); return false }
    if (!editingId.value && !(value.authType === 'PASSWORD' ? value.password : value.privateKey)) {
      ElMessage.warning(`请填写${label}的认证凭据`); return false
    }
  }
  return true
}

function configurationPayload() {
  const connection: ServerConnection = { target: { ...form.target }, jump: jumpEnabled.value ? { ...form.jump } : null }
  return { name: form.name.trim(), connection }
}

async function testConfiguration() {
  if (!validateForm()) return
  testingConfiguration.value = true
  connectionVerified.value = false
  connectionMessage.value = ''
  const revision = formRevision
  try {
    const response = await api.testServerConfiguration(editingId.value, configurationPayload())
    if (revision === formRevision) {
      connectionVerified.value = response.reachable
      connectionMessage.value = response.message
    }
  } catch (cause) {
    connectionMessage.value = errorMessage(cause)
  } finally { testingConfiguration.value = false }
}

async function save() {
  if (!validateForm()) return
  if (!connectionVerified.value) { ElMessage.warning('请先测试连接，通过后才能保存'); return }
  saving.value = true
  try {
    await api.saveServer(editingId.value, configurationPayload())
    editorOpen.value = false
    clearSecrets()
    ElMessage.success('连接校验通过，配置已保存并启用，默认查询模式')
    await load()
  } catch (cause) {
    connectionVerified.value = false
    connectionMessage.value = errorMessage(cause)
  } finally { saving.value = false }
}

async function remove(server: ServerSummary) {
  try {
    await ElMessageBox.confirm(
      `删除“${server.name}”的连接配置、保存的凭据及令牌中的对应授权。不会删除远程服务器的文件或服务；以后使用需重新添加。`,
      '删除服务器配置', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
  } catch { return }
  workingId.value = server.id
  try {
    await api.deleteServer(server.id)
    if (execution.serverId === server.id) { execution.serverId = ''; result.value = null }
    ElMessage.success('服务器配置已删除')
    await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)); await load() }
  finally { workingId.value = '' }
}

async function test(server: ServerSummary) {
  workingId.value = server.id
  try {
    const updated = await api.testServer(server.id)
    if (updated.enabled) ElMessage.success(updated.lastTestMessage ?? '连接成功')
    else ElMessage.warning(updated.lastTestMessage ?? '连接未通过')
  } catch (cause) { ElMessage.error(errorMessage(cause)) }
  finally { workingId.value = ''; await load() }
}

async function toggleAccess(server: ServerSummary) {
  const fullAccess = !server.fullAccess
  if (fullAccess) {
    try {
      await ElMessageBox.confirm(
        `开启后，所有已获授权的 AI 令牌都能在“${server.name}”执行任意命令，包括修改文件、删除数据、停止服务。实际能力受 SSH 账号权限限制。是否开启？`,
        '开启完整权限', { type: 'warning', confirmButtonText: '开启完整权限', cancelButtonText: '保持查询模式' }
      )
    } catch { return }
  }
  workingId.value = server.id
  try {
    await api.serverAccess(server.id, fullAccess)
    ElMessage.success(fullAccess ? '已开启完整权限' : '已恢复查询模式')
    await load()
  } catch (cause) { ElMessage.error(errorMessage(cause)) }
  finally { workingId.value = '' }
}

async function disable(server: ServerSummary) {
  workingId.value = server.id
  try { await api.disableServer(server.id); await load() }
  catch (cause) { ElMessage.error(errorMessage(cause)) }
  finally { workingId.value = '' }
}

async function execute() {
  if (!execution.serverId || !execution.command.trim() || !execution.purpose.trim()) {
    ElMessage.warning('请选择服务器并填写命令和用途'); return
  }
  executing.value = true
  executionError.value = ''
  result.value = null
  try { result.value = await api.executeServer({ ...execution }) }
  catch (cause) { executionError.value = errorMessage(cause) }
  finally { executing.value = false }
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">SSH / AI 运维接入</p>
        <h1 class="page-title">Linux 服务器</h1>
        <p class="page-subtitle">凭据由本机网关保管。AI 编写命令，通过授权令牌访问服务器。</p>
      </div>
      <el-button type="primary" @click="edit()">新增服务器</el-button>
    </header>

    <section class="panel server-panel">
      <header class="panel-header">
        <div><p class="panel-title">服务器连接</p><span class="panel-code">默认查询模式 · 每台独立授权</span></div>
        <el-button text @click="load">刷新</el-button>
      </header>
      <LoadState :loading="loading" :error="error" :empty="!servers.length" empty-title="还没有服务器" empty-description="添加直连或单级跳板机 SSH 连接，测试后在访问令牌中授权。" @retry="load">
        <div class="server-list">
          <article v-for="server in servers" :key="server.id" class="server-row">
            <div class="server-info">
              <h2>{{ server.name }} <el-tag :type="server.enabled ? 'success' : 'info'" size="small">{{ server.enabled ? '已启用' : '未启用' }}</el-tag></h2>
              <code>{{ server.id }}</code>
              <p v-if="server.lastTestMessage" class="connection-message">{{ server.lastTestMessage }}</p>
            </div>
            <div class="access-control">
              <span>{{ server.fullAccess ? '完整权限' : '查询模式' }}</span>
              <el-switch :model-value="server.fullAccess" :disabled="workingId === server.id" :aria-label="`${server.name}完整权限`" @change="toggleAccess(server)" />
            </div>
            <div class="server-actions">
              <el-button :disabled="workingId === server.id" @click="edit(server)">配置</el-button>
              <el-button :loading="workingId === server.id" @click="test(server)">测试并启用</el-button>
              <el-button v-if="server.enabled" :disabled="workingId === server.id" @click="disable(server)">停用</el-button>
              <el-button type="danger" plain :disabled="workingId === server.id" @click="remove(server)">删除</el-button>
            </div>
          </article>
        </div>
      </LoadState>
    </section>

    <section class="panel command-panel">
      <header class="panel-header"><div><p class="panel-title">命令验证</p><span class="panel-code">与 MCP 使用同一执行链 · 非交互式 SSH</span></div></header>
      <div class="command-body">
        <div class="command-fields">
          <el-select v-model="execution.serverId" placeholder="选择已启用的服务器" aria-label="执行服务器" :disabled="executing">
            <el-option v-for="server in servers.filter(s => s.enabled)" :key="server.id" :value="server.id" :label="server.name" />
          </el-select>
          <el-input v-model="execution.purpose" maxlength="500" placeholder="执行用途，如排查订单服务异常" aria-label="执行用途" :disabled="executing" />
        </div>
        <el-input v-model="execution.command" type="textarea" :rows="4" maxlength="16384" placeholder="输入 Linux 命令" aria-label="Linux 命令" :disabled="executing" class="command-input" />
        <div class="command-footer">
          <span>{{ selected?.fullAccess ? '完整权限：允许任意命令' : '查询模式：允许常见查询命令及管道' }}</span>
          <label>等待上限 <el-input-number v-model="execution.timeoutSeconds" :min="1" :max="30" size="small" :disabled="executing" /> 秒</label>
          <el-button type="primary" :loading="executing" @click="execute">执行命令</el-button>
        </div>
        <p class="mode-help">查询模式支持 ls、cat、head、tail、grep、df、du、free、ps、ss、journalctl 等查询命令及有限参数；不支持脚本、sudo、重定向、通配展开或复合语句。每次执行为独立会话。</p>
        <el-alert v-if="executionError" :title="executionError" type="error" :closable="false" show-icon />
        <div v-if="result" class="command-result">
          <div class="result-meta"><strong>{{ result.status }}</strong><span>退出码 {{ result.exitCode ?? '未知' }} · {{ result.durationMs }} ms</span></div>
          <p>{{ result.message }}</p>
          <pre aria-label="标准输出">{{ result.stdout || '（无标准输出）' }}</pre>
          <template v-if="result.stderr"><p>标准错误</p><pre class="stderr">{{ result.stderr }}</pre></template>
        </div>
      </div>
    </section>
    <p class="mode-help">查询模式属于应用层限制，服务器账号仍应按需授权。文件和日志可能包含敏感数据，输出可能发送给 AI；网关不会持久化命令输出。超时或断开连接不代表远程进程已停止。</p>

    <el-dialog v-model="editorOpen" :title="editingId ? '配置服务器' : '新增服务器'" width="720px" :close-on-click-modal="false" :close-on-press-escape="!saving && !testingConfiguration" :show-close="!saving && !testingConfiguration" @closed="clearSecrets">
      <el-form label-position="top" autocomplete="off" :disabled="saving || testingConfiguration">
        <el-form-item label="显示名称"><el-input v-model="form.name" maxlength="100" placeholder="例如：测试环境应用服务器" /></el-form-item>
        <el-form-item label="连接方式"><el-switch v-model="jumpEnabled" active-text="通过单级 SSH 跳板机" inactive-text="直接连接" /></el-form-item>
        <section v-for="entry in endpoints" :key="entry.label" class="endpoint-form">
          <h3>{{ entry.label }}</h3>
          <div class="endpoint-grid">
            <el-form-item label="主机地址"><el-input v-model="entry.value.host" placeholder="IP 或主机名" /></el-form-item>
            <el-form-item label="端口"><el-input-number v-model="entry.value.port" :min="1" :max="65535" /></el-form-item>
            <el-form-item label="SSH 账号"><el-input v-model="entry.value.username" autocomplete="off" /></el-form-item>
            <el-form-item label="认证方式"><el-select v-model="entry.value.authType"><el-option label="密码" value="PASSWORD" /><el-option label="私钥" value="PRIVATE_KEY" /></el-select></el-form-item>
          </div>
          <el-form-item v-if="entry.value.authType === 'PASSWORD'" :label="editingId ? '密码（留空保留原值）' : '密码'">
            <el-input v-model="entry.value.password" type="password" show-password autocomplete="new-password" />
          </el-form-item>
          <template v-else>
            <el-form-item :label="editingId ? '私钥（留空保留原值）' : '私钥'">
              <el-input v-model="entry.value.privateKey" type="textarea" :rows="4" placeholder="粘贴 PEM 或 OpenSSH 格式私钥" autocomplete="off" />
            </el-form-item>
            <el-form-item label="私钥口令（可选；替换私钥时一起填写）"><el-input v-model="entry.value.passphrase" type="password" show-password autocomplete="new-password" /></el-form-item>
          </template>
        </section>
        <p class="mode-help">填写账号及密码或私钥后，先测试连接，通过后才能确认保存。修改配置需要重新测试；保存时会再次校验连通性，并恢复查询模式。</p>
      </el-form>
      <el-alert v-if="connectionMessage" :title="connectionMessage" :type="connectionVerified ? 'success' : 'error'" :closable="false" show-icon />
      <template #footer>
        <el-button :disabled="saving || testingConfiguration" @click="editorOpen = false">取消</el-button>
        <el-button :loading="testingConfiguration" :disabled="saving" @click="testConfiguration">测试连接</el-button>
        <el-button type="primary" :loading="saving" :disabled="!connectionVerified || testingConfiguration" @click="save">确认保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.server-panel { margin-bottom: 24px; }
.server-row { display: flex; align-items: center; gap: 24px; padding: 22px; border-bottom: 1px solid var(--line); }
.server-row:last-child { border-bottom: 0; }
.server-info { flex: 1; min-width: 0; }
.server-info h2 { display: flex; align-items: center; gap: 12px; font-size: 16px; margin: 0 0 8px; }
.server-info code { color: var(--text-dim); font-size: 11px; }
.connection-message { color: var(--text-soft); font-size: 12px; overflow-wrap: anywhere; margin-bottom: 0; }
.access-control { display: flex; align-items: center; gap: 10px; white-space: nowrap; font-size: 13px; }
.server-actions { display: flex; flex-wrap: wrap; gap: 6px; }
.server-actions .el-button { margin-left: 0; }
.command-body { padding: 22px; }
.command-fields { display: grid; grid-template-columns: 260px 1fr; gap: 16px; margin-bottom: 16px; }
.command-input :deep(textarea), pre { font-family: 'JetBrains Mono', monospace; }
.command-footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 16px; font-size: 12px; color: var(--text-soft); flex-wrap: wrap; }
.mode-help { color: var(--text-dim); line-height: 1.8; font-size: 12px; }
.command-result { margin-top: 20px; border-top: 1px solid var(--line); padding-top: 18px; }
.result-meta { display: flex; align-items: center; justify-content: space-between; font-size: 12px; }
.command-result p { font-size: 12px; color: var(--text-soft); }
pre { background: var(--bg-deep); padding: 16px; border-radius: 6px; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 440px; overflow: auto; font-size: 12px; line-height: 1.7; }
.stderr { color: var(--el-color-danger); }
.endpoint-form { border-top: 1px solid var(--line); padding-top: 8px; margin-top: 12px; }
.endpoint-form h3 { font-size: 14px; margin-bottom: 16px; }
.endpoint-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
@media (max-width: 1000px) { .server-row { flex-wrap: wrap; } .server-info { flex-basis: 100%; } }
@media (max-width: 640px) { .command-fields, .endpoint-grid { grid-template-columns: 1fr; } }
</style>
