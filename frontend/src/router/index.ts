import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/setup',
    name: 'setup',
    component: () => import('../views/SetupView.vue'),
    meta: { public: true }
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('../views/LoginView.vue'),
    meta: { public: true }
  },
  {
    path: '/',
    component: () => import('../layouts/AppShell.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
      { path: 'datasources', name: 'datasources', component: () => import('../views/DataSourcesView.vue') },
      { path: 'workbench', name: 'workbench', component: () => import('../views/WorkbenchView.vue') },
      { path: 'approvals', name: 'approvals', component: () => import('../views/ApprovalsView.vue') },
      { path: 'audits', name: 'audits', component: () => import('../views/AuditView.vue') },
      { path: 'tokens', name: 'tokens', component: () => import('../views/TokensView.vue') },
      { path: 'security', name: 'security', component: () => import('../views/SecurityView.vue') }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.bootstrap()

  if (!auth.configured && to.name !== 'setup') return { name: 'setup' }
  if (auth.configured && to.name === 'setup') return auth.authenticated ? { name: 'dashboard' } : { name: 'login' }
  if (!to.meta.public && !auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.authenticated) return { name: 'dashboard' }
  return true
})

export default router
