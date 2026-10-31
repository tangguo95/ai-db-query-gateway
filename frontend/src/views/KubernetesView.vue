<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from '../api/client'
import type { KubernetesCluster, KubernetesResult, KubernetesSave } from '../api/types'
import LoadState from '../components/LoadState.vue'

const loading = ref(true)
const error = ref('')
const clusters = ref<KubernetesCluster[]>([])
const resources = ref<string[]>([])
const editorOpen = ref(false)
const editing = ref<KubernetesCluster | null>(null)
const busy = ref(false)
const verified = ref(false)
const connectionMessage = ref('')
const form = reactive({ name: '', kubeconfig: '', context: '', namespaces: 'default', allowContainerQuery: false })
const action = ref<'resources' | 'logs' | 'container'>('resources')
const querying = ref(false)
const result = ref<KubernetesResult | null>(null)
const queryError = ref('')
const query = reactive({ clusterId: '', namespace: 'default', resource: 'pods', name: '', labelSelector: '',
  pod: '', container: '', purpose: '', tailLines: 500, sinceSeconds: 3600, previous: false, command: '["tail", "-n", "100", "/var/log/app.log"]' })
const selected = computed(() => clusters.value.find(c => c.id === query.clusterId))
watch(form, () => { verified.value = false; connectionMessage.value = '' }, { deep: true, flush: 'sync' })
watch(() => query.clusterId, () => { result.value = null; queryError.value = ''; if (selected.value?.namespaces[0] !== '*') query.namespace = selected.value?.namespaces[0] ?? 'default' })

