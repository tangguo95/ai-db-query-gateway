<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { errorMessage, sqlAllowlist } from '../api/client'
import LoadState from './LoadState.vue'

const builtIns = ref<string[]>([])
const search = ref('')
const loading = ref(true)
const error = ref('')
const filteredFunctions = computed(() => builtIns.value.filter(name => name.toLowerCase().includes(search.value.trim().toLowerCase())))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await sqlAllowlist(undefined, 'OCEANBASE_ORACLE')
    builtIns.value = data.builtInFunctions
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
onMounted(load)
</script>

<template>
  <section class="panel allowlist-panel">
    <header class="panel-header">
      <div><p class="panel-title">SQL 函数与 Hint</p><span class="panel-code">全部查询函数和优化器 Hint 已放开，无需配置白名单</span></div>
    </header>
    <LoadState :loading="loading" :error="error" @retry="load">
      <div class="allowlist-content">
        <section>
          <h3>常用函数参考 <span>{{ builtIns.length }} 个示例</span></h3>
          <p>所有数据源均可在查询中使用内置函数、自定义函数及带 Schema 或包名的函数，无需配置白名单。下列名称仅供参考。</p>
          <p>函数能否执行及其行为由目标数据库和账号权限决定，网关不再按函数名判断副作用；单条查询和 SQL 结构检查继续保留。</p>
          <el-input v-model="search" clearable placeholder="搜索函数名称，例如 STR_TO_DATE" aria-label="搜索常用函数参考" />
          <div class="function-list">
            <el-tag v-for="name in filteredFunctions" :key="name" effect="plain">{{ name }}</el-tag>
            <span v-if="!filteredFunctions.length">没有匹配的函数</span>
          </div>
        </section>
        <div class="rule-columns">
          <section>
            <h3>优化器 Hint</h3>
            <p>查询中的所有 /*+ ... */ 优化器 Hint 均不再按名称或参数白名单拦截，无需按数据库类型开启。</p>
            <p>例如 USE_NL、LEADING、INDEX、MATERIALIZE、PARALLEL 及其组合、嵌套参数均可直接使用。提示内容原样传递，是否支持及如何生效由目标数据库决定。</p>
          </section>
        </div>
      </div>
    </LoadState>
  </section>
</template>

<style scoped>
.allowlist-panel { margin-bottom: 16px; border-radius: var(--radius); }
.allowlist-content { padding: 22px; }
h3 { margin: 0 0 10px; color: var(--text); font-size: 16px; }
h3 span { color: var(--text-dim); font-size: 13px; font-weight: 400; margin-left: 8px; }
p { color: var(--text-soft); font-size: 14px; line-height: 1.7; margin: 0 0 14px; }
.function-list { display: flex; flex-wrap: wrap; gap: 8px; max-height: 190px; overflow-y: auto; padding: 14px 0; }
.rule-columns { border-top: 1px solid var(--line); margin-top: 12px; padding-top: 22px; }
.rule-columns section { min-width: 0; }
</style>
