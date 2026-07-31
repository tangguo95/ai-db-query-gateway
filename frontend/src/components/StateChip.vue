<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value?: string | boolean | null
  fallback?: string
}>()

const normalized = computed(() => {
  if (props.value === true) return 'ACTIVE'
  if (props.value === false) return 'BLOCKED'
  return String(props.value ?? 'UNKNOWN').toUpperCase()
})

const labels: Record<string, string> = {
  STRICT: '严格只读',
  COMPATIBILITY: '网关只读',
  BLOCKED: '已阻断',
  UNKNOWN: '待检测',
  REQUESTED: '已申请',
  EXECUTED: '已执行',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  REJECTED: '已拒绝',
  EXPIRED: '已过期',
  FAILED: '执行失败',
  CANCELLED: '已取消',
  ACTIVE: '有效',
  REVOKED: '已吊销',
  SUCCESS: '成功',
  WARNING: '警告'
}
</script>

<template>
  <span class="state-chip" :class="normalized.toLowerCase()">
    {{ labels[normalized] ?? fallback ?? normalized }}
  </span>
</template>
