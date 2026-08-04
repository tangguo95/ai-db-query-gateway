<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, errorMessage } from '../api/client'
import type { AdminProfile } from '../api/types'
import { useAuthStore } from '../stores/auth'
import NavIcon from '../components/NavIcon.vue'
import { useTheme } from '../theme'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const { theme, toggleTheme } = useTheme()
const clock = ref(new Date())
const mobileOpen = ref(false)
const pendingApprovalCount = ref<number | null>(null)
const profile = ref<AdminProfile | null>(null)
const profileLoaded = ref(false)
const profileOpen = ref(false)
const profileSaving = ref(false)
const passwordSaving = ref(false)
const avatarInput = ref<HTMLInputElement | null>(null)
const profileForm = reactive({ displayName: '', avatarDataUrl: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmPassword: '' })
let clockTimer: number | undefined
let approvalTimer: number | undefined

const APPROVAL_REFRESH_INTERVAL = 15_000
const MAX_AVATAR_BYTES = 5 * 1024 * 1024

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

const profileName = computed(() => profile.value?.displayName || auth.username || '本地管理员')
const profileInitial = computed(() => Array.from(profileName.value.trim())[0] || '管')

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

async function loadProfile() {
  try {
    profile.value = await api.profile()
  } catch {
    // 用户设置不是工作区的阻塞依赖；接口暂时不可用时仍显示本地管理员占位信息。
    profile.value = {
      username: auth.username,
      displayName: auth.username,
      avatarDataUrl: undefined
    }
  } finally {
    profileLoaded.value = true
  }
}

async function openProfileSettings() {
  if (!profileLoaded.value) await loadProfile()
  profileForm.displayName = profileName.value
  profileForm.avatarDataUrl = profile.value?.avatarDataUrl || ''
  passwordForm.currentPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
  profileOpen.value = true
}

function resetProfileForms() {
  profileForm.displayName = ''
  profileForm.avatarDataUrl = ''
  passwordForm.currentPassword = ''
  passwordForm.newPassword = ''
  passwordForm.confirmPassword = ''
}

function chooseAvatar() {
  avatarInput.value?.click()
}

function onAvatarSelected(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return

  const acceptedTypes = new Set(['image/gif', 'image/png', 'image/jpeg', 'image/webp'])
  if (!acceptedTypes.has(file.type)) {
    ElMessage.error('头像仅支持 GIF、PNG、JPG 或 WebP 图片')
    return
  }
  if (file.size > MAX_AVATAR_BYTES) {
    ElMessage.error('头像文件不能超过 5 MiB')
    return
  }

  const reader = new FileReader()
  reader.onload = () => {
    if (typeof reader.result === 'string') {
      profileForm.avatarDataUrl = reader.result
    } else {
      ElMessage.error('头像读取失败，请重新选择')
    }
  }
  reader.onerror = () => ElMessage.error('头像读取失败，请重新选择')
  reader.readAsDataURL(file)
}

function clearAvatar() {
  profileForm.avatarDataUrl = ''
}

async function saveProfile() {
  const displayName = profileForm.displayName.trim()
  if (!displayName) {
    ElMessage.warning('请输入显示名称')
    return
  }
  if (displayName.length > 64) {
    ElMessage.warning('显示名称不能超过 64 个字符')
    return
  }
  profileSaving.value = true
  try {
    const updated = await api.updateProfile({
      displayName,
      avatarDataUrl: profileForm.avatarDataUrl || null
    })
    profile.value = updated
    if (auth.user) auth.user.displayName = updated.displayName
    ElMessage.success('用户信息已保存')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    profileSaving.value = false
  }
}

