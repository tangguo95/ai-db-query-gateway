<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, errorMessage } from '../api/client'
import type { Dashboard } from '../api/types'
import LoadState from '../components/LoadState.vue'
import StateChip from '../components/StateChip.vue'

const router = useRouter()
const loading = ref(true)
const error = ref('')
const dashboard = ref<Dashboard | null>(null)

const datasourceHealth = computed(() => {
  if (!dashboard.value?.dataSourceCount) return 0
  return Math.round((dashboard.value.strictCount / dashboard.value.dataSourceCount) * 100)
})

const metrics = computed(() => {
  const data = dashboard.value
  return [
    {
      code: '数据源',
      label: '受控数据源',
      value: data?.dataSourceCount ?? 0,
      unit: '个',
      tone: 'green'
    },
    {
      code: '今日查询',
      label: '今日查询',
      value: data?.queriesToday ?? 0,
      unit: '次',
      tone: 'plain'
    },
    {
      code: '审批队列',
      label: '待审批信号',
      value: data?.pendingApprovalCount ?? 0,
      unit: '项',
      tone: (data?.pendingApprovalCount ?? 0) > 0 ? 'amber' : 'plain'
    },
    {
      code: '今日拒绝',
      label: '今日拒绝',
      value: data?.rejectedToday ?? 0,
      unit: '次',
      tone: (data?.rejectedToday ?? 0) > 0 ? 'red' : 'plain'
    }
  ]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    dashboard.value = await api.dashboard()
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

function formatTime(value?: string): string {
  if (!value) return '暂无记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">系统总览 / 实时摘要</p>
        <h1 class="page-title">安全边界总览</h1>
        <p class="page-subtitle">一眼确认只读等级、待审批查询与审计链状态。这里展示运行数据，不展示任何连接凭据。</p>
      </div>
      <el-button type="primary" @click="router.push('/workbench')">发起受控查询</el-button>
    </header>

    <LoadState :loading="loading" :error="error" @retry="load">
      <div class="metrics-grid">
        <article v-for="metric in metrics" :key="metric.code" class="metric" :class="metric.tone">
          <span class="metric-code">{{ metric.code }}</span>
          <div>
            <strong>{{ metric.value }}</strong>
            <small>{{ metric.unit }}</small>
          </div>
          <p>{{ metric.label }}</p>
          <i />
        </article>
      </div>

      <div class="dashboard-grid">
        <section class="panel boundary-panel">
          <header class="panel-header">
            <div>
              <p class="panel-title">数据源只读等级</p>
              <span class="panel-code">数据源 / 防护状态</span>
            </div>
            <strong class="health-number">{{ datasourceHealth }}%</strong>
          </header>
          <div class="boundary-body">
            <div class="radar">
              <div class="radar-ring ring-a" />
              <div class="radar-ring ring-b" />
              <div class="radar-cross cross-a" />
              <div class="radar-cross cross-b" />
              <div class="radar-sweep" />
              <span>{{ dashboard?.dataSourceCount ?? 0 }}</span>
              <small>数据源</small>
            </div>
            <div class="grade-list">
              <div>
                <span><i class="grade strict" />数据库只读</span>
                <strong>{{ dashboard?.strictCount ?? 0 }}</strong>
              </div>
              <div>
                <span><i class="grade compatibility" />网关只读</span>
                <strong class="text-green">{{ dashboard?.compatibilityCount ?? 0 }}</strong>
              </div>
              <div>
                <span><i class="grade blocked" />已阻断</span>
                <strong class="text-red">{{ dashboard?.blockedCount ?? 0 }}</strong>
              </div>
              <el-button plain @click="router.push('/datasources')">检查数据源</el-button>
            </div>
          </div>
        </section>

        <section class="panel audit-panel">
          <header class="panel-header">
            <div>
              <p class="panel-title">审计链完整性</p>
              <span class="panel-code">HMAC / 链式校验</span>
            </div>
            <StateChip :value="dashboard?.auditChainValid ? 'SUCCESS' : 'FAILED'" />
          </header>
          <div class="audit-body">
            <div class="chain-strip" :class="{ invalid: dashboard?.auditChainValid === false }">
              <span v-for="n in 9" :key="n"><i />{{ String(n).padStart(2, '0') }}</span>
            </div>
            <p v-if="dashboard?.auditChainValid">
              最近一次记录已通过链式 HMAC 校验。任何审计写入失败都会拒绝查询。
            </p>
            <p v-else class="text-red">
              审计链校验未通过。在确认日志完整性前，请勿继续生产查询。
            </p>
            <div class="audit-timestamp">
              <span>最近事件</span>
              <strong>{{ formatTime(dashboard?.lastAuditAt) }}</strong>
            </div>
            <el-button plain @click="router.push('/audits')">查看完整轨迹</el-button>
          </div>
        </section>
      </div>

      <section class="panel quick-panel">
        <header class="panel-header">
          <div>
            <p class="panel-title">任务通道</p>
            <span class="panel-code">常用操作</span>
          </div>
        </header>
        <div class="quick-grid">
          <button type="button" @click="router.push('/datasources')">
            <span>01</span><strong>接入数据源</strong><small>凭据由服务端隔离保存</small><i>→</i>
          </button>
          <button type="button" @click="router.push('/workbench')">
            <span>02</span><strong>SQL 工作台</strong><small>解析风险后受控执行</small><i>→</i>
          </button>
          <button type="button" @click="router.push('/approvals')">
            <span>03</span><strong>处理审批</strong><small>{{ dashboard?.pendingApprovalCount ?? 0 }} 个等待信号</small><i>→</i>
          </button>
          <button type="button" @click="router.push('/tokens')">
            <span>04</span><strong>签发令牌</strong><small>为 AI 限定数据源作用域</small><i>→</i>
          </button>
        </div>
      </section>
    </LoadState>
  </div>
</template>

<style scoped>
.metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 16px;
}

.metric {
  position: relative;
  min-height: 146px;
  overflow: hidden;
  padding: 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius);
  background: var(--bg-panel);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
  transition: border-color .18s ease, box-shadow .18s ease, transform .18s ease;
}

.metric::after {
  display: none;
}

.metric:hover {
  border-color: #cbd5e1;
  box-shadow: 0 8px 20px rgba(15, 23, 42, .06);
  transform: translateY(-1px);
}

.metric-code {
  color: var(--text-dim);
  font: 500 12px/1 var(--font-display);
  letter-spacing: 0;
}

.metric div {
  margin-top: 22px;
}

.metric strong {
  color: var(--text);
  font: 700 34px/.9 var(--font-mono);
}

.metric small {
  margin-left: 7px;
  color: var(--text-dim);
  font-size: 13px;
}

.metric p {
  margin: 10px 0 0;
  color: var(--text-soft);
  font-size: 13px;
}

.metric > i {
  position: absolute;
  right: 17px;
  top: 17px;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #607169;
}

.metric.green > i {
  background: var(--green);
  box-shadow: 0 0 10px var(--green);
}

.metric.amber > i {
  background: var(--amber);
  box-shadow: 0 0 10px var(--amber);
}

.metric.red > i {
  background: var(--red);
  box-shadow: 0 0 10px var(--red);
}

.dashboard-grid {
  display: grid;
  grid-template-columns: 1.12fr .88fr;
  gap: 16px;
  margin-bottom: 16px;
}

.health-number {
  color: var(--green);
  font: 650 20px/1 var(--font-mono);
}

.boundary-body {
  display: grid;
  min-height: 288px;
  grid-template-columns: 1fr 1fr;
  align-items: center;
  padding: 24px;
}

.radar {
  position: relative;
  display: grid;
  width: 176px;
  height: 176px;
  margin: auto;
  place-content: center;
  overflow: hidden;
  border: 1px solid #bfdbfe;
  border-radius: 50%;
  color: var(--green);
  background: radial-gradient(circle, rgba(37, 99, 235, .08), transparent 62%);
  text-align: center;
}

.radar span {
  z-index: 2;
  font: 650 42px/.9 var(--font-mono);
}

.radar small {
  z-index: 2;
  margin-top: 6px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .13em;
}

.radar-ring,
.radar-cross,
.radar-sweep {
  position: absolute;
}

.radar-ring {
  inset: 27px;
  border: 1px solid rgba(37, 99, 235, .14);
  border-radius: 50%;
}

.radar-ring.ring-b {
  inset: 58px;
}

.radar-cross.cross-a {
  top: 50%;
  right: 0;
  left: 0;
  border-top: 1px solid rgba(37, 99, 235, .12);
}

.radar-cross.cross-b {
  top: 0;
  bottom: 0;
  left: 50%;
  border-left: 1px solid rgba(37, 99, 235, .12);
}

.radar-sweep {
  top: 50%;
  left: 50%;
  width: 84px;
  height: 1px;
  background: linear-gradient(90deg, var(--blue), transparent);
  transform-origin: left center;
  opacity: .45;
  transform: rotate(-18deg);
}

@keyframes sweep {
  to { transform: rotate(360deg); }
}

.grade-list {
  display: grid;
  gap: 5px;
}

.grade-list > div {
  display: flex;
  min-height: 43px;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border: 1px solid var(--line);
}

.grade-list span {
  color: var(--text-soft);
  font:  14px/1 var(--font-mono);
}

.grade-list strong {
  color: var(--green);
  font: 650 16px/1 var(--font-mono);
}

.grade {
  display: inline-block;
  width: 4px;
  height: 14px;
  margin-right: 9px;
  vertical-align: middle;
  background: var(--green);
}

.grade.compatibility { background: var(--green); }
.grade.blocked { background: var(--red); }

.grade-list .el-button {
  margin-top: 8px;
}

.audit-body {
  min-height: 288px;
  padding: 24px;
}

.chain-strip {
  display: flex;
  height: 62px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  border-top: 1px solid var(--green-deep);
  border-bottom: 1px solid var(--green-deep);
}

.chain-strip span {
  color: var(--text-dim);
  font: 12px/1 var(--font-mono);
}

.chain-strip i {
  display: block;
  width: 7px;
  height: 7px;
  margin: 0 auto 6px;
  border: 1px solid var(--green);
  background: var(--green-deep);
  transform: rotate(45deg);
}

.chain-strip.invalid {
  border-color: var(--red-deep);
}

.chain-strip.invalid i {
  border-color: var(--red);
  background: var(--red-deep);
}

.audit-body > p {
  min-height: 42px;
  margin: 0 0 22px;
  color: var(--text-soft);
  font-size: 13px;
  line-height: 1.7;
}

.audit-timestamp {
  display: flex;
  justify-content: space-between;
  margin-bottom: 18px;
  padding-top: 13px;
  border-top: 1px solid var(--line);
}

.audit-timestamp span,
.audit-timestamp strong {
  font:  14px/1.3 var(--font-mono);
}

.audit-timestamp span { color: var(--text-dim); }
.audit-timestamp strong { color: var(--text-soft); font-weight: 500; }

.audit-body .el-button {
  width: 100%;
}

.quick-panel {
  margin-top: 16px;
}

.quick-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
}