async function load() {
  loading.value = true; error.value = ''
  try { [clusters.value, resources.value] = await Promise.all([api.kubernetesClusters(), api.kubernetesResourceTypes()]) }
  catch (cause) { error.value = errorMessage(cause) }
  finally { loading.value = false }
}
function edit(cluster?: KubernetesCluster) {
  editing.value = cluster ?? null
  Object.assign(form, { name: cluster?.name ?? '', kubeconfig: '', context: cluster?.context ?? '',
    namespaces: cluster?.namespaces.join(',') ?? 'default', allowContainerQuery: cluster?.allowContainerQuery ?? false })
  verified.value = false; connectionMessage.value = ''; editorOpen.value = true
}
function payload(): KubernetesSave {
  return { ...form, name: form.name.trim(), namespaces: [...new Set(form.namespaces.split(',').map(n => n.trim()).filter(Boolean))] }
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]; input.value = ''
  if (!file) return
  if (file.size > 32768) { ElMessage.warning('kubeconfig 不能超过 32 KiB'); return }
  form.kubeconfig = await file.text()
}
async function test() {
  if (!form.name.trim() || (!editing.value && !form.kubeconfig.trim()) || !payload().namespaces.length) {
    ElMessage.warning('请填写名称、kubeconfig 和允许查询的命名空间'); return
  }
  busy.value = true; verified.value = false; connectionMessage.value = ''
  try {
    await api.testKubernetes(editing.value?.id ?? null, payload())
    verified.value = true; connectionMessage.value = '连接测试通过。查询时仍受集群 RBAC 和命名空间范围限制。'
  } catch (cause) { connectionMessage.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function save() {
  if (!verified.value) return
  if (form.allowContainerQuery && !editing.value?.allowContainerQuery) {
    try {
      await ElMessageBox.confirm('容器查询使用 exec 创建远程进程，需要集群 exec 权限，可能被平台审计或告警。网关只放行受限查看命令，不能保证容器内程序绝对无副作用。确认开启？', '开启容器内查询', { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' })
    } catch { return }
  }
  busy.value = true
  try { await api.saveKubernetes(editing.value?.id ?? null, payload()); editorOpen.value = false; form.kubeconfig = ''; await load(); ElMessage.success('集群配置已保存，请在访问令牌中授权') }
  catch (cause) { verified.value = false; connectionMessage.value = errorMessage(cause) }
  finally { busy.value = false }
}
async function remove(cluster: KubernetesCluster) {
  try { await ElMessageBox.confirm(`删除“${cluster.name}”的本机配置、kubeconfig 和对应令牌授权；不会删除集群内资源。`, '删除集群配置', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }) }
  catch { return }
  try { await api.deleteKubernetes(cluster.id); if (query.clusterId === cluster.id) query.clusterId = ''; await load() }
  catch (cause) { ElMessage.error(errorMessage(cause)) }
}
async function execute(continueToken?: string) {
  if (!query.clusterId || !query.purpose.trim()) { ElMessage.warning('请选择集群并填写查询用途'); return }
  querying.value = true; queryError.value = ''
  try {
    const body: Record<string, unknown> = { clusterId: query.clusterId, purpose: query.purpose, namespace: query.namespace }
    if (action.value === 'resources') Object.assign(body, { resource: query.resource, name: query.name || undefined,
      labelSelector: query.labelSelector || undefined, continueToken, limit: 50 })
    else if (action.value === 'logs') Object.assign(body, { pod: query.pod, container: query.container, tailLines: query.tailLines, sinceSeconds: query.sinceSeconds, previous: query.previous })
    else {
      const command: unknown = JSON.parse(query.command)
      if (!Array.isArray(command) || command.some(v => typeof v !== 'string')) throw new Error('命令须为 JSON 字符串数组')
      Object.assign(body, { pod: query.pod, container: query.container, command })
    }
    result.value = await api.queryKubernetes(action.value, body)
  } catch (cause) { result.value = null; queryError.value = errorMessage(cause) }
  finally { querying.value = false }
}
watch(action, () => { result.value = null; queryError.value = '' })
watch(query, () => { result.value = null }, { deep: true })
onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div><p class="eyebrow">KUBECONFIG / 只读访问</p><h1 class="page-title">Kubernetes</h1><p class="page-subtitle">查询集群资源 YAML 与 Pod 日志，通过现有 MCP 授权给 AI。</p></div>
      <el-button type="primary" @click="edit()">新增集群</el-button>
    </header>
    <section class="panel cluster-panel">
      <header class="panel-header"><div><p class="panel-title">集群连接</p><span class="panel-code">凭据本机保存 · 无写操作工具</span></div><el-button text @click="load">刷新</el-button></header>
      <LoadState :loading="loading" :error="error" :empty="!clusters.length" empty-title="还没有集群连接" empty-description="导入 kubeconfig 并设置查询范围，测试通过后保存。" @retry="load">
        <article v-for="cluster in clusters" :key="cluster.id" class="cluster-row">
          <div><h2>{{ cluster.name }} <el-tag size="small" :type="cluster.enabled ? 'success' : 'info'">{{ cluster.enabled ? '已启用' : '未启用' }}</el-tag></h2>
            <p>命名空间：{{ cluster.namespaces.join('、') }} · 容器内查询{{ cluster.allowContainerQuery ? '已开启' : '未开启' }}</p></div>
          <div><el-button @click="edit(cluster)">配置</el-button><el-button type="danger" plain @click="remove(cluster)">删除</el-button></div>
        </article>
      </LoadState>
    </section>
    <section class="panel">
      <header class="panel-header"><div><p class="panel-title">查询验证</p><span class="panel-code">显式查询 · 不轮询、不跟随日志、不自动重试</span></div></header>
      <el-form class="query-form" label-position="top" :disabled="querying">
        <div class="query-grid">
          <el-form-item label="集群"><el-select v-model="query.clusterId" placeholder="选择集群"><el-option v-for="cluster in clusters.filter(c => c.enabled)" :key="cluster.id" :label="cluster.name" :value="cluster.id" /></el-select></el-form-item>
          <el-form-item label="查询内容"><el-select v-model="action"><el-option label="资源 YAML" value="resources" /><el-option label="Pod 标准输出日志" value="logs" /><el-option label="容器内受限查询" value="container" :disabled="!selected?.allowContainerQuery" /></el-select></el-form-item>
          <el-form-item label="命名空间"><el-input v-model="query.namespace" placeholder="例如 default" /></el-form-item>
          <el-form-item label="查询用途"><el-input v-model="query.purpose" maxlength="500" placeholder="例如排查应用异常" /></el-form-item>
        </div>
        <div v-if="action === 'resources'" class="query-grid">
          <el-form-item label="资源类型"><el-select v-model="query.resource" filterable><el-option v-for="resource in resources" :key="resource" :label="resource" :value="resource" /></el-select></el-form-item>
          <el-form-item label="名称（留空列出资源）"><el-input v-model="query.name" /></el-form-item>
          <el-form-item label="标签筛选（可选）"><el-input v-model="query.labelSelector" placeholder="app=orders" /></el-form-item>
        </div>
        <div v-else class="query-grid">
          <el-form-item label="Pod 名称"><el-input v-model="query.pod" /></el-form-item>
          <el-form-item label="容器名称"><el-input v-model="query.container" /></el-form-item>
          <template v-if="action === 'logs'">
            <el-form-item label="最多行数"><el-input-number v-model="query.tailLines" :min="1" :max="2000" /></el-form-item>
            <el-form-item label="最近多少秒"><el-input-number v-model="query.sinceSeconds" :min="1" :max="86400" /></el-form-item>
            <el-checkbox v-model="query.previous">上一个容器实例的日志</el-checkbox>
          </template>
        </div>
        <el-form-item v-if="action === 'container'" label="查询命令参数数组"><el-input v-model="query.command" type="textarea" :rows="3" /></el-form-item>
        <p class="mode-help">资源和日志可能包含敏感信息。容器查询仅支持 ls、cat、head、tail、ps、df、id、uname、pwd 的有限参数；无交互终端，不能执行 Shell、脚本、管道或写命令，镜像须有对应工具。</p>
        <el-button type="primary" :loading="querying" @click="execute()">执行查询</el-button>
        <el-button v-if="action === 'resources' && result?.continueToken" @click="execute(result.continueToken)">下一页</el-button>
      </el-form>
      <div class="result-body">
        <el-alert v-if="queryError" :title="queryError" type="error" :closable="false" />
        <template v-if="result"><p>{{ result.format.toUpperCase() }} · {{ result.durationMs }} ms</p><pre>{{ result.content || '（无输出）' }}</pre></template>
      </div>
    </section>
    <el-dialog v-model="editorOpen" :title="editing ? '配置集群' : '新增集群'" width="720px" :close-on-click-modal="false" :show-close="!busy" :close-on-press-escape="!busy" @closed="form.kubeconfig = ''">
      <el-form label-position="top" :disabled="busy">
        <el-form-item label="显示名称"><el-input v-model="form.name" maxlength="100" /></el-form-item>
        <el-form-item label="kubeconfig 文件"><input type="file" :disabled="busy" @change="upload" /></el-form-item>
        <el-form-item :label="editing ? 'kubeconfig（留空保留原配置）' : 'kubeconfig 内容'"><el-input v-model="form.kubeconfig" type="textarea" :rows="7" maxlength="32768" placeholder="粘贴 kubeconfig，或使用上方文件选择器" /></el-form-item>
        <div class="query-grid">
          <el-form-item label="Context（留空使用 current-context）"><el-input v-model="form.context" maxlength="253" /></el-form-item>
          <el-form-item label="允许的命名空间"><el-input v-model="form.namespaces" placeholder="default,app；全部填写 *" /></el-form-item>
        </div>
        <el-checkbox v-model="form.allowContainerQuery">允许容器内受限查询（默认关闭）</el-checkbox>
        <p class="mode-help">支持内嵌 Token 或客户端证书；不执行认证插件，不读取本机凭据文件。证书验证沿用 kubeconfig。容器查询会创建 exec 进程，平台可能审计或告警；查询资源 YAML 和标准输出日志无需开启此项。选择其他 context 时请重新上传包含该 context 的文件。</p>
      </el-form>
      <el-alert v-if="connectionMessage" :title="connectionMessage" :type="verified ? 'success' : 'error'" :closable="false" />
      <template #footer><el-button :disabled="busy" @click="editorOpen = false">取消</el-button><el-button :loading="busy" @click="test">测试连接</el-button><el-button type="primary" :loading="busy" :disabled="!verified" @click="save">确认保存</el-button></template>
    </el-dialog>
  </div>
</template>

<style scoped>
.cluster-panel { margin-bottom: 24px; }
.cluster-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid var(--line); }
.cluster-row h2 { font-size: 16px; margin: 0 0 10px; }
.cluster-row p, .mode-help, .result-body p { font-size: 12px; color: var(--text-dim); line-height: 1.8; }
.query-form, .result-body { padding: 20px; }
.query-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.query-grid .el-select { width: 100%; }
pre { max-height: 600px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; padding: 18px; background: var(--bg-deep); border-radius: 6px; font: 12px/1.7 'JetBrains Mono', monospace; }
@media (max-width: 680px) { .query-grid { grid-template-columns: 1fr; } .cluster-row { flex-wrap: wrap; } }
</style>
