import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/setup', redirect: '/dashboard' },
  { path: '/login', redirect: '/dashboard' },
  {
    path: '/',
    component: () => import('../layouts/AppShell.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
      { path: 'servers', name: 'servers', component: () => import('../views/ServersView.vue') },
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

router.beforeEach(async () => {
  const auth = useAuthStore()
  await auth.bootstrap()
  return true
})

export default router
