<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, normalizeList, errorMessage, sqlAllowlist, saveSqlAllowlist } from '../api/client'
import type { DataSourceSummary } from '../api/types'
import LoadState from './LoadState.vue'

const sources = ref<DataSourceSummary[]>([])
const sourceId = ref('')
const databaseType = ref('OCEANBASE_ORACLE')
const builtIns = ref<string[]>([])
const functions = ref<string[]>([])
const functionName = ref('')
const functionInputError = ref('')
const hints = ref<string[]>([])
const supportedHints = ref<string[]>([])
const search = ref('')
const loading = ref(true)
const saving = reactive({ functions: false, hints: false })
const refreshing = reactive({ functions: false, hints: false })
const sectionErrors = reactive({ functions: '', hints: '' })
const revisions = { functions: 0, hints: 0 }
const error = ref('')
const functionsDisabled = computed(() => saving.functions || refreshing.functions || !!sectionErrors.functions || !sourceId.value)
function addFunction() {
  if (functionsDisabled.value) return
  const name = functionName.value.trim().toUpperCase()
  functionInputError.value = ''
  if (!/^[A-Z][A-Z0-9_]{0,63}$/.test(name)) {
    functionInputError.value = '请输入函数名，以字母开头，最多 64 个字符，仅支持字母、数字和下划线，不需要括号'
    return
  }
  if (builtIns.value.includes(name) || functions.value.some(value => value.toUpperCase() === name)) {
    functionInputError.value = '该函数已在允许列表中，无需重复添加'
    return
  }
  if (functions.value.length >= 200) {
    functionInputError.value = '每个数据源最多添加 200 个函数'
    return
  }
  functions.value.push(name)
  functionName.value = ''
}
function removeFunction(name: string) {
  if (!functionsDisabled.value) functions.value = functions.value.filter(value => value !== name)
}
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
    const data = await sqlAllowlist(sourceId.value || undefined, databaseType.value)
    builtIns.value = data.builtInFunctions
    functions.value = data.customFunctions
    hints.value = data.hints
    supportedHints.value = data.supportedHints
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}
async function initialize() {
  loading.value = true
  error.value = ''
  try {
    sources.value = normalizeList(await api.dataSources()).items
    sourceId.value = sources.value[0]?.id || ''
    await load()
  } catch (cause) {
    error.value = errorMessage(cause)
    loading.value = false
  }
}
async function refreshSection(kind: 'functions' | 'hints') {
  if (kind === 'functions') {
    functionName.value = ''
    functionInputError.value = ''
  }
  const revision = ++revisions[kind]
  refreshing[kind] = true
  sectionErrors[kind] = ''
  try {
    const data = await sqlAllowlist(kind === 'functions' ? sourceId.value || undefined : undefined, databaseType.value)
    if (revision !== revisions[kind]) return
    if (kind === 'functions') functions.value = data.customFunctions
    else hints.value = data.hints
  } catch (cause) {
    if (revision === revisions[kind]) sectionErrors[kind] = errorMessage(cause)
  } finally {
    if (revision === revisions[kind]) refreshing[kind] = false
  }
}
async function save(kind: 'functions' | 'hints') {
  if (saving[kind] || refreshing[kind] || sectionErrors[kind]) return
  saving[kind] = true
  try {
    await saveSqlAllowlist(kind, kind === 'functions' ? sourceId.value : databaseType.value,
      kind === 'functions' ? functions.value : hints.value)
    ElMessage.success('白名单已保存并生效')
    await refreshSection(kind)
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    saving[kind] = false
  }
}
onMounted(initialize)
</script>

<template>
  <section class="panel allowlist-panel">
    <header class="panel-header">
      <div><p class="panel-title">SQL 白名单管理</p><span class="panel-code">保存后立即生效，重启后保留</span></div>
    </header>
    <LoadState :loading="loading" :error="error" @retry="initialize">
      <div class="allowlist-content">
        <section>
          <h3>默认允许函数 <span>{{ builtIns.length }} 个</span></h3>
          <p>这些名称来自后端实际执行规则，适用于所有数据源。保留了此前已允许的业务函数。</p>
          <el-input v-model="search" clearable placeholder="搜索函数名称，例如 STR_TO_DATE" aria-label="搜索允许的函数" />
          <div class="function-list">
            <el-tag v-for="name in filteredFunctions" :key="name" effect="plain">{{ name }}</el-tag>
            <span v-if="!filteredFunctions.length">没有匹配的函数</span>
          </div>
        </section>
        <div class="rule-columns">
          <section>
            <h3>数据源专用函数</h3>
            <p>输入函数名后点击“添加”或按回车，点击标签上的 × 可移除。添加、移除后点击“保存函数白名单”生效。</p>
            <label>生效数据源</label>
            <el-select v-model="sourceId" :disabled="saving.functions" placeholder="请选择数据源" @change="refreshSection('functions')">
              <el-option v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" />
            </el-select>
            <label>额外允许的函数</label>
            <div class="function-entry">
              <el-input v-model="functionName" :disabled="functionsDisabled" clearable
                placeholder="例如：CALCULATE_WORK_MINUTES" aria-label="函数名称"
                @input="functionInputError = ''" @keydown.enter.prevent="addFunction" />
              <el-button :disabled="functionsDisabled || !functionName.trim()" @click="addFunction">添加</el-button>
            </div>
            <p v-if="functionInputError" class="text-red" role="alert">{{ functionInputError }}</p>
            <div class="custom-functions" aria-label="已添加的额外允许函数">
              <el-tag v-for="name in functions" :key="name" :closable="!functionsDisabled"
                effect="plain" @close="removeFunction(name)">{{ name }}</el-tag>
              <span v-if="!functions.length">{{ refreshing.functions ? '正在读取函数列表…' : '尚未添加额外允许的函数' }}</span>
            </div>
            <p v-if="sectionErrors.functions" class="text-red" role="alert">{{ sectionErrors.functions }}
              <el-button text @click="refreshSection('functions')">重试</el-button>
            </p>
            <el-button type="primary" :loading="saving.functions || refreshing.functions"
              :disabled="!sourceId || !!sectionErrors.functions" @click="save('functions')">保存函数白名单</el-button>
          </section>
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
.rule-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 28px; border-top: 1px solid var(--line); margin-top: 12px; padding-top: 22px; }
.rule-columns section { min-width: 0; }
label { display: block; margin: 14px 0 8px; color: var(--text-soft); }
.el-select { width: 100%; }
.function-entry { display: flex; gap: 8px; align-items: center; }
.function-entry .el-input { flex: 1; min-width: 0; }
.function-entry .el-button { margin: 0; flex-shrink: 0; }
.custom-functions { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px 0; color: var(--text-dim); font-size: 14px; }
.custom-functions .el-tag { max-width: 100%; height: auto; min-height: 24px; white-space: normal; overflow-wrap: anywhere; }
.el-checkbox-group { display: flex; flex-direction: column; }
.el-button { margin-top: 18px; }
@media (max-width: 800px) { .rule-columns { grid-template-columns: 1fr; } }
</style>
