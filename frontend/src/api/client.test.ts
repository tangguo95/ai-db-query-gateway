import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, normalizeList } from './client'

function response(status: number, payload?: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get: (name: string) => name.toLowerCase() === 'content-type' && payload !== undefined
        ? 'application/json'
        : ''
    },
    json: vi.fn().mockResolvedValue(payload),
    text: vi.fn().mockResolvedValue('')
  } as unknown as Response
}

describe('API client security behavior', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
    vi.stubGlobal('fetch', vi.fn())
  })

  it('adds the Spring CSRF cookie value to mutating requests', async () => {
    document.cookie = 'XSRF-TOKEN=proof%2F123; path=/'
    vi.mocked(fetch).mockResolvedValue(response(204))

    await api.logout()

    expect(fetch).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      cache: 'no-store',
      headers: expect.objectContaining({
        'X-XSRF-TOKEN': 'proof/123'
      })
    }))
  })

  it('does not attach a CSRF header to read requests', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      dataSourceCount: 0,
      strictCount: 0,
      compatibilityCount: 0,
      blockedCount: 0,
      pendingApprovalCount: 0,
      queriesToday: 0,
      rejectedToday: 0,
      auditChainValid: true
    }))

    await api.dashboard()

    const options = vi.mocked(fetch).mock.calls[0][1] as RequestInit
    expect(options.headers).not.toHaveProperty('X-XSRF-TOKEN')
  })

  it('adapts server query envelopes without exposing result HTML', async () => {
    vi.mocked(fetch).mockResolvedValue(response(200, {
      queryId: 'query-1',
      status: 'EXECUTED',
      effectiveMaxRows: 200,
      result: {
        columns: [{ label: 'payload', typeName: 'VARCHAR' }],
        rows: [['<script>alert(1)</script>']],
        truncated: false,
        durationMs: 12,
        rowCount: 1,
        byteCount: 25
      }
    }))

    const result = await api.query('query-1')

    expect(result.rows).toEqual([['<script>alert(1)</script>']])
    expect(result.bytes).toBe(25)
    expect(result.effectiveMaxRows).toBe(200)
  })

  it('maps frontend fields to the strict server contract', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      id: 'ds-1',
      name: 'orders',
      databaseType: 'MYSQL',
      readOnlyStatus: 'UNKNOWN',
      enabled: false,
      queryTimeoutSeconds: 10
    }))

    await api.createDataSource({
      name: 'orders',
      databaseType: 'MYSQL',
      host: 'db.internal',
      port: 3306,
      database: 'orders',
      username: 'reader',
      password: 'secret',
      tlsMode: 'VERIFY_IDENTITY',
      timeoutSeconds: 10,
      allowCompatibility: false
    })

    const options = vi.mocked(fetch).mock.calls[0][1] as RequestInit
    expect(JSON.parse(String(options.body))).toEqual(expect.objectContaining({
      properties: { tlsMode: 'VERIFY_IDENTITY' },
      queryTimeoutSeconds: 10,
      allowCompatibility: false
    }))
    expect(String(options.body)).not.toContain('timeoutSeconds')
  })

  it('maps safe partial updates without inventing redacted connection values', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      id: 'ds-1',
      name: 'orders-v2',
      databaseType: 'MYSQL',
      readOnlyStatus: 'UNKNOWN',
      enabled: false,
      allowCompatibility: true,
      queryTimeoutSeconds: 15
    }))

    const updated = await api.updateDataSource('ds/1', {
      name: 'orders-v2',
      password: 'rotated-secret',
      tlsMode: 'REQUIRED',
      timeoutSeconds: 15,
      allowCompatibility: true
    })

    expect(fetch).toHaveBeenCalledWith('/api/datasources/ds%2F1', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({
        name: 'orders-v2',
        password: 'rotated-secret',
        properties: { tlsMode: 'REQUIRED' },
        allowCompatibility: true,
        queryTimeoutSeconds: 15
      })
    }))
    expect(updated.allowCompatibility).toBe(true)
    const body = JSON.parse(String((vi.mocked(fetch).mock.calls[0][1] as RequestInit).body))
    expect(body).not.toHaveProperty('host')
    expect(body).not.toHaveProperty('username')
    expect(body).not.toHaveProperty('database')
  })

  it('updates only the automatic data source connection-check policy', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      autoRetryConnectionChecks: true,
      retryIntervalSeconds: 60,
      maxBackoffMinutes: 15
    }))

    const policy = await api.updateDataSourceRecoveryPolicy(true)

    expect(fetch).toHaveBeenCalledWith('/api/settings/data-source-recovery', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ autoRetryConnectionChecks: true }),
      headers: expect.objectContaining({
        'X-XSRF-TOKEN': 'proof'
      })
    }))
    expect(policy.autoRetryConnectionChecks).toBe(true)
  })

  it('loads and updates the local administrator profile without transforming the avatar data URL', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      username: 'admin',
      displayName: '数据管理员',
      avatarDataUrl: 'data:image/gif;base64,R0lGODlh'
    }))

    const profile = await api.profile()
    const updated = await api.updateProfile({
      displayName: profile.displayName,
      avatarDataUrl: profile.avatarDataUrl
    })

    expect(profile.avatarDataUrl).toContain('data:image/gif')
    expect(updated.displayName).toBe('数据管理员')
    expect(fetch).toHaveBeenLastCalledWith('/api/profile', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({
        displayName: '数据管理员',
        avatarDataUrl: 'data:image/gif;base64,R0lGODlh'
      }),
      headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'proof' })
    }))
  })

  it('sends password changes through the protected profile endpoint', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(204))

    await api.changePassword({
      currentPassword: 'current-password',
      newPassword: 'new-password-123',
      confirmPassword: 'new-password-123'
    })

    expect(fetch).toHaveBeenCalledWith('/api/profile/password', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({
        currentPassword: 'current-password',
        newPassword: 'new-password-123',
        confirmPassword: 'new-password-123'
      }),
      headers: expect.objectContaining({ 'X-XSRF-TOKEN': 'proof' })
    }))
  })

  it('uses the scoped DELETE endpoint for data source removal', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(204))

    await api.deleteDataSource('ds/1')

    expect(fetch).toHaveBeenCalledWith('/api/datasources/ds%2F1', expect.objectContaining({
      method: 'DELETE',
      credentials: 'include',
      headers: expect.objectContaining({
        'X-XSRF-TOKEN': 'proof'
      })
    }))
  })

  it('forwards audit event prefixes and query ID filters', async () => {
    vi.mocked(fetch).mockResolvedValue(response(200, { items: [], total: 0 }))

    await api.audits({
      page: 0,
      size: 25,
      eventType: 'QUERY',
      status: 'FAILED',
      queryId: '0b06b0a7-f58e-4fcb-8730-10d2940259aa'
    })

    expect(fetch).toHaveBeenCalledWith(
      '/api/audits?page=0&size=25&eventType=QUERY&status=FAILED&queryId=0b06b0a7-f58e-4fcb-8730-10d2940259aa',
      expect.objectContaining({
        credentials: 'include',
        cache: 'no-store'
      })
    )
  })

  it('updates a token scope without sending or replacing the raw token', async () => {
    document.cookie = 'XSRF-TOKEN=proof; path=/'
    vi.mocked(fetch).mockResolvedValue(response(200, {
      id: 'token-1',
      name: 'Codex',
      dataSourceIds: ['ds-1', 'ds-2']
    }))

    await api.updateTokenScope('token/1', {
      dataSourceIds: ['ds-1', 'ds-2'],
      rawDataAcknowledged: true
    })

    expect(fetch).toHaveBeenCalledWith('/api/tokens/token%2F1/scope', expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({
        dataSourceIds: ['ds-1', 'ds-2'],
        confirmCloudDataRisk: true
      }),
      headers: expect.objectContaining({
        'X-XSRF-TOKEN': 'proof'
      })
    }))
    expect(String((vi.mocked(fetch).mock.calls[0][1] as RequestInit).body))
      .not.toContain('token')
  })
})

describe('normalizeList', () => {
  it('accepts plain arrays and paginated server responses', () => {
    expect(normalizeList([{ id: 1 }])).toEqual({ items: [{ id: 1 }], total: 1 })
    expect(normalizeList({ content: [{ id: 2 }], totalElements: 7 })).toEqual({
      items: [{ id: 2 }],
      total: 7
    })
  })
})
