import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { QueryPreviewResponse, QueryResponse } from '../api/types'
import WorkbenchView from './WorkbenchView.vue'

const testState = vi.hoisted(() => ({
  push: vi.fn(),
  previewQuery: vi.fn(),
  submitQuery: vi.fn(),
  cancelQuery: vi.fn(),
  query: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  success: vi.fn()
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ push: testState.push })
}))

vi.mock('element-plus', () => ({
  ElMessage: {
    warning: testState.warning,
    error: testState.error,
    success: testState.success
  }
}))

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    api: {
      dataSources: vi.fn().mockResolvedValue({
        items: [{
          id: 'ds-1',
          name: '生产订单只读库',
          databaseType: 'MYSQL',
          readOnlyStatus: 'STRICT',
          enabled: true,
          allowCompatibility: false,
          timeoutSeconds: 10,
          maxRows: 200
        }],
        total: 1
      }),
      previewQuery: testState.previewQuery,
      submitQuery: testState.submitQuery,
      cancelQuery: testState.cancelQuery,
      query: testState.query
    }
  }
})

const ButtonStub = defineComponent({
  inheritAttrs: false,
  props: {
    disabled: Boolean,
    loading: Boolean
  },
  emits: ['click'],
  template: `
    <button
      v-bind="$attrs"
      :disabled="disabled || loading"
      @click="$emit('click')"
    ><slot /></button>
  `
})

const InputStub = defineComponent({
  inheritAttrs: false,
  props: {
    modelValue: [String, Number],
    type: String,
    disabled: Boolean
  },
  emits: ['update:modelValue'],
  template: `
    <textarea
      v-if="type === 'textarea'"
      v-bind="$attrs"
      :value="modelValue"
      :disabled="disabled"
      @input="$emit('update:modelValue', $event.target.value)"
    />
    <input
      v-else
      v-bind="$attrs"
      :value="modelValue"
      :disabled="disabled"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `
})

const SqlEditorStub = defineComponent({
  props: {
    modelValue: {
      type: String,
      required: true
    }
  },
  emits: ['update:modelValue'],
  template: `
    <textarea
      data-testid="sql-editor"
      :value="modelValue"
      @input="$emit('update:modelValue', $event.target.value)"
    />
  `
})

const PassThroughStub = defineComponent({
  template: '<div><slot /></div>'
})

function previewResponse(): QueryPreviewResponse {
  return {
    dataSourceId: 'ds-1',
    readOnlyStatus: 'STRICT',
    schemas: [],
    tables: ['order_info'],
    riskReasons: [],
    effectiveMaxRows: 200,
    parameterCount: 0,
    sqlFingerprint: 'fingerprint'
  }
}