async function changePassword() {
  if (!passwordForm.currentPassword) {
    ElMessage.warning('请输入当前密码')
    return
  }
  if (passwordForm.newPassword.length < 12) {
    ElMessage.warning('新密码至少需要 12 个字符')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  passwordSaving.value = true
  try {
    await api.changePassword({ ...passwordForm })
    passwordForm.currentPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    ElMessage.success('管理员密码已修改，下次登录请使用新密码')
  } catch (error) {
    ElMessage.error(errorMessage(error))
  } finally {
    passwordSaving.value = false
  }
}

function handleProfileCommand(command: string) {
  if (command === 'settings') {
    openProfileSettings()
  } else if (command === 'security') {
    void router.push('/security')
  } else if (command === 'logout') {
    void logout()
  }
}

async function logout() {
  try {
    await auth.logout()
  } finally {
    profile.value = null
    await router.replace({ name: 'login' })
  }
}

onMounted(() => {
  clockTimer = window.setInterval(() => { clock.value = new Date() }, 1000)
  void loadProfile()
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

      <div class="profile-slot">
        <el-dropdown trigger="click" placement="bottom-start" popper-class="profile-dropdown" @command="handleProfileCommand">
          <button class="profile-trigger" type="button" aria-label="打开用户菜单">
            <span class="profile-avatar">
              <img v-if="profile?.avatarDataUrl" :src="profile.avatarDataUrl" alt="" />
              <span v-else>{{ profileInitial }}</span>
            </span>
            <span class="profile-copy">
              <strong>{{ profileName }}</strong>
              <small>管理员账户</small>
            </span>
            <span class="profile-menu-hint">
              <span>用户设置</span>
              <span class="profile-chevron" aria-hidden="true" />
            </span>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="settings">用户设置</el-dropdown-item>
              <el-dropdown-item command="security">安全设置</el-dropdown-item>
              <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
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

    <el-dialog
      v-model="profileOpen"
      class="profile-dialog"
      title="用户设置"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetProfileForms"
    >
      <div class="profile-dialog-body">
        <section class="profile-section profile-overview">
          <div class="profile-section-heading">
            <div>
              <h2>基本信息</h2>
              <p>设置工作区中显示的名称和头像。</p>
            </div>
            <span class="profile-identity">{{ profile?.username || 'admin' }}</span>
          </div>

          <div class="avatar-editor">
            <span class="profile-avatar profile-avatar-large">
              <img v-if="profileForm.avatarDataUrl" :src="profileForm.avatarDataUrl" alt="头像预览" />
              <span v-else>{{ profileInitial }}</span>
            </span>
            <div class="avatar-editor-copy">
              <div class="avatar-actions">
                <input
                  ref="avatarInput"
                  class="avatar-input"
                  type="file"
                  accept="image/gif,image/png,image/jpeg,image/webp"
                  @change="onAvatarSelected"
                />
                <button class="dialog-button dialog-button-secondary" type="button" @click="chooseAvatar">选择头像</button>
                <button v-if="profileForm.avatarDataUrl" class="dialog-link-button" type="button" @click="clearAvatar">移除头像</button>
              </div>
              <p>支持 GIF、PNG、JPG、WebP，最大 5 MiB。GIF 会保留动画效果。</p>
            </div>
          </div>

          <label class="dialog-field">
            <span>显示名称</span>
            <input v-model="profileForm.displayName" maxlength="64" type="text" autocomplete="nickname" placeholder="例如：本地管理员" />
          </label>
          <div class="dialog-actions">
            <button class="dialog-button dialog-button-primary" type="button" :disabled="profileSaving" @click="saveProfile">
              {{ profileSaving ? '保存中…' : '保存基本信息' }}
            </button>
          </div>
        </section>

        <section class="profile-section">
          <div class="profile-section-heading">
            <div>
              <h2>修改管理员密码</h2>
              <p>密码只用于本地网页登录，不会展示或写入普通日志。</p>
            </div>
          </div>
          <div class="password-grid">
            <label class="dialog-field password-field-wide">
              <span>当前密码</span>
              <input v-model="passwordForm.currentPassword" type="password" autocomplete="current-password" />
            </label>
            <label class="dialog-field">
              <span>新密码</span>
              <input v-model="passwordForm.newPassword" type="password" minlength="12" autocomplete="new-password" />
            </label>
            <label class="dialog-field">
              <span>确认新密码</span>
              <input v-model="passwordForm.confirmPassword" type="password" minlength="12" autocomplete="new-password" />
            </label>
          </div>
          <div class="dialog-actions password-actions">
            <button class="dialog-button dialog-button-primary" type="button" :disabled="passwordSaving" @click="changePassword">
              {{ passwordSaving ? '修改中…' : '修改密码' }}
            </button>
          </div>
        </section>

        <section class="profile-section profile-more-settings">
          <div>
            <h2>其他设置</h2>
            <p>主题切换在页面右上角；查询审批、自动重试和访问令牌等选项集中在安全设置中。</p>
          </div>
          <RouterLink class="settings-link" to="/security" @click="profileOpen = false">打开安全设置 →</RouterLink>
        </section>
      </div>
    </el-dialog>
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

.profile-slot {
  padding: 12px 12px 0;
}

.profile-slot :deep(.el-dropdown) {
  display: block;
  width: 100%;
}

.profile-trigger {
  display: grid;
  width: 100%;
  min-height: 316px;
  justify-items: center;
  gap: 13px;
  padding: 21px 10px 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  color: var(--text-soft);
  background: var(--bg-panel-2);
  cursor: pointer;
  text-align: left;
  transition: border-color .18s ease, background .18s ease, transform .18s ease;
}

.profile-trigger:hover,
.profile-trigger:focus-visible {
  border-color: var(--line-strong);
  background: var(--bg-deep);
  outline: none;
  transform: translateY(-1px);
}

.profile-avatar {
  display: grid;
  width: 200px;
  height: 200px;
  flex: 0 0 200px;
  place-items: center;
  overflow: hidden;
  border: 4px solid color-mix(in srgb, var(--blue) 60%, var(--line));
  border-radius: 50%;
  color: var(--blue);
  background: var(--blue-deep);
  box-shadow: 0 10px 26px color-mix(in srgb, var(--blue) 18%, transparent);
  font: 700 58px/1 var(--font-display);
}

.profile-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.profile-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
  max-width: 100%;
  justify-items: center;
  text-align: center;
}

.profile-copy strong,
.profile-copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-copy strong {
  color: var(--text);
  font-size: 14px;
  font-weight: 650;
}

.profile-copy small {
  color: var(--text-dim);
  font-size: 11px;
}

.profile-menu-hint {
  display: inline-flex;
  min-height: 27px;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  border-top: 1px solid var(--line);
  color: var(--text-dim);
  font: 600 11px/1 var(--font-display);
}

.profile-menu-hint:hover {
  color: var(--blue);
}

.profile-chevron {
  width: 9px;
  height: 9px;
  margin-top: -4px;
  border-right: 2px solid currentColor;
  border-bottom: 2px solid currentColor;
  color: var(--text-dim);
  transform: rotate(45deg);
  transition: border-color .18s ease, color .18s ease, transform .18s ease;
}

.profile-menu-hint:hover .profile-chevron {
  color: var(--blue);
}

.navigation {
  display: grid;
  min-height: 0;
  gap: 22px;
  overflow-y: auto;
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
  margin: 0 8px;
  color: var(--green);
  font: 12px/1.3 var(--font-display);
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

:global(.profile-dropdown.el-dropdown-menu) {
  min-width: 178px;
  padding: 7px;
  border: 1px solid var(--line-strong);
  border-radius: 9px;
  background: var(--bg-panel);
  box-shadow: var(--shadow-card);
}

:global(.profile-dropdown .el-dropdown-menu__item) {
  min-height: 34px;
  border-radius: 6px;
  color: var(--text-soft);
  font: 13px/1 var(--font-display);
}

:global(.profile-dropdown .el-dropdown-menu__item:not(.is-disabled):hover) {
  color: var(--text);
  background: var(--bg-panel-2);
}

:global(.profile-dropdown .el-dropdown-menu__item--divided) {
  border-top-color: var(--line);
}

:global(.profile-dialog.el-dialog) {
  max-width: calc(100vw - 32px);
  overflow: hidden;
  border: 1px solid var(--line-strong);
  border-radius: 12px;
  background: var(--bg-panel);
  box-shadow: var(--shadow-card);
}

:global(.profile-dialog .el-dialog__header) {
  margin-right: 0;
  padding: 22px 24px 16px;
  border-bottom: 1px solid var(--line);
}

:global(.profile-dialog .el-dialog__title) {
  color: var(--text);
  font: 700 18px/1.2 var(--font-display);
}

:global(.profile-dialog .el-dialog__headerbtn) {
  top: 17px;
  right: 18px;
}

:global(.profile-dialog .el-dialog__headerbtn .el-dialog__close) {
  color: var(--text-dim);
}

:global(.profile-dialog .el-dialog__body) {
  max-height: calc(100vh - 150px);
  overflow-y: auto;
  padding: 0;
  color: var(--text-soft);
}

.profile-dialog-body {
  display: grid;
}

.profile-section {
  padding: 22px 24px;
  border-bottom: 1px solid var(--line);
}

.profile-section:last-child {
  border-bottom: 0;
}

.profile-section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.profile-section h2 {
  margin: 0;
  color: var(--text);
  font: 650 14px/1.3 var(--font-display);
}

.profile-section p {
  margin: 6px 0 0;
  color: var(--text-dim);
  font-size: 12px;
  line-height: 1.55;
}

.profile-identity {
  flex: 0 0 auto;
  padding: 5px 8px;
  border: 1px solid var(--line);
  border-radius: 5px;
  color: var(--text-dim);
  background: var(--bg-panel-2);
  font: 11px/1 var(--font-mono);
}

.avatar-editor {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-top: 20px;
  padding: 13px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--bg-panel-2);
}

.profile-avatar-large {
  width: 64px;
  height: 64px;
  flex-basis: 64px;
  border-width: 2px;
  box-shadow: none;
  font-size: 23px;
}

.avatar-editor-copy {
  min-width: 0;
}

.avatar-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.avatar-input {
  display: none;
}

.dialog-field {
  display: grid;
  gap: 7px;
  margin-top: 16px;
}

.dialog-field > span {
  color: var(--text-soft);
  font-size: 12px;
  font-weight: 600;
}

.dialog-field input {
  width: 100%;
  min-height: 38px;
  padding: 0 11px;
  border: 1px solid var(--line-strong);
  border-radius: 6px;
  outline: none;
  color: var(--text);
  background: var(--bg-panel-2);
  font-size: 13px;
  transition: border-color .18s ease, box-shadow .18s ease;
}

.dialog-field input::placeholder {
  color: var(--text-dim);
}

.dialog-field input:focus {
  border-color: var(--blue);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--blue) 14%, transparent);
}

