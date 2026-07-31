<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthFrame from '../layouts/AuthFrame.vue'
import { errorMessage } from '../api/client'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const showPassword = ref(false)
const form = reactive({
  bootstrapToken: '',
  password: '',
  confirmPassword: ''
})

async function submit() {
  if (!form.bootstrapToken.trim()) {
    ElMessage.warning('请输入 bootstrap-token 文件中的一次性初始化令牌')
    return
  }
  if (form.password.length < 12) {
    ElMessage.warning('管理员密码至少需要 12 个字符')
    return
  }
  if (form.password !== form.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  loading.value = true
  try {
    await auth.setup(form.bootstrapToken.trim(), form.password)
    ElMessage.success('安全边界初始化完成')
    await router.replace(auth.authenticated ? '/dashboard' : '/login')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthFrame
    step="初始化 / 01"
    title="建立本地控制权"
    subtitle="读取本机运行目录中的 bootstrap-token 一次性文件，设置唯一的本地管理员密码。"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="一次性初始化令牌">
        <el-input
          v-model="form.bootstrapToken"
          autocomplete="off"
          placeholder="BOOTSTRAP-••••••••"
          class="mono-input"
        />
      </el-form-item>
      <el-form-item label="管理员密码">
        <el-input
          v-model="form.password"
          :type="showPassword ? 'text' : 'password'"
          autocomplete="new-password"
          placeholder="至少 12 个字符"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-form-item label="确认管理员密码">
        <el-input
          v-model="form.confirmPassword"
          :type="showPassword ? 'text' : 'password'"
          autocomplete="new-password"
          placeholder="再次输入密码"
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-checkbox v-model="showPassword" class="show-password">显示密码</el-checkbox>
      <el-button type="primary" class="submit-button" :loading="loading" @click="submit">
        初始化安全边界
      </el-button>
    </el-form>
    <p class="form-note">
      默认路径：~/Library/Application Support/AI DB Query Gateway/bootstrap-token。文件权限为 0600，初始化成功后立即删除；管理员密码只保存 Argon2id 摘要。
    </p>
  </AuthFrame>
</template>

<style scoped>
.mono-input {
  font-family: var(--font-mono);
}

.show-password {
  margin: 0 0 20px;
}

.submit-button {
  width: 100%;
  height: 42px;
}

.form-note {
  margin: 18px 0 0;
  color: var(--text-dim);
  font:  14px/1.6 var(--font-mono);
}
</style>
