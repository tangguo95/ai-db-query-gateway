<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()

const handleUnauthorized = () => {
  auth.markUnauthenticated()
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
  }
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
    <RouterView v-else />
  </div>
</template>