.dialog-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 17px;
}

.dialog-button,
.dialog-link-button,
.settings-link {
  font: 600 12px/1 var(--font-display);
}

.dialog-button {
  min-height: 34px;
  padding: 0 13px;
  border-radius: 6px;
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease;
}

.dialog-button:disabled {
  cursor: wait;
  opacity: .62;
}

.dialog-button-primary {
  border: 1px solid var(--blue);
  color: #fff;
  background: var(--blue);
}

.dialog-button-primary:hover:not(:disabled) {
  filter: brightness(1.08);
}

.dialog-button-secondary {
  border: 1px solid var(--line-strong);
  color: var(--text-soft);
  background: var(--bg-panel);
}

.dialog-button-secondary:hover {
  border-color: var(--blue);
  color: var(--blue);
}

.dialog-link-button,
.settings-link {
  padding: 0;
  border: 0;
  color: var(--blue);
  background: transparent;
  cursor: pointer;
  text-decoration: none;
}

.dialog-link-button:hover,
.settings-link:hover {
  text-decoration: underline;
}

.password-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.password-field-wide {
  grid-column: 1 / -1;
}

.password-actions {
  margin-top: 17px;
}

.profile-more-settings {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  background: var(--bg-panel-2);
}

.profile-more-settings p {
  max-width: 440px;
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

  .profile-section {
    padding: 19px 17px;
  }

  .password-grid {
    grid-template-columns: 1fr;
  }

  .password-field-wide {
    grid-column: auto;
  }

  .profile-more-settings {
    align-items: flex-start;
    flex-direction: column;
    gap: 12px;
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
