import { defineStore } from 'pinia'
import { api } from '../api/client'
import type { CurrentUser } from '../api/types'

function setupConfigured(status: Awaited<ReturnType<typeof api.setupStatus>>): boolean {
  return Boolean(status.configured ?? status.initialized ?? status.setupComplete)
}

export const useAuthStore = defineStore('auth', {
  state: () => ({
    ready: false,
    configured: false,
    user: null as CurrentUser | null
  }),
  getters: {
    authenticated: (state) => Boolean(state.user && state.user.authenticated !== false),
    username: (state) => state.user?.displayName || state.user?.username || '本地管理员'
  },
  actions: {
    async bootstrap() {
      if (this.ready) return
      try {
        const status = await api.setupStatus()
        this.configured = setupConfigured(status)
        if (this.configured) {
          try {
            this.user = await api.currentUser()
          } catch {
            this.user = null
          }
        }
      } finally {
        this.ready = true
      }
    },
    async refreshUser() {
      this.user = await api.currentUser()
      return this.user
    },
    async login(password: string) {
      const response = await api.login({ password })
      this.user = response || await api.currentUser()
      this.configured = true
    },
    async setup(bootstrapToken: string, password: string) {
      await api.setup({ bootstrapToken, password })
      this.configured = true
      this.user = null
    },
    async logout() {
      try {
        await api.logout()
      } finally {
        this.user = null
      }
    },
    markUnauthenticated() {
      this.user = null
    }
  }
})
