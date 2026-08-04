<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { api, errorMessage, normalizeList } from '../api/client'
import type { AuditRecord, QueryResponse } from '../api/types'
import LoadState from '../components/LoadState.vue'
import QueryResultTable from '../components/QueryResultTable.vue'
import StateChip from '../components/StateChip.vue'

const loading = ref(true)
const error = ref('')
const records = ref<AuditRecord[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(25)
const expandedId = ref<string | number | null>(null)
const queryDetails = ref<Record<string, QueryResponse | null>>({})
const queryDetailErrors = ref<Record<string, string>>({})
const queryDetailLoading = ref<string | null>(null)
const filters = reactive({
  eventType: 'APPROVAL',
  status: '',
  queryId: ''
})

const eventLabels: Record<string, string> = {
  AUDIT_VIEWED: '查看审计记录',
  ADMIN_PASSWORD_CHANGED: '修改管理员密码',
  ADMIN_PASSWORD_CHANGE_FAILED: '修改管理员密码失败',
  ADMIN_PROFILE_UPDATED: '更新用户信息',
  DATASOURCE_CREATED: '新增数据源',
  DATASOURCE_CREATE_REQUESTED: '申请新增数据源',
  DATASOURCE_CREDENTIAL_CHANGED: '更新数据源凭据',
  DATASOURCE_DELETED: '删除数据源',
  DATASOURCE_DELETE_REQUESTED: '申请删除数据源',
  DATASOURCE_DISABLED: '停用数据源',
  DATASOURCE_RECHECK_FAILED: '数据源复检失败',
  DATASOURCE_SECRET_UPDATED: '更新数据源密钥',
  DATASOURCE_TESTED: '数据源连接检查完成',
  DATASOURCE_TEST_REQUESTED: '申请检查数据源连接',
  DATASOURCE_AUTO_RECHECK_POLICY_CHANGED: '修改数据源自动复检开关',
  DATASOURCE_UPDATED: '更新数据源配置',
  HTTP_API_COMPLETED: '接口请求完成',
  HTTP_API_REQUESTED: '接口请求开始',
  LOGIN_FAILED: '登录失败',
  LOGIN_RATE_LIMITED: '登录被限流',
  LOGIN_REQUESTED: '登录请求',
  LOGIN_SUCCESS: '登录成功',
  QUERY_APPROVAL_REQUESTED: '提交审批申请',
  QUERY_APPROVED: '审批通过',
  QUERY_CANCEL_ACCEPTED: '接受取消查询',
  QUERY_CANCELLED: '取消查询',
  QUERY_CANCEL_REQUESTED: '申请取消查询',
  QUERY_EXECUTED: '查询执行完成',
  QUERY_EXECUTION_STARTED: '开始执行查询',
  QUERY_FAILED: '查询执行失败',
  QUERY_PENDING_APPROVAL: '进入待审批队列',
  QUERY_POLICY_AUTO_APPROVED: '风险查询免审批放行',
  QUERY_POLICY_APPROVED: '策略校验通过',
  QUERY_POLICY_REJECTED: '策略拒绝查询',
  QUERY_PREVIEW_FAILED: '查询预检失败',
  QUERY_PREVIEW_REJECTED: '查询预检拒绝',
  QUERY_PREVIEW_REQUESTED: '发起查询预检',
  QUERY_PREVIEW_SUCCEEDED: '查询预检通过',
  QUERY_REJECTED: '查询被拒绝',
  QUERY_REQUESTED: '提交查询申请',
  QUERY_REQUEST_REJECTED: '查询请求被拒绝',
  QUERY_APPROVAL_POLICY_CHANGED: '修改查询审批开关',
  TOKEN_CREATED: '创建访问令牌',
  TOKEN_CREATE_REQUESTED: '申请创建访问令牌',
  TOKEN_DELETED: '吊销访问令牌',
  TOKEN_DELETE_REQUESTED: '申请吊销访问令牌',
  TOKEN_SCOPE_UPDATED: '更新令牌数据源范围',
  TOKEN_SCOPE_UPDATE_REQUESTED: '申请更新令牌范围'
}

const statusLabels: Record<string, string> = {
  APPROVED: '已批准',
  CANCELLED: '已取消',
  EXECUTING: '执行中',
  EXECUTED: '已执行',
  EXPIRED: '已过期',
  FAILED: '失败',
  PENDING_APPROVAL: '待审批',
  REJECTED: '已拒绝',
  REQUESTED: '已申请',
  SUCCESS: '成功',
  TIMED_OUT: '已超时'
}

const actorLabels: Record<string, string> = {
  ADMIN: '网页管理员',
  API_TOKEN: 'AI 访问令牌',
  SYSTEM: '系统任务',
  ANONYMOUS: '未登录请求'
}

const errorLabels: Record<string, string> = {
  APPROVAL_ALREADY_CONSUMED: '审批许可已经使用',
  APPROVAL_EXPIRED: '审批许可已过期',
  AUDIT_CHAIN_INVALID: '审计链校验失败',
  AUDIT_UNAVAILABLE: '审计服务不可用',
  DATABASE_QUERY_FAILED: '数据库查询失败',
  INVALID_CURRENT_PASSWORD: '当前密码错误',
  PASSWORD_REUSE: '新密码不能与当前密码相同',
  QUERY_CANCELLED: '查询已取消',
  QUERY_TIMEOUT: '查询执行超时',
  QUERY_POLICY_REJECTED: 'SQL 策略拒绝',
  QUERY_SCOPE_DENIED: '令牌没有数据源权限'
}

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
  expandedId.value = null
  void load()
}

