<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '../api/client'
import { useAuthStore } from '../stores/auth'
import NavIcon from '../components/NavIcon.vue'
import { useTheme } from '../theme'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { theme, toggleTheme } = useTheme()
const clock = ref(new Date())
const mobileOpen = ref(false)
const pendingApprovalCount = ref<number | null>(null)
let clockTimer: number | undefined
let approvalTimer: number | undefined

const APPROVAL_REFRESH_INTERVAL = 15_000

const navigationGroups = [
  {
    label: '工作台',
    items: [
      { to: '/dashboard', code: '01', label: '总览', icon: 'home' },
      { to: '/workbench', code: '03', label: 'SQL 工作台', icon: 'terminal' }
    ]
  },
  {
    label: '资源管理',
    items: [
      { to: '/datasources', code: '02', label: '数据源', icon: 'database' },
      { to: '/tokens', code: '06', label: '访问令牌', icon: 'key' }
    ]
  },
  {
    label: '安全与审计',
    items: [
      { to: '/approvals', code: '04', label: '待审批', icon: 'check' },
      { to: '/audits', code: '05', label: '审计轨迹', icon: 'audit' },
      { to: '/security', code: '07', label: '安全设置', icon: 'shield' }
    ]
  }
]

const navigation = navigationGroups.flatMap((group) => group.items)

const activeLabel = computed(() => navigation.find((item) => route.path.startsWith(item.to))?.label ?? '页面')
const timeText = computed(() => new Intl.DateTimeFormat('zh-CN', {
  hour12: false,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
}).format(clock.value))

const pendingApprovalLabel = computed(() => {
  const count = pendingApprovalCount.value ?? 0
  return count > 99 ? '99+' : String(count)
})

async function refreshPendingApprovalCount() {
  if (!auth.authenticated) return
  try {
    const dashboard = await api.dashboard()
    pendingApprovalCount.value = dashboard.pendingApprovalCount
  } catch {
    // 全局提示不能阻塞当前页面；下一次轮询继续尝试，401 仍由 API 客户端统一处理。
    pendingApprovalCount.value = null
  }
}

async function logout() {
  await auth.logout()
  await router.replace({ name: 'login' })
}

onMounted(() => {
  clockTimer = window.setInterval(() => { clock.value = new Date() }, 1000)
  void refreshPendingApprovalCount()
  approvalTimer = window.setInterval(() => {
    void refreshPendingApprovalCount()
  }, APPROVAL_REFRESH_INTERVAL)
})

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
  if (approvalTimer) window.clearInterval(approvalTimer)
})

watch(() => route.path, (path) => {
  if (path.startsWith('/approvals')) void refreshPendingApprovalCount()
})
</script>

<template>
  <div class="shell">
    <button class="mobile-trigger" type="button" aria-label="打开导航" @click="mobileOpen = !mobileOpen">
      <span /><span />
    </button>

    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand">
        <div class="brand-symbol" aria-hidden="true"><NavIcon name="database" /></div>
        <div>
          <strong>查询网关</strong>
          <small>只读数据控制台</small>
        </div>
      </div>

      <nav class="navigation" aria-label="主导航">
        <section v-for="group in navigationGroups" :key="group.label" class="nav-group">
          <h2>{{ group.label }}</h2>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            :class="{ 'approval-nav-link': item.to === '/approvals' && pendingApprovalCount !== null && pendingApprovalCount > 0 }"
            @click="mobileOpen = false"
          >
            <NavIcon :name="item.icon" />
            <span class="nav-label">{{ item.label }}</span>
            <span
              v-if="item.to === '/approvals' && pendingApprovalCount !== null && pendingApprovalCount > 0"
              class="nav-badge"
              :aria-label="`${pendingApprovalCount} 条待审批查询`"
            >
              {{ pendingApprovalLabel }}
            </span>
          </RouterLink>
        </section>
      </nav>

      <footer class="sidebar-footer">
        <div class="local-indicator"><span class="status-dot" />服务在线 · 仅限本机</div>
        <button class="account-row" type="button" @click="logout">
          <span class="account-avatar">{{ (auth.username || 'A').slice(0, 1).toUpperCase() }}</span>
          <span class="account-copy">
            <strong>{{ auth.username }}</strong>
            <small>结束当前会话</small>
          </span>
          <span class="account-action">↗</span>
        </button>
      </footer>
    </aside>

    <div v-if="mobileOpen" class="nav-scrim" @click="mobileOpen = false" />

    <section class="main-frame">
      <header class="topbar">
        <div class="breadcrumb">
          <span>工作区</span>
          <i>/</i>
          <strong>{{ activeLabel }}</strong>
        </div>
        <div class="topbar-tools">
          <RouterLink
            v-if="pendingApprovalCount !== null && pendingApprovalCount > 0"
            class="approval-alert"
            to="/approvals"
            aria-live="polite"
            :aria-label="`${pendingApprovalCount} 条待审批查询，点击查看`"
          >
            <span class="approval-alert-mark"><i /></span>
            <strong>{{ pendingApprovalLabel }}</strong>
            <span>条待审批查询</span>
            <small>立即处理 →</small>
          </RouterLink>
          <button
            class="theme-toggle"
            type="button"
            :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
            :title="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
            @click="toggleTheme"
          >
            <NavIcon :name="theme === 'dark' ? 'sun' : 'moon'" />
            <span>{{ theme === 'dark' ? '浅色' : '深色' }}</span>
          </button>
          <div class="telemetry">
            <span class="service-pill"><i class="live-dot" />服务在线</span>
            <time>{{ timeText }}</time>
          </div>
        </div>
      </header>

      <main class="content">
        <RouterView v-slot="{ Component }">
          <Transition name="page" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </section>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
}

