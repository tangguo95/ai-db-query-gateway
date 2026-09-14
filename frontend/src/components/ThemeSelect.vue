<script setup lang="ts">
import { useTheme, type ThemePreference } from '../theme'
const { preference, setTheme } = useTheme()
function select(value: string) {
  if (['light', 'dark', 'system'].includes(value)) setTheme(value as ThemePreference)
}
</script>

<template>
  <el-select :model-value="preference" aria-label="主题设置" class="theme-select" @change="select">
    <el-option label="浅色" value="light" />
    <el-option label="深色" value="dark" />
    <el-option label="跟随系统" value="system" />
  </el-select>
</template>

<style scoped>
.theme-select { width: 128px; flex-shrink: 0; }
</style>
