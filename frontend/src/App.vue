<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function retryBootstrap() {
  auth.ready = false
  await auth.bootstrap()
}

const handleUnauthorized = () => {
  auth.markUnauthenticated()
  // 本机免登录不跳转登录页；保持当前页面，由具体请求展示认证或服务异常。
}

onMounted(async () => {
  window.addEventListener('gateway:unauthorized', handleUnauthorized)
  await auth.bootstrap()
  await router.isReady()
})

onBeforeUnmount(() => window.removeEventListener('gateway:unauthorized', handleUnauthorized))
</script>

<template>
  <div class="app-root">
    <div v-if="!auth.ready" class="boot-screen" aria-live="polite">
      <div class="boot-mark">
        <span class="boot-pulse" />
        <span>查询网关</span>
      </div>
      <div class="boot-track"><span /></div>
      <p>正在校验本地安全边界</p>
    </div>
    <div v-else-if="auth.error" class="boot-screen" role="alert">
      <p>{{ auth.error }}</p>
      <button type="button" @click="retryBootstrap">重新连接网关</button>
    </div>
    <RouterView v-else />
  </div>
</template>