.sidebar {
  position: fixed;
  z-index: 20;
  top: 0;
  bottom: 0;
  left: 0;
  display: flex;
  width: 256px;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: var(--bg-panel);
}

.brand {
  display: flex;
  min-height: 72px;
  align-items: center;
  gap: 11px;
  padding: 0 18px;
  border-bottom: 1px solid var(--line);
}

.brand-symbol {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid var(--blue);
  border-radius: 8px;
  color: var(--blue);
  background: var(--blue-deep);
}

.brand strong,
.brand small {
  display: block;
}

.brand strong {
  color: var(--text);
  font: 700 15px/1.2 var(--font-display);
  letter-spacing: 0;
}

.brand small {
  margin-top: 3px;
  color: var(--text-dim);
  font: 12px/1.3 var(--font-display);
}

.navigation {
  display: grid;
  gap: 22px;
  padding: 22px 12px 20px;
}

.nav-group {
  display: grid;
  gap: 5px;
}

.nav-group h2 {
  margin: 0 10px 3px;
  color: #94a3b8;
  font: 600 11px/1.2 var(--font-display);
  letter-spacing: .04em;
}

.navigation a {
  display: grid;
  min-height: 38px;
  grid-template-columns: 20px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  padding: 0 10px;
  border-radius: 6px;
  color: #64748b;
  font: 500 14px/1 var(--font-display);
  text-decoration: none;
  transition: color .18s ease, background .18s ease, box-shadow .18s ease;
}

.navigation a:hover {
  color: #334155;
  background: var(--bg-panel-2);
}

.navigation a.router-link-active {
  color: var(--blue);
  background: var(--blue-deep);
  box-shadow: inset 0 0 0 1px var(--line);
  font-weight: 600;
}

.nav-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-badge {
  display: inline-grid;
  min-width: 22px;
  height: 20px;
  align-items: center;
  justify-content: center;
  margin-left: auto;
  padding: 0 6px;
  border: 1px solid var(--amber);
  border-radius: 999px;
  color: var(--amber);
  background: var(--amber-deep);
  font: 600 11px/1 var(--font-display);
  animation: approval-badge-pulse 2.8s ease-in-out infinite;
}

.navigation a.approval-nav-link {
  color: var(--amber);
}

.navigation a.approval-nav-link:hover {
  color: var(--amber);
  background: var(--amber-deep);
}

.navigation a :deep(.nav-icon) {
  width: 17px;
  height: 17px;
}

.sidebar-footer {
  margin-top: auto;
  padding: 14px 12px 16px;
  border-top: 1px solid var(--line);
}

.local-indicator {
  margin: 0 8px 12px;
  color: var(--green);
  font: 12px/1.3 var(--font-display);
}

.account-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 9px;
  padding: 9px 8px;
  border: 0;
  border-radius: 7px;
  color: var(--text-soft);
  background: var(--bg-panel-2);
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}

.account-row:hover {
  background: var(--bg-deep);
}

.account-avatar {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border-radius: 50%;
  color: var(--blue);
  background: var(--blue-deep);
  font: 700 12px/1 var(--font-display);
}

.account-copy {
  display: grid;
  min-width: 0;
  gap: 2px;
}

.account-copy strong,
.account-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account-copy strong {
  color: var(--text);
  font-size: 12px;
  font-weight: 600;
}

.account-copy small {
  color: var(--text-dim);
  font-size: 11px;
}

.account-action {
  margin-left: auto;
  color: #94a3b8;
  font-size: 14px;
}

.main-frame {
  min-height: 100vh;
  margin-left: 256px;
}

