import { ref, type Ref } from 'vue'

export type Theme = 'light' | 'dark'

const STORAGE_KEY = 'ai-db-query-gateway.theme'
const theme = ref<Theme>('light')
let initialized = false

function readStoredTheme(): Theme {
  try {
    return window.localStorage.getItem(STORAGE_KEY) === 'dark' ? 'dark' : 'light'
  } catch {
    return 'light'
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
  theme.value = readStoredTheme()
  applyTheme(theme.value)
  initialized = true
  return theme.value
}

export function useTheme(): {
  theme: Ref<Theme>
  setTheme: (next: Theme) => void
  toggleTheme: () => void
} {
  initializeTheme()

  function setTheme(next: Theme): void {
    theme.value = next
    applyTheme(next)
    try {
      window.localStorage.setItem(STORAGE_KEY, next)
    } catch {
      // 隐私模式或受限浏览器不允许写入时，仍保持本次会话的主题。
    }
  }

  function toggleTheme(): void {
    setTheme(theme.value === 'dark' ? 'light' : 'dark')
  }

  return { theme, setTheme, toggleTheme }
}
