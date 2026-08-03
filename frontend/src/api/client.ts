import type {
  AccessTokenSummary,
  AuditRecord,
  CreateDataSourceRequest,
  CurrentUser,
  Dashboard,
  DataSourceTestResult,
  DataSourceSummary,
  ListResponse,
  QueryPreviewResponse,
  QueryApprovalPolicy,
  QueryRequest,
  QueryResponse,
  SetupStatus,
  UpdateDataSourceRequest
} from './types'

type QueryValue = string | number | boolean | null | undefined

export class ApiError extends Error {
  readonly status: number
  readonly code?: string
  readonly details?: unknown

  constructor(status: number, message: string, code?: string, details?: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
  }
}

function readCookie(name: string): string | undefined {
  const prefix = `${encodeURIComponent(name)}=`
  return document.cookie
    .split(';')
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix))
    ?.slice(prefix.length)
}

function csrfHeader(): Record<string, string> {
  const xsrf = readCookie('XSRF-TOKEN')
  if (xsrf) return { 'X-XSRF-TOKEN': decodeURIComponent(xsrf) }

  const csrf = readCookie('CSRF-TOKEN')
  if (csrf) return { 'X-CSRF-TOKEN': decodeURIComponent(csrf) }

  return {}
}

function toQuery(params?: Record<string, QueryValue>): string {
  if (!params) return ''
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') query.set(key, String(value))
  })
  const serialized = query.toString()
  return serialized ? `?${serialized}` : ''
}

async function request<T>(
  path: string,
  options: RequestInit = {},
  params?: Record<string, QueryValue>
): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const response = await fetch(`${path}${toQuery(params)}`, {
    ...options,
    credentials: 'include',
    cache: 'no-store',
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(method === 'GET' || method === 'HEAD' ? {} : csrfHeader()),
      ...options.headers
    }
  })

  const contentType = response.headers.get('content-type') ?? ''
  const payload = response.status === 204
    ? undefined
    : contentType.includes('application/json')
      ? await response.json()
      : await response.text()

  if (!response.ok) {
    const body = payload && typeof payload === 'object' ? payload as Record<string, unknown> : {}
    const message = String(body.message ?? body.detail ?? payload ?? `请求失败（HTTP ${response.status}）`)
    const code = body.errorCode ?? body.code
    if (response.status === 401 && !path.includes('/api/auth/login')) {
      window.dispatchEvent(new CustomEvent('gateway:unauthorized'))
    }
    throw new ApiError(response.status, message, code ? String(code) : undefined, payload)
  }

  return payload as T
}

export function normalizeList<T>(payload: T[] | ListResponse<T> | { content?: T[]; totalElements?: number }): ListResponse<T> {
  if (Array.isArray(payload)) return { items: payload, total: payload.length }
  if ('items' in payload && Array.isArray(payload.items)) return payload
  if ('content' in payload) {
    const items = payload.content ?? []
    return { items, total: payload.totalElements ?? items.length }
  }
  return { items: [], total: 0 }
}

type JsonRecord = Record<string, any>

function dataSource(raw: JsonRecord): DataSourceSummary {
  return {
    id: String(raw.id),
    name: String(raw.name),
    databaseType: raw.databaseType,
    readOnlyStatus: raw.readOnlyStatus,
    enabled: Boolean(raw.enabled),
    allowCompatibility: Boolean(raw.allowCompatibility),
    detectedVersion: raw.detectedVersion ?? raw.databaseVersion,
    lastCheckedAt: raw.lastCheckedAt ?? raw.lastTestedAt,
    timeoutSeconds: Number(raw.timeoutSeconds ?? raw.queryTimeoutSeconds ?? 10),
    maxRows: Number(raw.maxRows ?? 200),
    riskMessage: raw.riskMessage ?? raw.lastTestMessage
  }
}

