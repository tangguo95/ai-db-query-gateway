<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const clock = ref(new Date())
const mobileOpen = ref(false)
let clockTimer: number | undefined

const navigation = [
  { to: '/dashboard', code: '01', label: '总览', short: '总览' },
  { to: '/datasources', code: '02', label: '数据源', short: '数据源' },
  { to: '/workbench', code: '03', label: 'SQL 工作台', short: '工作台' },
  { to: '/approvals', code: '04', label: '待审批', short: '审批' },
  { to: '/audits', code: '05', label: '审计轨迹', short: '审计' },
  { to: '/tokens', code: '06', label: '访问令牌', short: '令牌' },
  { to: '/security', code: '07', label: '安全设置', short: '安全' }
]

const activeCode = computed(() => navigation.find((item) => route.path.startsWith(item.to))?.code ?? '00')
const timeText = computed(() => new Intl.DateTimeFormat('zh-CN', {
  hour12: false,
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit'
}).format(clock.value))

async function logout() {
  await auth.logout()
  await router.replace({ name: 'login' })
}

onMounted(() => {
  clockTimer = window.setInterval(() => { clock.value = new Date() }, 1000)
})

onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>

<template>
  <div class="shell">
    <button class="mobile-trigger" type="button" aria-label="打开导航" @click="mobileOpen = !mobileOpen">
      <span /><span />
    </button>

    <aside class="sidebar" :class="{ open: mobileOpen }">
      <div class="brand">
        <div class="brand-symbol"><span>查</span><span>询</span></div>
        <div>
          <strong>只读查询网关</strong>
          <small>生产数据查询控制台</small>
        </div>
      </div>

      <nav class="navigation" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.to"
          :to="item.to"
          @click="mobileOpen = false"
        >
          <span class="nav-code">{{ item.code }}</span>
          <span>{{ item.label }}</span>
          <i />
        </RouterLink>
      </nav>

      <footer class="sidebar-footer">
        <div class="local-indicator"><span class="status-dot" />仅限本机访问</div>
        <button type="button" @click="logout">
          <span>{{ auth.username }}</span>
          <small>结束会话 →</small>
        </button>
      </footer>
    </aside>

    <div v-if="mobileOpen" class="nav-scrim" @click="mobileOpen = false" />

    <section class="main-frame">
      <header class="topbar">
        <div class="breadcrumb">
          <span>只读查询网关 / 系统</span>
          <strong>{{ activeCode }}</strong>
        </div>
        <div class="telemetry">
          <span><i class="live-dot" />安全策略正常</span>
          <span>本机服务 / 运行正常</span>
          <time>{{ timeText }} 中国标准时间</time>
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
  width: 252px;
  flex-direction: column;
  border-right: 1px solid var(--line);
  background: rgba(255, 255, 255, .98);
  box-shadow: 4px 0 14px rgba(26, 48, 40, .035);
}

.brand {
  display: flex;
  min-height: 112px;
  align-items: center;
  gap: 13px;
  padding: 0 20px;
  border-bottom: 1px solid var(--line);
}

.brand-symbol {
  display: grid;
  width: 36px;
  height: 36px;
  grid-template-columns: 1fr 1fr;
  border: 1px solid var(--green);
  color: var(--green);
  font: 700 13px/36px var(--font-mono);
  text-align: center;
}

.brand-symbol span:first-child {
  border-right: 1px solid var(--green);
}

.brand strong,
.brand small {
  display: block;
}

.brand strong {
  color: var(--text);
  font: 700 17px/1.2 var(--font-display);
  letter-spacing: .04em;
}

.brand small {
  margin-top: 5px;
  color: var(--text-dim);
  font:  14px/1.3 var(--font-mono);
  letter-spacing: .03em;
}

.navigation {
  display: grid;
  gap: 2px;
  padding: 22px 10px 20px;
}

.navigation a {
  position: relative;
  display: grid;
  min-height: 45px;
  grid-template-columns: 31px 1fr 10px;
  align-items: center;
  color: #53645e;
  font: 600 15px/1 var(--font-display);
  letter-spacing: .025em;
  text-decoration: none;
  transition: color .18s ease, background .18s ease;
}

.navigation a:hover {
  color: var(--text);
  background: #edf4f0;
}

.navigation a.router-link-active {
  color: #075f50;
  background: #e8f2ee;
}

.navigation a.router-link-active::before {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 2px;
  content: "";
  background: var(--green);
}

.nav-code {
  color: #7b8983;
  font:  14px/1 var(--font-mono);
  text-align: center;
}

.navigation a i {
  width: 4px;
  height: 4px;
  border: 1px solid #48534f;
  transform: rotate(45deg);
}

.navigation a.router-link-active i {
  border-color: var(--green);
  background: var(--green);
}

.sidebar-footer {
  margin-top: auto;
  padding: 18px;
  border-top: 1px solid var(--line);
}

.local-indicator {
  margin-bottom: 14px;
  color: var(--green);
  font:  14px/1 var(--font-mono);
  letter-spacing: .06em;
}

.sidebar-footer button {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: space-between;
  padding: 0;
  border: 0;
  color: var(--text-soft);
  background: transparent;
  cursor: pointer;
  text-align: left;
}

.sidebar-footer button span {
  max-width: 105px;
  overflow: hidden;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-footer button small {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
}

.main-frame {
  min-height: 100vh;
  margin-left: 252px;
}

.topbar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  height: 58px;
  align-items: center;
  justify-content: space-between;
  padding: 0 28px;
  border-bottom: 1px solid var(--line);
  background: rgba(247, 249, 248, .94);
  backdrop-filter: blur(10px);
}

.breadcrumb {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .05em;
}

.breadcrumb strong {
  padding: 4px 6px;
  color: var(--green);
  border: 1px solid var(--green-deep);
  font-weight: 600;
}

.telemetry {
  display: flex;
  gap: 22px;
  color: #607169;
  font:  14px/1 var(--font-mono);
  letter-spacing: .04em;
}

.live-dot {
  display: inline-block;
  width: 5px;
  height: 5px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--green);
}

.content {
  width: min(1460px, 100%);
  margin: 0 auto;
  padding: 36px clamp(20px, 3vw, 44px) 64px;
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
    width: 252px;
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
    background: rgba(31, 44, 40, .38);
    backdrop-filter: blur(3px);
  }

  .mobile-trigger {
    position: fixed;
    z-index: 30;
    top: 16px;
    left: 17px;
    display: grid;
    width: 28px;
    height: 28px;
    place-content: center;
    gap: 5px;
    border: 1px solid var(--line-strong);
    background: #fffdf7;
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
    padding-left: 60px;
  }

  .telemetry span:nth-child(2) {
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
}
</style>
