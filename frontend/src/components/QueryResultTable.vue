<script setup lang="ts">
import { computed } from 'vue'
import type { QueryResponse } from '../api/types'

const props = defineProps<{ result: QueryResponse }>()

const columns = computed(() => props.result.columns ?? [])
const rows = computed(() => props.result.rows ?? [])
const resultAvailable = computed(() => props.result.resultAvailable !== false)

function cellText(value: unknown): string {
  if (value === null) return 'NULL'
  if (value === undefined) return ''
  if (typeof value === 'object') {
    try {
      return JSON.stringify(value)
    } catch {
      return '[无法序列化]'
    }
  }
  return String(value)
}

function bytesText(bytes?: number): string {
  if (bytes === undefined) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MiB`
}
</script>

<template>
  <section class="result-block">
    <div v-if="!resultAvailable" class="result-unavailable">
      <span>结果未保留</span>
      <h3>查询结果未持久化，无法从历史请求恢复</h3>
      <p>网关只在原始执行响应中返回结果。这里仅能确认查询已执行，不能据此判断结果集为空。</p>
    </div>
    <template v-else>
      <header class="result-telemetry">
        <div>
          <span>行数</span>
          <strong>{{ result.rowCount ?? rows.length }}</strong>
        </div>
        <div>
          <span>耗时</span>
          <strong>{{ result.durationMs ?? '—' }}<small v-if="result.durationMs !== undefined"> ms</small></strong>
        </div>
        <div>
          <span>数据量</span>
          <strong>{{ bytesText(result.bytes) }}</strong>
        </div>
        <div>
          <span>限额状态</span>
          <strong :class="{ 'text-amber': result.truncated }">{{ result.truncated ? '已截断' : '完整' }}</strong>
        </div>
      </header>

      <div v-if="rows.length && columns.length" class="result-scroll" role="region" aria-label="查询结果">
        <table>
          <thead>
            <tr>
              <th class="row-number">#</th>
              <th v-for="(column, index) in columns" :key="`${column.label}-${index}`">
                <span>{{ column.label }}</span>
                <small>{{ column.typeName }}</small>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, rowIndex) in rows" :key="rowIndex">
              <td class="row-number">{{ String(rowIndex + 1).padStart(3, '0') }}</td>
              <td
                v-for="(column, columnIndex) in columns"
                :key="`${rowIndex}-${columnIndex}`"
                :class="{ 'null-cell': row[columnIndex] === null }"
                :title="cellText(row[columnIndex])"
              >
                {{ cellText(row[columnIndex]) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="result-empty">查询已完成，本次响应中的结果集为空。</div>
    </template>
  </section>
</template>

<style scoped>
.result-block {
  border-top: 1px solid var(--line);
}

.result-unavailable {
  padding: 31px;
  border-left: 3px solid var(--amber);
  background: rgba(245, 185, 76, .045);
}

.result-unavailable span {
  color: var(--amber);
  font: 700   14px/1 var(--font-mono);
  letter-spacing: .1em;
}

.result-unavailable h3 {
  margin: 10px 0 7px;
  color: #654818;
  font: 650 16px/1.3 var(--font-display);
}

.result-unavailable p {
  margin: 0;
  color: #80652d;
  font-size: 13px;
  line-height: 1.6;
}

.result-telemetry {
  display: grid;
  grid-template-columns: repeat(4, minmax(110px, 1fr));
  background: #f2f4ef;
  border-bottom: 1px solid var(--line);
}

.result-telemetry div {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  min-height: 56px;
  padding: 12px 16px;
  border-right: 1px solid var(--line);
}

.result-telemetry div:last-child {
  border-right: 0;
}

.result-telemetry span,
.result-telemetry small {
  color: var(--text-dim);
  font:  14px/1 var(--font-mono);
  letter-spacing: .08em;
}

.result-telemetry strong {
  color: var(--text);
  font: 650 15px/1 var(--font-mono);
}

.result-scroll {
  max-height: 470px;
  overflow: auto;
  overscroll-behavior: contain;
}

table {
  width: max-content;
  min-width: 100%;
  border-collapse: collapse;
  color: #344a42;
  font:  13px/1.45 var(--font-mono);
}

th,
td {
  min-width: 130px;
  max-width: 360px;
  padding: 9px 12px;
  overflow: hidden;
  border-right: 1px solid #d2dad5;
  border-bottom: 1px solid #d2dad5;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

th {
  position: sticky;
  z-index: 2;
  top: 0;
  color: var(--text-soft);
  background: #f7f8f4;
  font-weight: 650;
}

th span,
th small {
  display: block;
}

th small {
  margin-top: 3px;
  color: var(--text-dim);
  font-size: 14px;
  font-weight: 400;
}

tbody tr:hover td {
  background: #eaf2ed;
}

.row-number {
  min-width: 50px;
  width: 50px;
  color: #607169;
  text-align: right;
  background: #f7f8f4;
}

th.row-number {
  z-index: 3;
}

.null-cell {
  color: #607169;
  font-style: italic;
}

.result-empty {
  padding: 42px;
  color: var(--text-dim);
  font:  13px/1.6 var(--font-mono);
  text-align: center;
}

@media (max-width: 680px) {
  .result-telemetry {
    grid-template-columns: repeat(2, 1fr);
  }

  .result-telemetry div:nth-child(2) {
    border-right: 0;
  }
}
</style>