function queryResponse(raw: JsonRecord): QueryResponse {
  const nestedResult = raw.result && typeof raw.result === 'object' ? raw.result : undefined
  const result = nestedResult ?? {}
  const parameters = Array.isArray(raw.parameters)
    ? raw.parameters.map((parameter: JsonRecord) => ({
        jdbcType: String(parameter.jdbcType ?? parameter.type ?? 'VARCHAR'),
        value: parameter.value
      }))
    : undefined
  return {
    queryId: String(raw.queryId),
    status: raw.status,
    columns: result.columns ?? raw.columns,
    rows: result.rows ?? raw.rows,
    truncated: result.truncated ?? raw.truncated,
    durationMs: result.durationMs ?? raw.durationMs,
    rowCount: result.rowCount ?? raw.rowCount,
    bytes: result.byteCount ?? raw.byteCount ?? raw.bytes,
    riskReasons: raw.riskReasons,
    expiresAt: raw.approvalExpiresAt ?? raw.expiresAt,
    errorCode: raw.errorCode,
    message: raw.message,
    dataSourceId: raw.dataSourceId,
    dataSourceName: raw.dataSourceName,
    purpose: raw.purpose,
    sqlFingerprint: raw.sqlFingerprint,
    requestedAt: raw.createdAt ?? raw.requestedAt,
    requestedBy: raw.actor ?? raw.requestedBy,
    effectiveMaxRows: raw.effectiveMaxRows,
    sql: raw.sql,
    parameters,
    resultAvailable: nestedResult !== undefined
      || Array.isArray(raw.rows)
      || Array.isArray(raw.columns)
  }
}

function queryRequestPayload(body: QueryRequest): JsonRecord {
  return {
    ...body,
    parameters: body.parameters.map((parameter) => ({
      type: parameter.jdbcType,
      value: parameter.value
    }))
  }
}

function dashboard(raw: JsonRecord): Dashboard {
  return {
    dataSourceCount: Number(raw.dataSourceCount ?? 0),
    strictCount: Number(raw.strictDataSourceCount ?? raw.strictCount ?? 0),
    compatibilityCount: Number(raw.compatibilityDataSourceCount ?? raw.compatibilityCount ?? 0),
    blockedCount: Number(raw.blockedDataSourceCount ?? raw.blockedCount ?? 0),
    pendingApprovalCount: Number(raw.pendingApprovalCount ?? 0),
    queriesToday: Number(raw.queryCountToday ?? raw.queriesToday ?? 0),
    rejectedToday: Number(raw.failedQueryCountToday ?? raw.rejectedToday ?? 0),
    auditChainValid: Boolean(raw.auditChainValid),
    lastAuditAt: raw.lastAuditAt
  }
}

