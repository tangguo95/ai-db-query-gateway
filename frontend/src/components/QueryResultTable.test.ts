import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import QueryResultTable from './QueryResultTable.vue'

describe('QueryResultTable', () => {
  it('renders database values as text instead of executable markup', () => {
    const wrapper = mount(QueryResultTable, {
      props: {
        result: {
          queryId: 'q-1',
          status: 'EXECUTED',
          columns: [{ label: '<img src=x>', typeName: 'VARCHAR' }],
          rows: [['<img src=x onerror=alert(1)>']],
          rowCount: 1
        }
      }
    })

    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>')
    expect(wrapper.find('img').exists()).toBe(false)
  })
})
