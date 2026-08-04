<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errorMessage } from '../api/client'
import type { CurrentUser, DataSourceRecoveryPolicy, Dashboard, QueryApprovalPolicy } from '../api/types'
import { useAuthStore } from '../stores/auth'
import LoadState from '../components/LoadState.vue'
import StateChip from '../components/StateChip.vue'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(true)
const error = ref('')
const user = ref<CurrentUser | null>(null)
const dashboard = ref<Dashboard | null>(null)
const approvalPolicy = ref<QueryApprovalPolicy | null>(null)
const recoveryPolicy = ref<DataSourceRecoveryPolicy | null>(null)
const locking = ref(false)
const updatingApprovalPolicy = ref(false)
const updatingRecoveryPolicy = ref(false)

const policyGroups = [
  {
    code: 'SQL-01',
    title: 'AST 白名单',
    state: '已启用',
    description: '仅允许单条 SELECT、UNION 或最终为 SELECT 的非递归 CTE。解析失败默认拒绝。'
  },
  {
    code: 'JDBC-02',
    title: '只读事务',
    state: '已启用',
    description: 'PreparedStatement.executeQuery + JDBC read-only + 数据库只读事务，结束后无条件 rollback。'
  },
  {
    code: 'LIM-03',
    title: '资源保险丝',
    state: '已启用',
    description: '硬上限 1,000 行、5 MiB 响应、256 KiB 单字段，超时或断开时主动取消。'
  },
  {
    code: 'LOG-04',
    title: '审计先行',
    state: '失败即关闭',
    description: 'REQUESTED 审计写入失败即拒绝查询；结果集不进入日志，也不写入本地磁盘。'
  }
]

async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[user.value, dashboard.value, approvalPolicy.value, recoveryPolicy.value] = await Promise.all([
      api.currentUser(),
      api.dashboard(),
      api.queryApprovalPolicy(),
      api.dataSourceRecoveryPolicy()
    ])
  } catch (cause) {
    error.value = errorMessage(cause)
  } finally {
    loading.value = false
  }
}

