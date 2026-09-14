import { ref, type Ref } from 'vue'

export type Theme = 'light' | 'dark'
export type ThemePreference = Theme | 'system'

const STORAGE_KEY = 'ai-db-query-gateway.theme'
const theme = ref<Theme>('light')
const preference = ref<ThemePreference>('system')
let systemTheme: MediaQueryList | undefined
let initialized = false

function readStoredTheme(): ThemePreference {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY)
    return stored === 'dark' || stored === 'light' ? stored : 'system'
  } catch {
    return 'system'
  }
}

function applyTheme(next: Theme): void {
  const root = document.documentElement
  root.dataset.theme = next
  root.style.colorScheme = next

  const themeColor = document.querySelector<HTMLMetaElement>('meta[name="theme-color"]')
  if (themeColor) themeColor.content = next === 'dark' ? '#0b1220' : '#f8fafc'
}

export function initializeTheme(): Theme {
  if (initialized) return theme.value
  systemTheme = window.matchMedia?.('(prefers-color-scheme: dark)')
  preference.value = readStoredTheme()
  updateTheme()
  systemTheme?.addEventListener('change', updateTheme)
  window.addEventListener('storage', (event) => {
    if (event.key === STORAGE_KEY || event.key === null) {
      preference.value = readStoredTheme()
      updateTheme()
    }
  })
  initialized = true
  return theme.value
}

// 主题偏好与实际颜色分开保存，SQL 编辑器继续订阅实际深浅色。
function updateTheme(): void {
  theme.value = preference.value === 'system'
    ? (systemTheme?.matches ? 'dark' : 'light') : preference.value
  applyTheme(theme.value)
}

export function useTheme(): {
  theme: Ref<Theme>
  preference: Ref<ThemePreference>
  setTheme: (next: ThemePreference) => void
  toggleTheme: () => void
} {
  initializeTheme()

  function setTheme(next: ThemePreference): void {
    preference.value = next
    updateTheme()
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 隐私模式或受限浏览器不允许写入时，仍保持本次会话的主题。
    }
  }

  function toggleTheme(): void {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, preference, setTheme, toggleTheme }
}
