<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportDataSourceBackup, importDataSourceBackup, errorMessage } from '../api/client'

const props = defineProps<{ mode: 'export' | 'import'; ids: string[] }>()
const emit = defineEmits<{ close: []; imported: [] }>()
const password = ref('')
const confirmPassword = ref('')
const file = ref<File | null>(null)
const busy = ref(false)
const results = ref<{ name: string; status: string; message: string }[]>([])
const error = ref('')

function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

async function submit() {
  error.value = ''
  if (password.value.length < 12 || password.value.length > 256) {
    error.value = '备份密码需为 12–256 个字符'
    return
  }
  if (props.mode === 'export' && password.value !== confirmPassword.value) {
    error.value = '两次输入的备份密码不一致'
    return
  }
  if (props.mode === 'import' && (!file.value || file.value.size > 5 * 1024 * 1024)) {
    error.value = '请选择不超过 5 MiB 的网关备份文件'
    return
  }
  busy.value = true
  try {
    if (props.mode === 'export') {
      const blob = await exportDataSourceBackup(password.value, props.ids)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = '数据源备份-' + new Date().toISOString().slice(0, 10) + '.gwbackup'
      document.body.appendChild(link)
      link.click()
      link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
      ElMessage.success('加密备份已导出，请妥善保存备份密码')
      emit('close')
    } else {
      const bytes = new Uint8Array(await file.value!.arrayBuffer())
      let binary = ''
      for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192))
      const response = await importDataSourceBackup(password.value, btoa(binary))
      results.value = response.items
      emit('imported')
    }
    password.value = ''
    confirmPassword.value = ''
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="mode === 'export' ? '导出加密数据源备份' : '导入数据源备份'"
    width="560px" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy"
    @close="emit('close')">
    <template v-if="!results.length">
      <p class="backup-note" v-if="mode === 'export'">
        将备份 {{ ids.length }} 个数据源，包含连接信息和超时配置。文件使用你设置的独立密码加密，换设备后凭此密码恢复。忘记密码将无法恢复备份。
      </p>
      <p class="backup-note" v-else>
        选择网关导出的 .gwbackup 文件，并输入导出时设置的备份密码。同名数据源将跳过；导入后可批量连接复检。备份不包含访问令牌或审计历史。
      </p>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item v-if="mode === 'import'" label="备份文件">
          <input type="file" accept=".gwbackup" :disabled="busy" aria-label="备份文件" @change="chooseFile" />
        </el-form-item>
        <el-form-item label="备份密码">
          <el-input v-model="password" type="password" show-password autocomplete="new-password"
            :maxlength="256" :disabled="busy" placeholder="请输入至少 12 个字符的备份密码" />
        </el-form-item>
        <el-form-item v-if="mode === 'export'" label="确认备份密码">
          <el-input v-model="confirmPassword" type="password" show-password autocomplete="new-password"
            :maxlength="256" :disabled="busy" placeholder="再次输入备份密码" />
        </el-form-item>
      </el-form>
    </template>
    <div v-else class="backup-results">
      <p class="backup-note">导入 {{ results.filter(i => i.status === 'IMPORTED').length }} 个，
        跳过 {{ results.filter(i => i.status === 'SKIPPED').length }} 个，
        未完成 {{ results.filter(i => i.status === 'FAILED').length }} 个。</p>
      <div v-for="item in results" :key="item.name" class="backup-result">
        <strong>{{ item.name }}</strong><span>{{ item.message }}</span>
      </div>
    </div>
    <p v-if="error" class="text-red" role="alert">{{ error }}</p>
    <template #footer>
      <el-button :disabled="busy" @click="emit('close')">{{ results.length ? '完成' : '取消' }}</el-button>
      <el-button v-if="!results.length" type="primary" :loading="busy" @click="submit">
        {{ mode === 'export' ? '加密并导出' : '解密并导入' }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.backup-note { color: var(--text-soft); line-height: 1.8; margin: 0 0 20px; }
.backup-results { max-height: 420px; overflow: auto; }
.backup-result { display: flex; flex-direction: column; gap: 6px; padding: 12px 0; border-bottom: 1px solid var(--line); }
.backup-result strong { color: var(--text); }
.backup-result span { color: var(--text-dim); }
input[type=file] { color: var(--text); max-width: 100%; }
</style>
