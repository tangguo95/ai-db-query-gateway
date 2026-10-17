import { defineStore } from 'pinia'
import { api, errorMessage } from '../api/client'
import type { CurrentUser } from '../api/types'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    ready: false,
    configured: false,
    error: '',
    user: null as CurrentUser | null
  }),
  getters: {
    authenticated: (state) => Boolean(state.user && state.user.authenticated !== false),
    username: (state) => state.user?.displayName || state.user?.username || '本地管理员'
  },
  actions: {
    async bootstrap() {
      if (this.ready) return
      this.error = ''
      try {
        this.user = await api.currentUser()
        this.configured = true
      } catch (cause) {
        this.user = null
        this.error = errorMessage(cause)
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
      this.error = '本机请求认证失败，请检查网关状态后重试'
    }
  }
})
