<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { errorMessage, sqlAllowlist, saveSqlAllowlist } from '../api/client'
import LoadState from './LoadState.vue'

const databaseType = ref('OCEANBASE_ORACLE')
const builtIns = ref<string[]>([])
const hints = ref<string[]>([])
const supportedHints = ref<string[]>([])
const search = ref('')
const loading = ref(true)
const saving = reactive({ hints: false })
const refreshing = reactive({ hints: false })
const sectionErrors = reactive({ hints: '' })
const revisions = { hints: 0 }
const error = ref('')
const filteredFunctions = computed(() => builtIns.value.filter(name => name.toLowerCase().includes(search.value.trim().toLowerCase())))
const hintDescriptions: Record<string, string> = {
  MATERIALIZE: '请求物化中间结果', INLINE: '请求内联子查询',
  MERGE: '请求合并查询块', NO_MERGE: '请求不合并查询块'
}
const types = [
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'DRDS_MYSQL', label: 'DRDS · MySQL 兼容' },
  { value: 'OCEANBASE_MYSQL', label: 'OceanBase · MySQL' },
  { value: 'OCEANBASE_ORACLE', label: 'OceanBase · Oracle' }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await sqlAllowlist(undefined, databaseType.value)
    builtIns.value = data.builtInFunctions
    hints.value = data.hints
    supportedHints.value = data.supportedHints
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
async function refreshSection(kind: 'hints') {
  const revision = ++revisions[kind]
  refreshing[kind] = true
  sectionErrors[kind] = ''
  try {
    const data = await sqlAllowlist(undefined, databaseType.value)
    if (revision !== revisions[kind]) return
    hints.value = data.hints
  } catch (cause) {
    if (revision === revisions[kind]) sectionErrors[kind] = errorMessage(cause)
  } finally {
    if (revision === revisions[kind]) refreshing[kind] = false
  }
}
async function save(kind: 'hints') {
  if (saving[kind] || refreshing[kind] || sectionErrors[kind]) return
  saving[kind] = true
  try {
    await saveSqlAllowlist(kind, databaseType.value, hints.value)
    ElMessage.success('白名单已保存并生效')
    await refreshSection(kind)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    saving[kind] = false
  }
}
onMounted(load)
</script>

<template>
  <section class="panel allowlist-panel">
    <header class="panel-header">
      <div><p class="panel-title">SQL 函数与 Hint</p><span class="panel-code">全部查询函数已放开；Hint 配置保存后立即生效</span></div>
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
            <p>按数据库类型允许无参数 Hint，例如 /*+ MATERIALIZE */。数据库是否采用提示取决于其版本和执行计划。</p>
            <label>生效数据库类型</label>
            <el-select v-model="databaseType" :disabled="saving.hints" @change="refreshSection('hints')">
              <el-option v-for="type in types" :key="type.value" :label="type.label" :value="type.value" />
            </el-select>
            <label>允许的 Hint</label>
            <el-checkbox-group v-model="hints" :disabled="saving.hints || refreshing.hints || !!sectionErrors.hints">
              <el-checkbox v-for="name in supportedHints" :key="name" :value="name">
                {{ name }} · {{ hintDescriptions[name] }}
              </el-checkbox>
            </el-checkbox-group>
            <p v-if="sectionErrors.hints" class="text-red" role="alert">{{ sectionErrors.hints }}
              <el-button text @click="refreshSection('hints')">重试</el-button>
            </p>
            <el-button type="primary" :loading="saving.hints || refreshing.hints"
              :disabled="!!sectionErrors.hints" @click="save('hints')">保存 Hint 白名单</el-button>
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
label { display: block; margin: 14px 0 8px; color: var(--text-soft); }
.el-select { width: 100%; }
.el-checkbox-group { display: flex; flex-direction: column; }
.el-button { margin-top: 18px; }
</style>
