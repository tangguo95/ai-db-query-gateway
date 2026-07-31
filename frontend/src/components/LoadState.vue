<script setup lang="ts">
defineProps<{
  loading?: boolean
  error?: string
  empty?: boolean
  emptyTitle?: string
  emptyText?: string
}>()

defineEmits<{ retry: [] }>()
</script>

<template>
  <div v-if="loading" class="skeleton-lines" aria-label="正在加载">
    <span /><span /><span />
  </div>
  <div v-else-if="error" class="error-state">
    <div>
      <div class="empty-glyph"><span>!</span></div>
      <h3>遥测链路中断</h3>
      <p>{{ error }}</p>
      <el-button class="state-action" plain @click="$emit('retry')">重新连接</el-button>
    </div>
  </div>
  <div v-else-if="empty" class="empty-state">
    <div>
      <div class="empty-glyph"><span>Ø</span></div>
      <h3>{{ emptyTitle ?? '没有记录' }}</h3>
      <p>{{ emptyText ?? '当前筛选条件下暂无数据。' }}</p>
      <slot name="action" />
    </div>
  </div>
  <slot v-else />
</template>

<style scoped>
.state-action {
  margin-top: 16px;
}
</style>