function resetFilters() {
  filters.eventType = 'APPROVAL'
  filters.status = ''
  filters.queryId = ''
  page.value = 1
  expandedId.value = null
  void load()
}

function recordKey(record: AuditRecord): string {
  return String(record.id)
}

function timeParts(record: AuditRecord): { date: string; time: string } {
  const value = record.timestamp || record.createdAt
  if (!value) return { date: '—', time: '—' }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return { date: value, time: '' }
  const pad = (part: number) => String(part).padStart(2, '0')
  return {
    date: `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`,
    time: `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  }
}

function action(record: AuditRecord): string {
  const code = record.eventType || record.action || ''
  return eventLabels[code] || '其他操作'
}

function eventCode(record: AuditRecord): string {
  return record.eventType || record.action || '未知事件'
}

function actor(record: AuditRecord): string {
  return actorLabels[record.actorType || ''] || (record.actor ? '操作用户' : '系统任务')
}

function status(record: AuditRecord): string {
  const value = String(record.status || 'SUCCESS').toUpperCase()
  return statusLabels[value] || '状态未知'
}

function statusCode(record: AuditRecord): string {
  return String(record.status || 'SUCCESS').toUpperCase()
}

function errorText(record: AuditRecord): string {
  if (!record.errorCode) return '无'
  return errorLabels[record.errorCode] || '操作未完成'
}

function metrics(record: AuditRecord): string {
  const duration = record.durationMs === undefined ? '—' : `${record.durationMs} 毫秒`
  const rows = record.rowCount === undefined ? '—' : `${record.rowCount} 行`
  const bytes = record.bytes === undefined ? '—' : `${record.bytes} 字节`
  return `${duration} · ${rows} · ${bytes}`
}

async function toggle(record: AuditRecord) {
  if (expandedId.value === record.id) {
    expandedId.value = null
    return
  }
  expandedId.value = record.id
  if (!record.queryId || queryDetails.value[recordKey(record)] !== undefined) return

  const key = recordKey(record)
  queryDetailLoading.value = key
  queryDetailErrors.value[key] = ''
  try {
    queryDetails.value[key] = await api.queryResult(record.queryId)
  } catch (cause) {
    queryDetails.value[key] = null
    queryDetailErrors.value[key] = errorMessage(cause)
  } finally {
    queryDetailLoading.value = null
  }
}

function queryDetail(record: AuditRecord): QueryResponse | null | undefined {
  return queryDetails.value[recordKey(record)]
}

function queryDetailRequired(record: AuditRecord): QueryResponse {
  return queryDetails.value[recordKey(record)] as QueryResponse
}

function queryResultHint(detail: QueryResponse): string {
  if (detail.resultAvailable) return '结果已在网关进程内短暂保留，可在下方查看。'
  if (detail.status === 'EXECUTED') return '查询已执行，但结果已从临时缓存中清除，网关不会从磁盘恢复结果。'
  return '查询尚未执行，当前只展示审批和策略信息。'
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">查询审计 / 审批记录</p>
        <h1 class="page-title">查询审计记录</h1>
        <p class="page-subtitle">默认只显示查询审批事件。展开记录可查看查询申请和近期结果；结果不写入磁盘，过期后只能看到执行元数据。</p>
      </div>
      <div class="retention-label"><span>∞</span> 审计链持续校验</div>
    </header>

    <section class="panel filter-panel">
      <div class="filters">
        <div>
          <label>查看范围</label>
          <el-select v-model="filters.eventType" clearable placeholder="全部事件">
            <el-option label="审批记录（推荐）" value="APPROVAL" />
            <el-option label="全部事件" value="" />
            <el-option label="查询事件" value="QUERY" />
            <el-option label="数据源操作" value="DATASOURCE" />
            <el-option label="访问令牌" value="TOKEN" />
            <el-option label="登录认证" value="LOGIN" />
            <el-option label="用户设置" value="ADMIN" />
          </el-select>
        </div>
        <div>
          <label>处理结果</label>
          <el-select v-model="filters.status" clearable placeholder="全部状态">
            <el-option label="已申请" value="REQUESTED" />
            <el-option label="待审批" value="PENDING_APPROVAL" />
            <el-option label="已批准" value="APPROVED" />
            <el-option label="已执行" value="EXECUTED" />
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已拒绝" value="REJECTED" />
          </el-select>
        </div>
        <div class="query-id-filter">
          <label>查询编号</label>
          <el-input
            v-model="filters.queryId"
            clearable
            maxlength="36"
            placeholder="按查询 ID 精确定位"
            spellcheck="false"
            @keyup.enter="applyFilters"
          />
        </div>
        <el-button type="primary" @click="applyFilters">筛选记录</el-button>
        <el-button plain @click="resetFilters">恢复默认</el-button>
        <span class="local-time-note">时间显示为本机时间</span>
      </div>
    </section>

    <section class="panel log-panel">
      <header class="panel-header">
        <div>
          <p class="panel-title">审核操作记录</p>
          <span class="panel-code">{{ filters.eventType === 'APPROVAL' ? '审批事件' : '审计事件' }} / 共 {{ total }} 条</span>
        </div>
        <span class="chain-indicator"><i class="status-dot" />{{ records.length ? '链路正常' : '等待记录' }}</span>
      </header>

      <LoadState
        :loading="loading"
        :error="error"
        :empty="!records.length"
        empty-title="没有匹配的记录"
        empty-text="可以切换查看范围，或等待新的查询审批操作写入审计链。"
        @retry="load"
      >
        <div class="log-table" role="table" aria-label="查询审计记录">
          <div class="log-head" role="row">
            <span>序号</span>
            <span>发生时间</span>
            <span>事件</span>
            <span>操作主体</span>
            <span>数据源</span>
            <span>结果</span>
            <span />
          </div>
          <template v-for="(record, index) in records" :key="record.id">
            <button class="log-row" type="button" role="row" @click="toggle(record)">
              <span class="sequence">{{ String((page - 1) * pageSize + index + 1).padStart(5, '0') }}</span>
              <time class="full-time">
                <span>{{ timeParts(record).date }}</span>
                <span>{{ timeParts(record).time }}</span>
              </time>
              <strong>{{ action(record) }}</strong>
              <span>{{ actor(record) }}</span>
              <span>{{ record.dataSourceName || record.dataSourceId || '—' }}</span>
              <span><StateChip :value="statusCode(record)" /></span>
              <span class="expand-arrow">{{ expandedId === record.id ? '−' : '+' }}</span>
            </button>
            <div v-if="expandedId === record.id" class="log-detail">
              <dl>
                <div>
                  <dt>发生时间</dt>
                  <dd>{{ timeParts(record).date }} {{ timeParts(record).time }}（本机时间）</dd>
                </div>
                <div>
                  <dt>事件说明</dt>
                  <dd>{{ action(record) }}</dd>
                </div>
                <div>
                  <dt>处理结果</dt>
                  <dd>{{ status(record) }}</dd>
                </div>
                <div>
                  <dt>操作主体</dt>
                  <dd>{{ actor(record) }}</dd>
                </div>
                <div>
                  <dt>数据源</dt>
                  <dd>{{ record.dataSourceName || record.dataSourceId || '—' }}</dd>
                </div>
                <div>
                  <dt>查询用途</dt>
                  <dd>{{ record.purpose || '—' }}</dd>
                </div>
                <div>
                  <dt>查询编号</dt>
                  <dd class="mono">{{ record.queryId || '—' }}</dd>
                </div>
                <div>
                  <dt>执行统计</dt>
                  <dd>{{ metrics(record) }}</dd>
                </div>
                <div>
                  <dt>错误说明</dt>
                  <dd :class="{ 'text-red': record.errorCode }">
                    {{ errorText(record) }}<small v-if="record.errorCode" class="raw-code">{{ record.errorCode }}</small>
                  </dd>
                </div>
                <div>
                  <dt>链路校验</dt>
                  <dd :class="record.chainValid === false ? 'text-red' : 'text-green'">
                    {{ record.chainValid === false ? '校验失败' : '已通过校验' }}
                  </dd>
                </div>
                <div>
                  <dt>事件编号</dt>
                  <dd class="mono">{{ record.id }}</dd>
                </div>
                <div>
                  <dt>系统事件代码</dt>
                  <dd class="mono raw-code">{{ eventCode(record) }}</dd>
                </div>
              </dl>

              <section v-if="record.queryId" class="query-audit-detail">
                <header>
                  <div>
                    <span>关联查询</span>
                    <strong>{{ record.queryId }}</strong>
                  </div>
                  <span v-if="queryDetailLoading === recordKey(record)" class="loading-note">正在读取查询详情…</span>
                </header>
                <div v-if="queryDetailErrors[recordKey(record)]" class="query-detail-error">
                  {{ queryDetailErrors[recordKey(record)] }}
                </div>
                <template v-else-if="queryDetail(record)">
                  <div class="query-detail-summary">
                    <span>当前状态 <StateChip :value="queryDetail(record)?.status" /></span>
                    <span>用途：{{ queryDetail(record)?.purpose || '—' }}</span>
                    <span>限额：{{ queryDetail(record)?.effectiveMaxRows ?? '—' }} 行</span>
                  </div>
                  <pre v-if="queryDetail(record)?.sql" class="query-sql">{{ queryDetail(record)?.sql }}</pre>
                  <p class="result-retention-note">{{ queryResultHint(queryDetailRequired(record)) }}</p>
                  <QueryResultTable
                    v-if="queryDetail(record)?.status === 'EXECUTED'"
                    :result="queryDetailRequired(record)"
                  />
                </template>
              </section>
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
  font: 14px/1 var(--font-mono);
  letter-spacing: .08em;
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
  font: 14px/1 var(--font-mono);
  letter-spacing: .06em;
}

.filters .el-select {
  width: 100%;
}

.local-time-note {
  margin-left: auto;
  padding-bottom: 10px;
  color: var(--text-dim);
  font: 13px/1 var(--font-mono);
  white-space: nowrap;
}

.log-panel {
  overflow: hidden;
}

.chain-indicator {
  color: var(--green);
  font: 14px/1 var(--font-mono);
  letter-spacing: .06em;
}

.log-table {
  min-width: 980px;
  overflow-x: auto;
}

.log-head,
.log-row {
  display: grid;
  grid-template-columns: 66px 178px minmax(160px, 1.35fr) 130px minmax(135px, 1fr) 100px 32px;
  align-items: center;
}

.log-head {
  min-height: 38px;
  padding: 0 13px;
  border-bottom: 1px solid var(--line);
  color: var(--text-dim);
  background: var(--bg-panel-2);
  font: 14px/1 var(--font-mono);
  letter-spacing: .06em;
}

.log-row {
  width: 100%;
  min-height: 58px;
  padding: 0 13px;
  border: 0;
  border-bottom: 1px solid var(--line);
  color: var(--text-soft);
  background: var(--bg-panel);
  cursor: pointer;
  font: 14px/1.4 var(--font-mono);
  text-align: left;
}

.log-row:hover {
  background: var(--bg-panel-2);
}

.log-row > * {
  min-width: 0;
  overflow: hidden;
  padding-right: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sequence {
  color: var(--text-dim);
}

.full-time {
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow: visible;
  color: var(--text-soft);
  font-size: 13px;
  line-height: 1.15;
  white-space: nowrap;
}

.full-time span:last-child {
  color: var(--text-dim);
  font-size: 12px;
}

.log-row strong {
  overflow: hidden;
  color: var(--text);
  font-family: var(--font-display);
  font-size: 14px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.expand-arrow {
  color: var(--green);
  font-size: 16px;
  text-align: center;
}

.log-detail {
  padding: 20px 24px 24px 80px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-deep);
}

.log-detail dl {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 18px;
  margin: 0;
}

.log-detail dt {
  margin-bottom: 6px;
  color: var(--text-dim);
  font: 13px/1 var(--font-mono);
}

.log-detail dd {
  margin: 0;
  overflow: hidden;
  color: var(--text-soft);
  font-size: 13px;
  line-height: 1.5;
  text-overflow: ellipsis;
}

.raw-code {
  display: block;
  margin-top: 4px;
  color: var(--text-dim);
  font: 11px/1.4 var(--font-mono);
}

.query-audit-detail {
  margin-top: 22px;
  border: 1px solid var(--line-strong);
  background: var(--bg-panel);
}

.query-audit-detail > header {
  display: flex;
  min-height: 48px;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 0 16px;
  border-bottom: 1px solid var(--line);
}

.query-audit-detail > header > div {
  display: flex;
  align-items: baseline;
  gap: 14px;
}

.query-audit-detail > header span {
  color: var(--text-dim);
  font: 13px/1 var(--font-mono);
}

.query-audit-detail > header strong {
  color: var(--text);
  font: 13px/1 var(--font-mono);
}

.loading-note {
  color: var(--green) !important;
}

.query-detail-error {
  padding: 15px 16px;
  color: var(--red);
  background: color-mix(in srgb, var(--red-deep) 42%, var(--bg-panel));
  font-size: 13px;
}

.query-detail-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  color: var(--text-dim);
  font-size: 13px;
}

.query-detail-summary span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.query-sql {
  max-height: 220px;
  margin: 0;
  overflow: auto;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  color: var(--text-soft);
  background: var(--bg-deep);
  font: 13px/1.6 var(--font-mono);
  white-space: pre-wrap;
  word-break: break-word;
}

.result-retention-note {
  margin: 0;
  padding: 13px 16px;
  color: var(--text-dim);
  background: var(--bg-panel-2);
  font-size: 13px;
  line-height: 1.6;
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
  font: 14px/1 var(--font-mono);
}

@media (max-width: 900px) {
  .filters {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }

  .filters > div,
  .filters > .query-id-filter {
    width: auto;
  }

  .local-time-note {
    display: none;
  }

  .log-panel > :deep(.load-state) {
    overflow-x: auto;
  }

  .log-table {
    overflow: visible;
  }
}

@media (max-width: 560px) {
  .filters {
    grid-template-columns: 1fr;
  }

  .log-detail {
    padding-left: 20px;
  }

  .log-detail dl {
    grid-template-columns: 1fr;
  }
}

.filter-panel,
.log-panel {
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.log-table {
  border-top: 0;
}

.log-table :deep(.el-table__header-wrapper th) {
  background: var(--bg-panel-2);
}

.query-audit-detail {
  border-radius: 7px;
  background: var(--bg-panel);
}

.query-sql {
  color: var(--text-soft);
  background: var(--bg-deep);
}

.result-retention-note {
  color: var(--text-dim);
  background: var(--bg-panel-2);
}
</style>
