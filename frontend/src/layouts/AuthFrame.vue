<script setup lang="ts">
import NavIcon from '../components/NavIcon.vue'
import { useTheme } from '../theme'

defineProps<{
  step: string
  title: string
  subtitle: string
}>()

const { theme, toggleTheme } = useTheme()
</script>

<template>
  <main class="auth-frame">
    <section class="auth-context">
      <div class="auth-brand">
        <span class="brand-index">本地安全访问</span>
        <h1>生产数据库<br />只读查询网关</h1>
        <p>AI 数据库只读查询控制台</p>
      </div>

      <div class="security-notes">
        <div><strong>01</strong><span>数据库凭据由服务端 SecretStore 隔离，不写入应用数据库。</span></div>
        <div><strong>02</strong><span>所有查询先过 AST 白名单，并在只读事务中执行。</span></div>
        <div><strong>03</strong><span>审计记录链式校验，查询结果不落盘。</span></div>
      </div>
    </section>

    <section class="auth-console">
      <div class="console-top">
        <span><i />本机回环安全通道</span>
        <div class="console-top-actions">
          <span>{{ step }}</span>
          <button
            class="auth-theme-toggle"
            type="button"
            :aria-label="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
            :title="theme === 'dark' ? '切换为浅色主题' : '切换为深色主题'"
            @click="toggleTheme"
          >
            <NavIcon :name="theme === 'dark' ? 'sun' : 'moon'" />
            <span>{{ theme === 'dark' ? '浅色' : '深色' }}</span>
          </button>
        </div>
      </div>
      <div class="auth-card">
        <p class="eyebrow">{{ step }} / 管理员认证</p>
        <h2>{{ title }}</h2>
        <p class="auth-subtitle">{{ subtitle }}</p>
        <slot />
      </div>
      <div class="auth-foot">
        <span>默认监听 127.0.0.1</span>
        <span>不接入外部埋点</span>
      </div>
    </section>
  </main>
</template>

<style scoped>
.auth-frame {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(330px, .85fr) minmax(480px, 1.15fr);
  background: var(--bg-void);
}

.auth-context {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  justify-content: space-between;
  overflow: hidden;
  padding: clamp(42px, 6vw, 86px);
  border-right: 1px solid var(--line);
  background: #fff;
}

.auth-brand {
  position: relative;
  z-index: 1;
}

.brand-index {
  display: inline-block;
  margin-bottom: 40px;
  padding: 7px 11px;
  border: 1px solid #bfdbfe;
  border-radius: 6px;
  color: #1d4ed8;
  background: #eff6ff;
  font: 600 12px/1 var(--font-display);
  letter-spacing: 0;
}

.auth-brand h1 {
  margin: 0;
  color: var(--text);
  font: 700 clamp(34px, 4.2vw, 56px)/1.12 var(--font-display);
  letter-spacing: -.04em;
}

.auth-brand p {
  margin: 22px 0 0;
  color: var(--blue);
  font: 14px/1.5 var(--font-display);
  letter-spacing: .04em;
}

.security-notes {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 14px;
  max-width: 470px;
}

.security-notes div {
  display: grid;
  grid-template-columns: 36px 1fr;
  gap: 13px;
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

.security-notes strong {
  color: var(--text-dim);
  font:  14px/1.5 var(--font-mono);
}

.security-notes span {
  color: var(--text-soft);
  font-size: 14px;
  line-height: 1.6;
}

.auth-console {
  display: grid;
  min-height: 100vh;
  grid-template-rows: 58px 1fr 48px;
  padding: 0 clamp(28px, 6vw, 90px);
  background: #f8fafc;
}

.console-top,
.auth-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .04em;
}

.console-top-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.auth-theme-toggle {
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 6px;
  padding: 0 9px;
  border: 1px solid var(--line);
  border-radius: 6px;
  color: var(--text-soft);
  background: var(--bg-panel);
  cursor: pointer;
  font: 500 12px/1 var(--font-display);
}

.auth-theme-toggle:hover {
  color: var(--text);
  background: var(--bg-panel-2);
}

.auth-theme-toggle :deep(.nav-icon) {
  width: 15px;
  height: 15px;
}

.console-top {
  border-bottom: 1px solid var(--line);
}

.console-top i {
  display: inline-block;
  width: 5px;
  height: 5px;
  margin-right: 7px;
  border-radius: 50%;
  background: var(--green);
  box-shadow: 0 0 10px var(--green);
}

.auth-card {
  width: min(480px, 100%);
  margin: auto;
  padding: 34px 36px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, .05);
}

.auth-card h2 {
  margin: 0;
  color: var(--text);
  font: 700 clamp(26px, 3.5vw, 34px)/1.15 var(--font-display);
  letter-spacing: -.03em;
}

.auth-subtitle {
  margin: 13px 0 30px;
  color: var(--text-soft);
  font-size: 15px;
  line-height: 1.7;
}

.auth-foot {
  border-top: 1px solid var(--line);
}

@media (max-width: 860px) {
  .auth-frame {
    display: block;
  }

  .auth-context {
    min-height: 270px;
    padding: 34px 28px;
  }

  .auth-brand h1 {
    font-size: 40px;
  }

  .brand-index {
    margin-bottom: 20px;
  }

  .security-notes {
    display: none;
  }

  .auth-console {
    min-height: calc(100vh - 270px);
    padding: 0 22px;
  }
}
</style>
