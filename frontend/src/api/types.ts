export type DatabaseType = 'MYSQL' | 'OCEANBASE_MYSQL' | 'OCEANBASE_ORACLE'
export type ReadOnlyStatus = 'STRICT' | 'COMPATIBILITY' | 'BLOCKED' | 'UNKNOWN'
export type QueryStatus =
  | 'REQUESTED'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'EXECUTING'
  | 'EXECUTED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'FAILED'
  | 'CANCELLED'
  | 'TIMED_OUT'

export interface SetupStatus {
  configured?: boolean
  initialized?: boolean
  setupComplete?: boolean
  bootstrapTokenRequired?: boolean
}

export interface CurrentUser {
  username?: string
  displayName?: string
  authenticated?: boolean
  sessionExpiresAt?: string
  idleExpiresAt?: string
}

export interface DataSourceSummary {
  id: string
  name: string
  databaseType: DatabaseType
  readOnlyStatus: ReadOnlyStatus
  enabled: boolean
  allowCompatibility: boolean
  detectedVersion?: string
  lastCheckedAt?: string
  timeoutSeconds: number
  maxRows: number
  riskMessage?: string
}

export interface CreateDataSourceRequest {
  name: string
  databaseType: DatabaseType
  host: string
  port: number
  database: string
  username: string
  password: string
  tlsMode: 'DISABLED' | 'REQUIRED' | 'VERIFY_IDENTITY'
  timeoutSeconds: number
  allowCompatibility: boolean
}

export interface UpdateDataSourceRequest {
  name?: string
  host?: string
  port?: number
  database?: string
  username?: string
  password?: string
  tlsMode?: CreateDataSourceRequest['tlsMode']
  timeoutSeconds?: number
  allowCompatibility?: boolean
  enabled?: boolean
}

export interface QueryParameter {
  jdbcType: string
  value: unknown
}

export interface QueryRequest {
  dataSourceId: string
  sql: string
  parameters: QueryParameter[]
  purpose: string
  maxRows: number
  requestId?: string
}

export interface QueryPreviewResponse {
  dataSourceId: string
  readOnlyStatus: ReadOnlyStatus
  schemas: string[]
  tables: string[]
  riskReasons: string[]
  effectiveMaxRows: number
  parameterCount: number
  sqlFingerprint: string
}

export interface QueryColumn {
  label: string
  typeName: string
}

export interface QueryResponse {
  queryId: string
  status: QueryStatus
  columns?: QueryColumn[]
  rows?: unknown[][]
  truncated?: boolean
  durationMs?: number
  rowCount?: number
  bytes?: number
  riskReasons?: string[]
  expiresAt?: string
  errorCode?: string
  message?: string
  dataSourceId?: string
  dataSourceName?: string
  purpose?: string
  sqlFingerprint?: string
  requestedAt?: string
  requestedBy?: string
  effectiveMaxRows?: number
  sql?: string
  parameters?: QueryParameter[]
  resultAvailable?: boolean
}

export interface Dashboard {
  dataSourceCount: number
  strictCount: number
  compatibilityCount: number
  blockedCount: number
  pendingApprovalCount: number
  queriesToday: number
  rejectedToday: number
  auditChainValid: boolean
  lastAuditAt?: string
  pendingQueries?: QueryResponse[]
  recentQueries?: QueryResponse[]
}

export interface AuditRecord {
  id: string | number
  timestamp?: string
  createdAt?: string
  eventType?: string
  action?: string
  actor?: string
  subject?: string
  dataSourceName?: string
  dataSourceId?: string
  queryId?: string
  status?: string
  purpose?: string
  sqlFingerprint?: string
  durationMs?: number
  rowCount?: number
  bytes?: number
  errorCode?: string
  chainValid?: boolean
}

export interface AccessTokenSummary {
  id: string
  name: string
  dataSourceIds?: string[]
  createdAt?: string
  expiresAt?: string
  lastUsedAt?: string
  revoked?: boolean
  status?: string
  token?: string
}

export interface ListResponse<T> {
  items: T[]
  total: number
  page?: number
  size?: number
}

export interface DataSourceTestResult {
  reachable: boolean
  readOnlyStatus: ReadOnlyStatus
  enabled: boolean
  databaseVersion?: string
  account?: string
  findings?: string[]
  message?: string
}
