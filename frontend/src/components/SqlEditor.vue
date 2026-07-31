<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api'
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution'
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

const props = defineProps<{
  modelValue: string
  readOnly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const host = ref<HTMLElement | null>(null)
let instance: monaco.editor.IStandaloneCodeEditor | undefined
let changeListener: monaco.IDisposable | undefined

self.MonacoEnvironment = {
  getWorker: () => new EditorWorker()
}

onMounted(() => {
  if (!host.value) return

  monaco.editor.defineTheme('gateway-console', {
    base: 'vs',
    inherit: true,
    rules: [
      { token: 'keyword.sql', foreground: '087C68', fontStyle: 'bold' },
      { token: 'string.sql', foreground: '9A5B12' },
      { token: 'number.sql', foreground: '28768A' },
      { token: 'comment.sql', foreground: '7B8983' }
    ],
    colors: {
      'editor.background': '#FFFEFA',
      'editor.foreground': '#23352F',
      'editorLineNumber.foreground': '#98A69F',
      'editorLineNumber.activeForeground': '#50635B',
      'editorCursor.foreground': '#087C68',
      'editor.selectionBackground': '#B9DED4AA',
      'editor.inactiveSelectionBackground': '#D9EAE5AA',
      'editor.lineHighlightBackground': '#F2F6F2',
      'editorIndentGuide.background1': '#D9E0DB',
      'editorIndentGuide.activeBackground1': '#A9BAB2'
    }
  })

  instance = monaco.editor.create(host.value, {
    value: props.modelValue,
    language: 'sql',
    theme: 'gateway-console',
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
