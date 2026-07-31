<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, errorMessage, normalizeList } from '../api/client'
import type { AuditRecord } from '../api/types'
import LoadState from '../components/LoadState.vue'
import StateChip from '../components/StateChip.vue'

const loading = ref(true)
const error = ref('')
const records = ref<AuditRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(25)
const expandedId = ref<string | number | null>(null)
const filters = reactive({
  eventType: '',
  status: '',
  queryId: ''
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = normalizeList(await api.audits({
      page: page.value - 1,
      size: pageSize.value,
      eventType: filters.eventType || undefined,
      status: filters.status || undefined,
      queryId: filters.queryId.trim() || undefined
    }))
    records.value = response.items
    total.value = response.total
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function applyFilters() {
  page.value = 1
  void load()
}

function resetFilters() {
  filters.eventType = ''
  filters.status = ''
  filters.queryId = ''
  page.value = 1
  void load()
}

function timestamp(record: AuditRecord): string {
  const value = record.timestamp || record.createdAt
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

function action(record: AuditRecord): string {
  return record.eventType || record.action || 'UNKNOWN_EVENT'
}

function actor(record: AuditRecord): string {
  return record.actor || record.subject || 'SYSTEM'
}

function toggle(record: AuditRecord) {
  expandedId.value = expandedId.value === record.id ? null : record.id
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">审计记录 / 防篡改轨迹</p>
        <h1 class="page-title">审计记录带</h1>
        <p class="page-subtitle">这里只记录操作元数据和去字面量指纹，不记录查询结果。审计无删除入口，也不会自动过期。</p>
      </div>
      <div class="retention-label"><span>∞</span> 永久保留</div>
    </header>

    <section class="panel filter-panel">
      <div class="filters">
        <div>
          <label>事件类型</label>
          <el-select v-model="filters.eventType" clearable placeholder="全部事件">
            <el-option label="查询事件" value="QUERY" />
            <el-option label="数据源操作" value="DATASOURCE" />
            <el-option label="访问令牌" value="TOKEN" />
            <el-option label="登录认证" value="LOGIN" />
            <el-option label="HTTP 请求" value="HTTP" />
          </el-select>
        </div>
        <div>
          <label>状态</label>
          <el-select v-model="filters.status" clearable placeholder="全部状态">
            <el-option label="成功" value="SUCCESS" />
            <el-option label="已执行" value="EXECUTED" />
            <el-option label="已拒绝" value="REJECTED" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </div>
        <div class="query-id-filter">
          <label>查询 ID</label>
          <el-input
            v-model="filters.queryId"
            clearable
            maxlength="36"
            placeholder="按 Query ID 精确定位"
            spellcheck="false"
            @keyup.enter="applyFilters"
          />
        </div>
        <el-button type="primary" @click="applyFilters">筛选轨迹</el-button>
        <el-button plain @click="resetFilters">清除</el-button>
        <span class="no-export">不可导出 / 不含查询结果</span>
      </div>
    </section>

    <section class="panel log-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">不可变事件序列</p>
          <span class="panel-code">链式 HMAC / {{ total }} 个事件</span>
        </div>
        <span class="chain-indicator"><i class="status-dot" />启动及每日校验</span>
      </header>

      <LoadState
        :loading="loading"
        :error="error"
        :empty="!records.length"
        empty-title="没有匹配的审计事件"
        empty-text="调整筛选条件，或等待下一次网关操作写入记录。"
        @retry="load"
      >
        <div class="log-table" role="table" aria-label="审计事件">
          <div class="log-head" role="row">
            <span>序号</span>
            <span>时间</span>
            <span>事件</span>
            <span>操作主体</span>
            <span>数据源</span>
            <span>状态</span>
            <span />
          </div>
          <template v-for="(record, index) in records" :key="record.id">
            <button class="log-row" type="button" role="row" @click="toggle(record)">
              <span class="sequence">{{ String((page - 1) * pageSize + index + 1).padStart(5, '0') }}</span>
              <time>{{ timestamp(record) }}</time>
              <strong>{{ action(record) }}</strong>
              <span>{{ actor(record) }}</span>
              <span>{{ record.dataSourceName || record.dataSourceId || '—' }}</span>
              <span><StateChip :value="record.status || 'SUCCESS'" /></span>
              <span class="expand-arrow">{{ expandedId === record.id ? '−' : '+' }}</span>
            </button>
            <div v-if="expandedId === record.id" class="log-detail">
              <dl>
                <div>
                  <dt>事件 ID</dt>
                  <dd>{{ record.id }}</dd>
                </div>
                <div>
                  <dt>查询用途</dt>
                  <dd>{{ record.purpose || '—' }}</dd>
                </div>
                <div>
                  <dt>查询 ID</dt>
                  <dd class="mono">{{ record.queryId || '—' }}</dd>
                </div>
                <div>
                  <dt>SQL 指纹</dt>
                  <dd class="mono">{{ record.sqlFingerprint || '—' }}</dd>
                </div>
                <div>
                  <dt>执行指标</dt>
                  <dd>{{ record.durationMs ?? '—' }} 毫秒 · {{ record.rowCount ?? '—' }} 行 · {{ record.bytes ?? '—' }} 字节</dd>
                </div>
                <div>
                  <dt>错误代码</dt>
                  <dd :class="{ 'text-red': record.errorCode }">{{ record.errorCode || '无' }}</dd>
                </div>
                <div>
                  <dt>链路校验</dt>
                  <dd :class="record.chainValid === false ? 'text-red' : 'text-green'">
                    {{ record.chainValid === false ? '校验失败' : '已校验 / 未发现异常' }}
                  </dd>
                </div>
              </dl>
            </div>
          </template>
        </div>
      </LoadState>

      <footer v-if="total > pageSize" class="pagination">
        <span>第 {{ String(page).padStart(2, '0') }} 页</span>
        <el-pagination
          v-model:current-page="page"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          @current-change="load"
        />
      </footer>
    </section>
  </div>
</template>

<style scoped>
.retention-label {
  padding: 9px 12px;
  border: 1px solid var(--line-strong);
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.retention-label span {
  margin-right: 8px;
  color: var(--green);
  font-size: 17px;
  vertical-align: middle;
}

.filter-panel {
  margin-bottom: 12px;
}

.filters {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 15px;
}

.filters > div {
  width: 190px;
}

.filters > .query-id-filter {
  width: min(310px, 30vw);
}

.filters label {
  display: block;
  margin: 0 0 7px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.filters .el-select {
  width: 100%;
}

.no-export {
  margin-left: auto;
  padding-bottom: 10px;
  color: #5e6e67;
  font:  14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.log-panel {
  overflow: hidden;
}

.chain-indicator {
  color: var(--green);
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.log-table {
  min-width: 850px;
  overflow-x: auto;
}

.log-head,
.log-row {
  display: grid;
  grid-template-columns: 66px 150px minmax(160px, 1.4fr) 120px minmax(120px, 1fr) 110px 32px;
  align-items: center;
}

.log-head {
  min-height: 38px;
  padding: 0 13px;
  border-bottom: 1px solid var(--line);
  color: var(--text-dim);
  background: #fffefa;
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.log-row {
  width: 100%;
  min-height: 52px;
  padding: 0 13px;
  border: 0;
  border-bottom: 1px solid #d2dad5;
  color: var(--text-soft);
  background: transparent;
  cursor: pointer;
  font:  14px/1.4 var(--font-mono);
  text-align: left;
}

.log-row:hover {
  background: #eaf2ed;
}

.log-row > * {
  min-width: 0;
  overflow: hidden;
  padding-right: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sequence {
  color: #607169;
}

.log-row strong {
  color: #344a42;
  font-weight: 550;
}

.expand-arrow {
  color: var(--green);
  font-size: 14px;
  text-align: center;
}

.log-detail {
  padding: 18px 24px 20px 80px;
  border-bottom: 1px solid var(--line);
  background: #f2f4ef;
}

.log-detail dl {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
  margin: 0;
}

.log-detail dt {
  margin-bottom: 6px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.log-detail dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-soft);
  font-size: 13px;
  line-height: 1.5;
  text-overflow: ellipsis;
}

.pagination {
  display: flex;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  border-top: 1px solid var(--line);
}

.pagination > span {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

@media (max-width: 800px) {
  .filters {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .filters > div {
    width: auto;
  }

  .no-export {
    display: none;
  }

  .log-panel > :deep(.load-state) {
    overflow-x: auto;
  }

  .log-table {
    overflow: visible;
  }
}

@media (max-width: 520px) {
  .filters {
    grid-template-columns: 1fr;
  }
}
</style>