function auditRecord(raw: JsonRecord): AuditRecord {
  return {
    id: raw.eventId ?? raw.sequenceNo,
    timestamp: raw.occurredAt,
    eventType: raw.eventType,
    actor: raw.actor,
    actorType: raw.actorType,
    dataSourceId: raw.dataSourceId,
    dataSourceName: raw.dataSourceName,
    queryId: raw.queryId,
    status: raw.status,
    purpose: raw.purpose,
    sqlFingerprint: raw.sqlFingerprint,
    durationMs: raw.durationMs,
    rowCount: raw.rowCount,
    bytes: raw.byteCount ?? raw.bytes,
    errorCode: raw.errorCode,
    chainValid: raw.chainValid
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message
  if (error instanceof Error) return error.message
  return '请求未完成，请检查服务状态后重试'
}

export const api = {
  setupStatus: () => request<SetupStatus>('/api/setup/status'),
  setup: (body: { bootstrapToken: string; password: string }) =>
    request<void>('/api/setup', { method: 'POST', body: JSON.stringify(body) }),
  login: (body: { password: string }) =>
    request<CurrentUser>('/api/auth/login', { method: 'POST', body: JSON.stringify(body) }),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  currentUser: () => request<CurrentUser>('/api/auth/me'),

  dashboard: async () => dashboard(await request<JsonRecord>('/api/dashboard')),

  queryApprovalPolicy: () => request<QueryApprovalPolicy>('/api/settings/query-approval'),
  updateQueryApprovalPolicy: (approvalRequired: boolean) =>
    request<QueryApprovalPolicy>('/api/settings/query-approval', {
      method: 'PUT',
      body: JSON.stringify({ approvalRequired })
    }),

  dataSources: async () => {
    const payload = await request<JsonRecord[] | ListResponse<JsonRecord>>('/api/datasources')
    const page = normalizeList(payload)
    return { ...page, items: page.items.map(dataSource) }
  },
  createDataSource: async (body: CreateDataSourceRequest) => dataSource(await request<JsonRecord>(
    '/api/datasources',
    {
      method: 'POST',
      body: JSON.stringify({
        name: body.name,
        databaseType: body.databaseType,
        host: body.host,
        port: body.port,
        database: body.database,
        username: body.username,
        password: body.password,
        properties: { tlsMode: body.tlsMode },
        allowCompatibility: body.allowCompatibility,
        queryTimeoutSeconds: body.timeoutSeconds
      })
    }
  )),
  updateDataSource: async (id: string, body: UpdateDataSourceRequest) => {
    const payload: JsonRecord = {}
    if (body.name !== undefined) payload.name = body.name
    if (body.host !== undefined) payload.host = body.host
    if (body.port !== undefined) payload.port = body.port
    if (body.database !== undefined) payload.database = body.database
    if (body.username !== undefined) payload.username = body.username
    if (body.password !== undefined) payload.password = body.password
    if (body.tlsMode !== undefined) payload.properties = { tlsMode: body.tlsMode }
    if (body.allowCompatibility !== undefined) payload.allowCompatibility = body.allowCompatibility
    if (body.timeoutSeconds !== undefined) payload.queryTimeoutSeconds = body.timeoutSeconds
    if (body.enabled !== undefined) payload.enabled = body.enabled

    return dataSource(await request<JsonRecord>(
      `/api/datasources/${encodeURIComponent(id)}`,
      { method: 'PUT', body: JSON.stringify(payload) }
    ))
  },
  deleteDataSource: (id: string) =>
    request<void>(`/api/datasources/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  testDataSource: (id: string) =>
    request<DataSourceTestResult>(`/api/datasources/${encodeURIComponent(id)}/test`, {
      method: 'POST',
      body: JSON.stringify({})
    }),

  previewQuery: (body: QueryRequest) =>
    request<QueryPreviewResponse>('/api/queries/preview', {
      method: 'POST',
      body: JSON.stringify(queryRequestPayload(body))
    }),
  submitQuery: async (body: QueryRequest) => queryResponse(await request<JsonRecord>(
    '/api/queries',
    {
      method: 'POST',
      body: JSON.stringify(queryRequestPayload(body))
    }
  )),
  queries: async (params: { status?: string; limit?: number } = {}) => {
    const payload = await request<JsonRecord[] | ListResponse<JsonRecord>>('/api/queries', {}, params)
    const page = normalizeList(payload)
    return { ...page, items: page.items.map(queryResponse) }
  },
  query: async (id: string) =>
    queryResponse(await request<JsonRecord>(`/api/queries/${encodeURIComponent(id)}`)),
  queryResult: async (id: string) =>
    queryResponse(await request<JsonRecord>(`/api/queries/${encodeURIComponent(id)}/result`)),
  approveQuery: async (id: string) =>
    queryResponse(await request<JsonRecord>(`/api/queries/${encodeURIComponent(id)}/approve`, {
      method: 'POST',
      body: JSON.stringify({})
    })),
  executeQuery: async (id: string) =>
    queryResponse(await request<JsonRecord>(`/api/queries/${encodeURIComponent(id)}/execute`, {
      method: 'POST',
      body: JSON.stringify({})
    })),
  cancelQuery: async (id: string) =>
    queryResponse(await request<JsonRecord>(`/api/queries/${encodeURIComponent(id)}/cancel`, {
      method: 'POST',
      body: JSON.stringify({})
    })),

  audits: async (params: {
    page?: number
    size?: number
    eventType?: string
    status?: string
    queryId?: string
  }) => {
    const payload = await request<JsonRecord[] | ListResponse<JsonRecord> | { content: JsonRecord[]; totalElements: number }>(
      '/api/audits',
      {},
      params
    )
    const page = normalizeList(payload)
    return { ...page, items: page.items.map(auditRecord) }
  },

  tokens: () => request<AccessTokenSummary[] | ListResponse<AccessTokenSummary>>('/api/tokens'),
  createToken: (body: {
    name: string
    dataSourceIds: string[]
    expiresInDays: number
    rawDataAcknowledged: boolean
  }) => request<AccessTokenSummary>('/api/tokens', {
    method: 'POST',
    body: JSON.stringify({
      name: body.name,
      dataSourceIds: body.dataSourceIds,
      expiresInDays: body.expiresInDays,
      confirmCloudDataRisk: body.rawDataAcknowledged
    })
  }),
  updateTokenScope: (
    id: string,
    body: { dataSourceIds: string[]; rawDataAcknowledged: boolean }
  ) => request<AccessTokenSummary>(`/api/tokens/${encodeURIComponent(id)}/scope`, {
    method: 'PUT',
    body: JSON.stringify({
      dataSourceIds: body.dataSourceIds,
      confirmCloudDataRisk: body.rawDataAcknowledged
    })
  }),
  deleteToken: (id: string) =>
    request<void>(`/api/tokens/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