.quick-grid button {
  position: relative;
  display: grid;
  min-height: 112px;
  grid-template-columns: 28px 1fr;
  grid-template-rows: auto auto;
  align-content: center;
  gap: 4px 9px;
  padding: 20px;
  border-radius: 0;
  border: 0;
  border-right: 1px solid var(--line);
  color: var(--text);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}

.quick-grid button:last-child {
  border-right: 0;
}

.quick-grid button:hover {
  background: #f8fafc;
}

.quick-grid button > span {
  grid-row: span 2;
  color: var(--blue);
  font: 600 12px/1.5 var(--font-mono);
}

.quick-grid button strong {
  font: 650 13px/1.3 var(--font-display);
}

.quick-grid button small {
  color: var(--text-dim);
  font-size: 14px;
}

.quick-grid button i {
  position: absolute;
  right: 16px;
  bottom: 15px;
  color: var(--text-dim);
  font-style: normal;
}

@media (max-width: 1050px) {
  .metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .quick-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .quick-grid button:nth-child(2) {
    border-right: 0;
  }

  .quick-grid button:nth-child(-n + 2) {
    border-bottom: 1px solid var(--line);
  }
}

@media (max-width: 570px) {
  .metrics-grid {
    grid-template-columns: 1fr 1fr;
  }

  .metric {
    min-height: 125px;
    padding: 14px;
  }

  .metric strong {
    font-size: 29px;
  }

  .boundary-body {
    grid-template-columns: 1fr;
    gap: 24px;
  }
}
</style>
