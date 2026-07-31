<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import AuthFrame from '../layouts/AuthFrame.vue'
import { errorMessage } from '../api/client'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const form = reactive({ password: '' })

async function submit() {
  if (!form.password) {
    ElMessage.warning('请输入管理员密码')
    return
  }

  loading.value = true
  try {
    await auth.login(form.password)
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(target)
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    form.password = ''
    loading.value = false
  }
}
</script>

<template>
  <AuthFrame
    step="登录 / 02"
    title="操作员身份校验"
    subtitle="登录仅建立本机网页会话。连续失败将触发速率限制，查询和管理操作会进入审计轨迹。"
  >
    <el-form label-position="top" @submit.prevent="submit">
      <el-form-item label="管理员密码">
        <el-input
          v-model="form.password"
          type="password"
          autocomplete="current-password"
          placeholder="输入本地管理员密码"
          autofocus
          @keyup.enter="submit"
        />
      </el-form-item>
      <el-button type="primary" class="submit-button" :loading="loading" @click="submit">
        进入控制台
      </el-button>
    </el-form>
    <div class="session-spec">
      <span>空闲超时 <strong>30 分钟</strong></span>
      <span>最长会话 <strong>8 小时</strong></span>
    </div>
  </AuthFrame>
</template>

<style scoped>
.submit-button {
  width: 100%;
  height: 42px;
  margin-top: 4px;
}

.session-spec {
  display: flex;
  justify-content: space-between;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--line);
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.session-spec strong {
  color: var(--text-soft);
  font-weight: 600;
}
</style>