.topbar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  height: 64px;
  align-items: center;
  justify-content: space-between;
  padding: 0 30px;
  border-bottom: 1px solid var(--line);
  background: var(--topbar-bg);
  backdrop-filter: blur(14px);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 9px;
  color: #94a3b8;
  font: 13px/1 var(--font-display);
}

.breadcrumb i {
  color: #cbd5e1;
  font-style: normal;
}

.breadcrumb strong {
  color: var(--text);
  font-weight: 600;
}

.topbar-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}

.approval-alert {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  padding: 0 10px;
  border: 1px solid var(--amber);
  border-radius: 6px;
  color: var(--amber);
  background: var(--amber-deep);
  font: 12px/1 var(--font-display);
  text-decoration: none;
  transition: border-color .18s ease, background .18s ease, transform .18s ease;
}

.approval-alert:hover {
  border-color: var(--amber);
  background: var(--amber-deep);
  transform: translateY(-1px);
}

.approval-alert-mark {
  display: inline-grid;
  width: 16px;
  height: 16px;
  place-items: center;
  border: 1px solid var(--amber);
  border-radius: 50%;
}

.approval-alert-mark i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--amber);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--amber) 14%, transparent);
  animation: approval-dot-pulse 1.8s ease-in-out infinite;
}

.approval-alert strong {
  font-size: 14px;
}

.approval-alert small {
  margin-left: 4px;
  color: var(--amber);
  font-size: 12px;
}

.telemetry {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-left: 0;
  color: var(--text-dim);
  font: 12px/1 var(--font-display);
}

.service-pill {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  padding: 0 9px;
  border: 1px solid var(--green-deep);
  border-radius: 999px;
  color: var(--green);
  background: color-mix(in srgb, var(--green-deep) 34%, var(--bg-panel));
}

.theme-toggle {
  display: inline-flex;
  min-height: 32px;
  align-items: center;
  gap: 7px;
  padding: 0 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--text-soft);
  background: var(--bg-panel);
  cursor: pointer;
  font: 500 12px/1 var(--font-display);
  transition: border-color .18s ease, color .18s ease, background .18s ease;
}

.theme-toggle:hover {
  border-color: var(--line-strong);
  color: var(--text);
  background: var(--bg-panel-2);
}

.theme-toggle :deep(.nav-icon) {
  width: 15px;
  height: 15px;
  color: var(--text-dim);
}

.live-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--green);
}

.content {
  width: min(1440px, 100%);
  margin: 0 auto;
  padding: 32px clamp(22px, 3vw, 42px) 64px;
}

@keyframes approval-badge-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 3px rgba(196, 122, 32, .08);
  }
  50% {
    box-shadow: 0 0 0 5px rgba(196, 122, 32, .14);
  }
}

@keyframes approval-dot-pulse {
  0%,
  100% {
    opacity: .72;
    transform: scale(.82);
  }
  50% {
    opacity: 1;
    transform: scale(1.15);
  }
}

.page-enter-active,
.page-leave-active {
  transition: opacity .18s ease, transform .18s ease;
}

.page-enter-from {
  opacity: 0;
  transform: translateY(5px);
}

.page-leave-to {
  opacity: 0;
  transform: translateY(-3px);
}

.mobile-trigger,
.nav-scrim {
  display: none;
}

@media (max-width: 900px) {
  .sidebar {
    width: 256px;
    transform: translateX(-100%);
    transition: transform .22s ease;
  }

  .sidebar.open {
    transform: translateX(0);
  }

  .nav-scrim {
    position: fixed;
    z-index: 15;
    inset: 0;
    display: block;
    background: rgba(15, 23, 42, .35);
    backdrop-filter: blur(3px);
  }

  .mobile-trigger {
    position: fixed;
    z-index: 30;
    top: 16px;
    left: 17px;
    display: grid;
    width: 32px;
    height: 32px;
    place-content: center;
    gap: 5px;
    border: 1px solid var(--line);
    border-radius: 6px;
    background: #fff;
  }

  .mobile-trigger span {
    display: block;
    width: 13px;
    height: 1px;
    background: var(--text);
  }

  .main-frame {
    margin-left: 0;
  }

  .topbar {
    padding-right: 18px;
    padding-left: 64px;
  }

  .approval-alert small {
    display: none;
  }

}

@media (max-width: 560px) {
  .content {
    padding: 27px 14px 48px;
  }

  .telemetry span:first-child {
    display: none;
  }

  .breadcrumb > span {
    display: none;
  }

  .approval-alert {
    padding-right: 8px;
    padding-left: 8px;
  }

  .theme-toggle span {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .nav-badge,
  .approval-alert-mark i {
    animation: none;
  }

  .approval-alert {
    transition: none;
  }
}
</style>
