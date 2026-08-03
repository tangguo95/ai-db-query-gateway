<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'
import { useTheme } from '../theme'

const props = defineProps<{
  modelValue: string
  readOnly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const host = ref<HTMLElement | null>(null)
const { theme } = useTheme()
let instance: monaco.editor.IStandaloneCodeEditor | undefined
let changeListener: monaco.IDisposable | undefined

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
}

function editorThemeName(): string {
  return theme.value === 'dark' ? 'gateway-console-dark' : 'gateway-console-light'
}

function defineEditorThemes(): void {
  monaco.editor.defineTheme('gateway-console-light', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword.sql', foreground: '2563EB', fontStyle: 'bold' },
      { token: 'string.sql', foreground: 'B45309' },
      { token: 'number.sql', foreground: '0F9F75' },
      { token: 'comment.sql', foreground: '64748B' }
    ],
    colors: {
      'editor.background': '#FFFFFF',
      'editor.foreground': '#334155',
      'editorLineNumber.foreground': '#94A3B8',
      'editorLineNumber.activeForeground': '#475569',
      'editorCursor.foreground': '#2563EB',
      'editor.selectionBackground': '#BFDBFE99',
      'editor.inactiveSelectionBackground': '#E2E8F0AA',
      'editor.lineHighlightBackground': '#F8FAFC',
      'editorIndentGuide.background1': '#E2E8F0',
      'editorIndentGuide.activeBackground1': '#CBD5E1'
    }
  })

  monaco.editor.defineTheme('gateway-console-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'keyword.sql', foreground: '60A5FA', fontStyle: 'bold' },
      { token: 'string.sql', foreground: 'FBBF24' },
      { token: 'number.sql', foreground: '34D399' },
      { token: 'comment.sql', foreground: '94A3B8' }
    ],
    colors: {
      'editor.background': '#111A2A',
      'editor.foreground': '#E2E8F0',
      'editorLineNumber.foreground': '#64748B',
      'editorLineNumber.activeForeground': '#CBD5E1',
      'editorCursor.foreground': '#60A5FA',
      'editor.selectionBackground': '#1E3A8A99',
      'editor.inactiveSelectionBackground': '#26364DAA',
      'editor.lineHighlightBackground': '#162235',
      'editorIndentGuide.background1': '#26364D',
      'editorIndentGuide.activeBackground1': '#3B4D68'
    }
  })
}

onMounted(() => {
  if (!host.value) return

  defineEditorThemes()

  instance = monaco.editor.create(host.value, {
    value: props.modelValue,
    language: 'sql',
    theme: editorThemeName(),
    readOnly: props.readOnly,
    automaticLayout: true,
    minimap: { enabled: false },
    folding: true,
    fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', monospace",
    fontSize: 15,
    lineHeight: 23,
    lineNumbersMinChars: 3,
    padding: { top: 13, bottom: 13 },
    renderWhitespace: 'selection',
    scrollBeyondLastLine: false,
    smoothScrolling: true,
    tabSize: 2,
    wordWrap: 'on',
    ariaLabel: 'SQL 编辑器。数据库结果不会在此执行为 HTML。'
  })

  changeListener = instance.onDidChangeModelContent(() => {
    const value = instance?.getValue() ?? ''
    if (value !== props.modelValue) emit('update:modelValue', value)
  })
})

watch(theme, () => {
  monaco.editor.setTheme(editorThemeName())
})

watch(
  () => props.modelValue,
  (value) => {
    if (instance && instance.getValue() !== value) instance.setValue(value)
  }
)

watch(
  () => props.readOnly,
  (readOnly) => instance?.updateOptions({ readOnly })
)

onBeforeUnmount(() => {
  changeListener?.dispose()
  instance?.dispose()
})
</script>

<template>
  <div ref="host" class="sql-editor" />
</template>

<style scoped>
.sql-editor {
  width: 100%;
  height: 360px;
}
</style>