async function changeApprovalMode(skipApproval: boolean) {
  if (!approvalPolicy.value || updatingApprovalPolicy.value) return
  const nextApprovalRequired = !skipApproval
  const previousApprovalRequired = approvalPolicy.value.approvalRequired
  if (nextApprovalRequired === previousApprovalRequired) return

  if (skipApproval) {
    try {
      await ElMessageBox.confirm(
        '开启后，命中风险规则的 AI 查询也会直接执行。SQL 只读解析、查询超时、行数、响应大小和并发限制仍然生效。',
        '确认开启免审批执行',
        {
          confirmButtonText: '确认开启',
          cancelButtonText: '暂不开启',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  updatingApprovalPolicy.value = true
  try {
    approvalPolicy.value = await api.updateQueryApprovalPolicy(nextApprovalRequired)
    ElMessage.success(nextApprovalRequired ? '已恢复高风险查询审批' : '已开启高风险查询免审批执行')
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    updatingApprovalPolicy.value = false
  }
}

async function changeRecoveryMode(enabled: boolean) {
  if (!recoveryPolicy.value || updatingRecoveryPolicy.value) return
  if (enabled === recoveryPolicy.value.autoRetryConnectionChecks) return

  if (enabled) {
    try {
      await ElMessageBox.confirm(
        '开启后，后台会定期对已隔离的数据源发起连接检查。它只获取连接和数据库元数据，不会执行或重试任何 SQL；连接成功后会恢复数据源启用状态。',
        '确认开启自动连接复检',
        {
          confirmButtonText: '确认开启',
          cancelButtonText: '暂不开启',
          type: 'warning'
        }
      )
    } catch {
      return
    }
  }

  updatingRecoveryPolicy.value = true
  try {
    recoveryPolicy.value = await api.updateDataSourceRecoveryPolicy(enabled)
    ElMessage.success(enabled ? '已开启自动连接复检' : '已关闭自动连接复检')
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    updatingRecoveryPolicy.value = false
  }
}

async function lockSession() {
  locking.value = true
  try {
    await auth.logout()
    await router.replace('/login')
  } catch (cause) {
    ElMessage.error(errorMessage(cause))
  } finally {
    locking.value = false
  }
}

function formatTime(value?: string): string {
  if (!value) return '由服务端管理'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false })
}

onMounted(load)
</script>

<template>
  <div>
    <header class="page-heading">
      <div>
        <p class="eyebrow">安全防护 / 本地边界</p>
        <h1 class="page-title">安全设置</h1>
        <p class="page-subtitle">关键防线由服务端固定执行。网页只展示能够从 API 验证的状态，不推断监听地址、TLS 或 SecretStore 实现。</p>
      </div>
      <el-button type="danger" plain :loading="locking" @click="lockSession">立即锁定会话</el-button>
    </header>

    <LoadState :loading="loading" :error="error" @retry="load">
      <div class="security-grid">
        <section class="panel posture-panel">
          <header class="panel-header">
            <div>
              <p class="panel-title">边界姿态</p>
              <span class="panel-code">服务端强制 / 网页未认证运行态</span>
            </div>
            <StateChip value="UNATTESTED" fallback="运行态未认证" />
          </header>
          <div class="posture-body">
            <div class="shield">
              <div class="shield-core">安</div>
              <span class="orbit orbit-a" />
              <span class="orbit orbit-b" />
              <i v-for="n in 4" :key="n" :class="`node node-${n}`" />
            </div>
            <div class="posture-info">
              <div class="posture-list">
                <div><span>默认监听策略</span><strong>本机回环</strong></div>
                <div><span>远程模式</span><strong>以服务端配置为准</strong></div>
                <div><span>凭据存储</span><strong>不向网页公开</strong></div>
                <div><span>结果持久化</span><strong>API 设计：不保存</strong></div>
                <div><span>运行态认证</span><strong>尚未对外提供</strong></div>
              </div>
              <p class="runtime-disclaimer">
                监听地址、TLS 开关和凭据存储类型必须以服务端启动日志与部署配置为准；本页不提供未经服务端签名或回传的绿色保证。
              </p>
            </div>
          </div>
        </section>

        <section class="panel session-panel">
          <header class="panel-header">
            <div>
              <p class="panel-title">当前操作员会话</p>
              <span class="panel-code">Spring Security 会话</span>
            </div>
          </header>
          <div class="session-body">
            <div class="operator-avatar">{{ (user?.displayName || user?.username || 'A').slice(0, 1).toUpperCase() }}</div>
            <h2>{{ user?.displayName || user?.username || '本地管理员' }}</h2>
            <p><span class="status-dot" />已认证 / 本机会话</p>
            <dl>
              <div>
                <dt>空闲失效时间</dt>
                <dd>{{ formatTime(user?.idleExpiresAt) }}</dd>
              </div>
              <div>
                <dt>最长会话失效时间</dt>
                <dd>{{ formatTime(user?.sessionExpiresAt) }}</dd>
              </div>
            </dl>
            <el-button plain @click="load">重新校验身份</el-button>
          </div>
        </section>
      </div>

      <section class="panel policy-panel">
        <header class="panel-header">
          <div>
            <p class="panel-title">不可降级安全策略</p>
            <span class="panel-code">服务端强制执行</span>
          </div>
          <span class="fixed-label">网页不可覆盖</span>
        </header>
        <div class="policy-list">
          <article v-for="policy in policyGroups" :key="policy.code">
            <span class="policy-code">{{ policy.code }}</span>
            <div>
              <h3>{{ policy.title }}</h3>
              <p>{{ policy.description }}</p>
            </div>
            <strong>{{ policy.state }}</strong>
          </article>
        </div>
      </section>

      <section class="panel approval-policy-panel">
        <header class="panel-header">
          <div>
            <p class="panel-title">高风险查询审批</p>
            <span class="panel-code">管理员开关 / 本地持久化</span>
          </div>
          <span class="fixed-label" :class="{ 'approval-off-label': approvalPolicy && !approvalPolicy.approvalRequired }">
            {{ approvalPolicy?.approvalRequired ? '当前需要审批' : '当前免审批执行' }}
          </span>
        </header>
        <div v-if="approvalPolicy" class="approval-policy-body">
          <div class="approval-policy-copy">
            <div class="approval-policy-status">
              <span class="status-dot" :class="{ 'status-dot-warning': !approvalPolicy.approvalRequired }" />
              <strong>{{ approvalPolicy.approvalRequired ? '风险查询进入待审批' : '风险查询自动执行' }}</strong>
            </div>
            <p>
              关闭审批后，仅跳过网页一次性审批；SQL AST 只读校验、数据库只读事务、查询超时、行数、响应大小、并发和令牌数据源范围不会关闭。
            </p>
            <small>只影响切换后新提交的 API Token / MCP 风险查询；已经进入队列的申请不会自动改状态。</small>
          </div>
          <div class="approval-policy-control">
            <el-switch
              :model-value="!approvalPolicy.approvalRequired"
              active-text="免审批执行"
              inactive-text="每次审批"
              :loading="updatingApprovalPolicy"
              @change="changeApprovalMode(Boolean($event))"
            />
            <span>设置会保存到本机，重启后继续生效。</span>
          </div>
        </div>
      </section>

      <section class="panel recovery-policy-panel">
        <header class="panel-header">
          <div>
            <p class="panel-title">数据源自动复检</p>
            <span class="panel-code">管理员开关 / 仅检查连接</span>
          </div>
          <span class="fixed-label" :class="{ 'recovery-off-label': recoveryPolicy && !recoveryPolicy.autoRetryConnectionChecks }">
            {{ recoveryPolicy?.autoRetryConnectionChecks ? '当前已开启' : '当前未开启' }}
          </span>
        </header>
        <div v-if="recoveryPolicy" class="recovery-policy-body">
          <div class="recovery-policy-copy">
            <div class="approval-policy-status">
              <span class="status-dot" :class="{ 'status-dot-warning': !recoveryPolicy.autoRetryConnectionChecks }" />
              <strong>{{ recoveryPolicy.autoRetryConnectionChecks ? '隔离数据源会自动检查' : '隔离数据源需要手动复检' }}</strong>
            </div>
            <p>
              开启后，后台只对“连接未知 / 已隔离”的数据源做连接检查；检查成功后恢复启用。不会执行、重试或保存任何 SQL 查询结果。
            </p>
            <small>
              检查间隔约 {{ recoveryPolicy.retryIntervalSeconds }} 秒，连续失败会逐步延长间隔，最长 {{ recoveryPolicy.maxBackoffMinutes }} 分钟。
            </small>
          </div>
          <div class="recovery-policy-control">
            <el-switch
              :model-value="recoveryPolicy.autoRetryConnectionChecks"
              active-text="自动检查"
              inactive-text="手动复检"
              :loading="updatingRecoveryPolicy"
              @change="changeRecoveryMode(Boolean($event))"
            />
            <span>设置会保存到本机，重启后继续生效。</span>
          </div>
        </div>
      </section>

      <section class="panel integrity-panel">
        <header class="panel-header">
          <div>
            <p class="panel-title">审计完整性</p>
            <span class="panel-code">链路状态 / 最近事件</span>
          </div>
          <StateChip :value="dashboard?.auditChainValid ? 'SUCCESS' : 'FAILED'" />
        </header>
        <div class="integrity-body">
          <div class="checksum">
            <span v-for="(char, index) in 'A3-F8-19-7C-CHAIN'.split('')" :key="index">{{ char }}</span>
          </div>
          <div>
            <h3>{{ dashboard?.auditChainValid ? '链式校验正常' : '链式校验异常' }}</h3>
            <p>最近审计事件：{{ formatTime(dashboard?.lastAuditAt) }}。本地链式 HMAC 用于检测篡改，不等同独立 WORM 存储。</p>
          </div>
          <el-button plain @click="router.push('/audits')">打开审计记录带</el-button>
        </div>
      </section>
    </LoadState>
  </div>
</template>

<style scoped>
.security-grid {
  display: grid;
  grid-template-columns: 1.25fr .75fr;
  gap: 12px;
  margin-bottom: 12px;
}

.posture-body {
  display: grid;
  min-height: 310px;
  grid-template-columns: .9fr 1.1fr;
  align-items: center;
  padding: 24px;
}

.shield {
  position: relative;
  display: grid;
  width: 190px;
  height: 190px;
  margin: auto;
  place-content: center;
}

.shield-core {
  display: grid;
  z-index: 2;
  width: 68px;
  height: 78px;
  place-content: center;
  border: 1px solid var(--green);
  color: var(--green);
  background: rgba(65, 214, 163, .06);
  clip-path: polygon(50% 0, 100% 15%, 91% 75%, 50% 100%, 9% 75%, 0 15%);
  font: 700 20px/1 var(--font-display);
  letter-spacing: .08em;
}

.orbit {
  position: absolute;
  inset: 20px;
  border: 1px dashed #285043;
  border-radius: 50%;
  animation: orbit 20s linear infinite;
}

.orbit-b {
  inset: 0;
  border-color: #d2dad5;
  animation-direction: reverse;
  animation-duration: 30s;
}

@keyframes orbit {
  to { transform: rotate(360deg); }
}

.node {
  position: absolute;
  width: 6px;
  height: 6px;
  border: 1px solid var(--green);
  background: var(--green-deep);
  transform: rotate(45deg);
}

.node-1 { top: 17px; left: 92px; }
.node-2 { right: 17px; top: 92px; }
.node-3 { bottom: 17px; left: 92px; }
.node-4 { top: 92px; left: 17px; }

.posture-list {
  border-top: 1px solid var(--line);
}

.posture-info {
  min-width: 0;
}

.posture-list div {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 15px;
  border-bottom: 1px solid var(--line);
}

.posture-list span,
.posture-list strong {
  font:  14px/1 var(--font-mono);
}

.posture-list span {
  color: var(--text-dim);
}

.posture-list strong {
  color: var(--text-soft);
  font-weight: 600;
  text-align: right;
}

.runtime-disclaimer {
  margin: 13px 0 0;
  padding: 10px;
  border-left: 2px solid var(--amber);
  color: #9d906f;
  background: rgba(245, 185, 76, .04);
  font-size: 14px;
  line-height: 1.55;
}

.session-body {
  padding: 28px;
  text-align: center;
}

.operator-avatar {
  display: grid;
  width: 72px;
  height: 72px;
  margin: 0 auto 15px;
  place-content: center;
  border: 1px solid var(--line-strong);
  color: var(--text);
  background: #fffefa;
  font: 650 28px/1 var(--font-display);
  transform: rotate(45deg);
}

.operator-avatar::first-letter {
  transform: rotate(-45deg);
}

.session-body h2 {
  margin: 0;
  color: var(--text);
  font: 650 18px/1.2 var(--font-display);
}

.session-body > p {
  color: var(--green);
  font:  14px/1 var(--font-mono);
  letter-spacing: .09em;
}

.session-body dl {
  margin: 23px 0 16px;
  border-top: 1px solid var(--line);
}

.session-body dl div {
  display: flex;
  min-height: 41px;
  align-items: center;
  justify-content: space-between;
  border-bottom: 1px solid var(--line);
}

.session-body dt,
.session-body dd {
  font:  14px/1.3 var(--font-mono);
}

.session-body dt { color: var(--text-dim); }
.session-body dd { margin: 0; color: var(--text-soft); }
.session-body .el-button { width: 100%; }

.policy-panel {
  margin-bottom: 12px;
}

.approval-policy-panel {
  margin-bottom: 12px;
}

.recovery-policy-panel {
  margin-bottom: 12px;
}

.approval-off-label {
  color: var(--red);
}

.approval-policy-body {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 24px;
  padding: 22px;
}

.approval-policy-copy {
  min-width: 0;
}

.approval-policy-status {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--text);
  font: 650 15px/1.3 var(--font-display);
}

.approval-policy-status .status-dot {
  flex: 0 0 auto;
}

.status-dot-warning {
  background: var(--red);
  box-shadow: 0 0 0 4px rgba(198, 73, 66, .1);
}

.approval-policy-copy p {
  max-width: 860px;
  margin: 10px 0 5px;
  color: var(--text-dim);
  font-size: 14px;
  line-height: 1.65;
}

.approval-policy-copy small,
.approval-policy-control span {
  color: var(--text-dim);
  font: 13px/1.5 var(--font-mono);
}

.approval-policy-control {
  display: grid;
  min-width: 170px;
  justify-items: end;
  gap: 10px;
}

.approval-policy-control .el-switch {
  --el-switch-on-color: var(--red);
}

.approval-policy-control > span {
  text-align: right;
}

.recovery-policy-body {
  display: grid;
  grid-template-columns: 1fr auto;
  align-items: center;
  gap: 24px;
  padding: 22px;
}

.recovery-policy-copy {
  min-width: 0;
}

.recovery-policy-copy p {
  max-width: 860px;
  margin: 10px 0 5px;
  color: var(--text-dim);
  font-size: 14px;
  line-height: 1.65;
}

.recovery-policy-copy small,
.recovery-policy-control span {
  color: var(--text-dim);
  font: 13px/1.5 var(--font-mono);
}

.recovery-policy-control {
  display: grid;
  min-width: 170px;
  justify-items: end;
  gap: 10px;
}

.recovery-policy-control .el-switch {
  --el-switch-on-color: var(--green);
}

.recovery-policy-control > span {
  text-align: right;
}

.fixed-label {
  color: var(--amber);
  font:  14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.policy-list {
  display: grid;
  grid-template-columns: 1fr 1fr;
}

.policy-list article {
  display: grid;
  min-height: 125px;
  grid-template-columns: 54px 1fr auto;
  align-items: center;
  gap: 15px;
  padding: 20px;
  border-right: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
}

.policy-list article:nth-child(2n) { border-right: 0; }
.policy-list article:nth-last-child(-n + 2) { border-bottom: 0; }

.policy-code {
  display: grid;
  width: 45px;
  height: 45px;
  place-content: center;
  border: 1px solid var(--green-deep);
  color: var(--green);
  font:  14px/1 var(--font-mono);
}

.policy-list h3 {
  margin: 0 0 6px;
  color: var(--text);
  font: 650 14px/1.2 var(--font-display);
}

.policy-list p {
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
  line-height: 1.55;
}

.policy-list article > strong {
  align-self: start;
  color: var(--green);
  font:  14px/1 var(--font-mono);
  white-space: nowrap;
}

.integrity-body {
  display: grid;
  grid-template-columns: 310px 1fr auto;
  align-items: center;
  gap: 24px;
  padding: 22px;
}

.checksum {
  display: flex;
  justify-content: space-between;
  padding: 13px;
  border: 1px solid var(--line);
  color: var(--green);
  background: #f2f4ef;
  font:  14px/1 var(--font-mono);
}

.integrity-body h3 {
  margin: 0 0 5px;
  color: var(--text);
  font: 650 14px/1.2 var(--font-display);
}

.integrity-body p {
  margin: 0;
  color: var(--text-dim);
  font-size: 13px;
  line-height: 1.6;
}

@media (max-width: 1050px) {
  .security-grid {
    grid-template-columns: 1fr;
  }

  .integrity-body {
    grid-template-columns: 1fr;
  }

  .approval-policy-body {
    grid-template-columns: 1fr;
  }

  .recovery-policy-body {
    grid-template-columns: 1fr;
  }

  .approval-policy-control {
    justify-items: start;
  }

  .approval-policy-control > span {
    text-align: left;
  }

  .recovery-policy-control {
    justify-items: start;
  }

  .recovery-policy-control > span {
    text-align: left;
  }
}

@media (max-width: 680px) {
  .posture-body {
    grid-template-columns: 1fr;
    gap: 20px;
  }

  .policy-list {
    grid-template-columns: 1fr;
  }

  .policy-list article {
    border-right: 0;
    border-bottom: 1px solid var(--line) !important;
  }

  .policy-list article:last-child {
    border-bottom: 0 !important;
  }
}

.posture-panel,
.session-panel,
.policy-panel,
.approval-policy-panel,
.recovery-policy-panel,
.integrity-panel {
  border-radius: var(--radius);
  box-shadow: 0 1px 2px rgba(15, 23, 42, .03);
}

.shield-core {
  border-radius: 12px;
  border-color: #93c5fd;
  background: #eff6ff;
  clip-path: none;
}

.orbit {
  border-color: #bfdbfe;
  animation: none;
}

.operator-avatar {
  border-radius: 50%;
  border-color: #bfdbfe;
  color: #1d4ed8;
  background: #eff6ff;
  transform: none;
}

.policy-code {
  border-radius: 8px;
  border-color: #bfdbfe;
  color: var(--blue);
  background: #eff6ff;
}

.checksum {
  border-radius: 6px;
  background: #f8fafc;
}
</style>