function queryResponse(overrides: Partial<QueryResponse> = {}): QueryResponse {
  return {
    queryId: 'query-fixed-id',
    status: 'EXECUTED',
    columns: [{ label: 'id', typeName: 'BIGINT' }],
    rows: [[1]],
    resultAvailable: true,
    ...overrides
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((promiseResolve, promiseReject) => {
    resolve = promiseResolve
    reject = promiseReject
  })
  return { promise, resolve, reject }
}

async function mountWorkbench(): Promise<VueWrapper> {
  const wrapper = mount(WorkbenchView, {
    global: {
      stubs: {
        'el-button': ButtonStub,
        'el-select': PassThroughStub,
        'el-option': PassThroughStub,
        'el-form': PassThroughStub,
        'el-form-item': PassThroughStub,
        'el-input': InputStub,
        'el-input-number': InputStub,
        SqlEditor: SqlEditorStub,
        StateChip: true
      }
    }
  })
  await flushPromises()
  await wrapper.get('[data-testid="query-purpose"]').setValue('核对生产订单状态')
  await wrapper.get('[data-testid="sql-editor"]').setValue(
    'SELECT id FROM order_info WHERE id = 1'
  )
  return wrapper
}

describe('WorkbenchView guarded query flow', () => {
  beforeEach(() => {
    testState.push.mockReset()
    testState.previewQuery.mockReset()
    testState.submitQuery.mockReset()
    testState.cancelQuery.mockReset()
    testState.query.mockReset()
    testState.warning.mockReset()
    testState.error.mockReset()
    testState.success.mockReset()
    vi.spyOn(crypto, 'randomUUID').mockReturnValue('00000000-0000-4000-8000-000000000001')
  })

  it('shows a server preview failure even when no preview object exists', async () => {
    testState.previewQuery.mockRejectedValueOnce(new Error('SQL 含有禁止的锁查询'))
    const wrapper = await mountWorkbench()

    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('SQL 含有禁止的锁查询')
    expect(wrapper.get('[data-testid="submit-query"]').attributes('disabled')).toBeDefined()
    expect(testState.submitQuery).not.toHaveBeenCalled()
  })

  it('submits only the exact form revision accepted by server preview', async () => {
    testState.previewQuery.mockResolvedValue(previewResponse())
    testState.submitQuery.mockResolvedValue(queryResponse())
    const wrapper = await mountWorkbench()

    expect(wrapper.get('[data-testid="submit-query"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="submit-query"]').attributes('disabled')).toBeUndefined()

    await wrapper.get('[data-testid="sql-editor"]').setValue(
      'SELECT id FROM order_info WHERE id = 2'
    )
    expect(wrapper.get('[data-testid="submit-query"]').attributes('disabled')).toBeDefined()
    await wrapper.get('[data-testid="submit-query"]').trigger('click')
    expect(testState.submitQuery).not.toHaveBeenCalled()

    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="submit-query"]').trigger('click')
    await flushPromises()

    expect(testState.submitQuery).toHaveBeenCalledTimes(1)
    expect(testState.submitQuery).toHaveBeenCalledWith(expect.objectContaining({
      dataSourceId: 'ds-1',
      sql: 'SELECT id FROM order_info WHERE id = 2',
      purpose: '核对生产订单状态',
      requestId: '00000000-0000-4000-8000-000000000001'
    }))
  })

  it('discards a successful preview response when the form changed in flight', async () => {
    const pendingPreview = deferred<QueryPreviewResponse>()
    testState.previewQuery.mockReturnValue(pendingPreview.promise)
    const wrapper = await mountWorkbench()

    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await wrapper.get('[data-testid="query-purpose"]').setValue('改成另一项生产核对')
    pendingPreview.resolve(previewResponse())
    await flushPromises()

    expect(wrapper.text()).toContain('上一次服务端预检已失效')
    expect(wrapper.get('[data-testid="submit-query"]').attributes('disabled')).toBeDefined()
    expect(testState.submitQuery).not.toHaveBeenCalled()
  })

  it('keeps cancellation reachable while the submit request is pending', async () => {
    const pendingSubmit = deferred<QueryResponse>()
    testState.previewQuery.mockResolvedValue(previewResponse())
    testState.submitQuery.mockReturnValue(pendingSubmit.promise)
    testState.cancelQuery.mockResolvedValue(queryResponse({
      status: 'CANCELLED',
      columns: undefined,
      rows: undefined,
      resultAvailable: false,
      errorCode: 'QUERY_CANCELLED'
    }))
    const wrapper = await mountWorkbench()

    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="submit-query"]').trigger('click')
    await nextTick()

    const cancelButton = wrapper.get('[data-testid="cancel-active-query"]')
    expect(cancelButton.attributes('disabled')).toBeUndefined()
    await cancelButton.trigger('click')
    await flushPromises()
    expect(testState.cancelQuery).toHaveBeenCalledWith(
      '00000000-0000-4000-8000-000000000001'
    )

    pendingSubmit.resolve(queryResponse())
    await flushPromises()
    expect(wrapper.text()).toContain('CANCELLED')
  })

  it('does not present a non-persisted terminal result as an empty result set', async () => {
    testState.previewQuery.mockResolvedValue(previewResponse())
    testState.submitQuery.mockResolvedValue(queryResponse({
      resultAvailable: false,
      columns: undefined,
      rows: undefined,
      rowCount: 8
    }))
    const wrapper = await mountWorkbench()

    await wrapper.get('[data-testid="preview-query"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="submit-query"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('查询结果未持久化，无法从历史请求恢复')
    expect(wrapper.text()).not.toContain('本次响应中的结果集为空')
  })
})
