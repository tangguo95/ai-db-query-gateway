import Foundation
import Security

private let service = "com.tangguo.ai-db-query-gateway"
private let maxInputBytes = 1_048_576

private struct HelperRequest: Decodable {
    let action: String
    let account: String
    let value: String?
}

private struct HelperResponse: Encodable {
    let ok: Bool
    let value: String?
    let error: String?

    static func success(value: String? = nil) -> HelperResponse {
        HelperResponse(ok: true, value: value, error: nil)
    }

    static func failure(_ error: String) -> HelperResponse {
        HelperResponse(ok: false, value: nil, error: error)
    }
}

private func baseQuery(account: String) -> [CFString: Any] {
    [
        kSecClass: kSecClassGenericPassword,
        kSecAttrService: service,
        kSecAttrAccount: account
    ]
}

/// 新值仅通过 stdin 进入进程；先更新已有条目，不存在时再写入默认登录钥匙串。
///
/// 命令行程序没有 data-protection keychain entitlement，不能依赖 kSecAttrAccessible。
/// 默认文件型登录钥匙串会随用户登录解锁，并由 macOS 自身的 ACL 保护。
private func put(account: String, value: String) -> HelperResponse {
    guard let secretData = value.data(using: .utf8) else {
        return .failure("value_not_utf8")
    }

    let query = baseQuery(account: account)
    let updateStatus = SecItemUpdate(
        query as CFDictionary,
        [kSecValueData: secretData] as CFDictionary
    )
    if updateStatus == errSecSuccess {
        return .success()
    }
    guard updateStatus == errSecItemNotFound else {
        return .failure("keychain_error:\(updateStatus)")
    }

    var createQuery = query
    createQuery[kSecValueData] = secretData
    let createStatus = SecItemAdd(createQuery as CFDictionary, nil)
    if createStatus == errSecSuccess {
        return .success()
    }
    // 并发创建命中重复条目时再更新一次，避免把秘密写入错误信息或日志。
    if createStatus == errSecDuplicateItem {
        let retryStatus = SecItemUpdate(
            query as CFDictionary,
            [kSecValueData: secretData] as CFDictionary
        )
        return retryStatus == errSecSuccess
            ? .success()
            : .failure("keychain_error:\(retryStatus)")
    }
    return .failure("keychain_error:\(createStatus)")
}

private func get(account: String) -> HelperResponse {
    var query = baseQuery(account: account)
    query[kSecReturnData] = kCFBooleanTrue
    query[kSecMatchLimit] = kSecMatchLimitOne

    var result: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    guard status == errSecSuccess else {
        return status == errSecItemNotFound
            ? .failure("not_found")
            : .failure("keychain_error:\(status)")
    }
    guard let data = result as? Data, let value = String(data: data, encoding: .utf8) else {
        return .failure("stored_value_not_utf8")
    }
    return .success(value: value)
}

private func delete(account: String) -> HelperResponse {
    let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
    // delete 设计为幂等，方便服务端清理已经不存在的 secretRef。
    return status == errSecSuccess || status == errSecItemNotFound
        ? .success()
        : .failure("keychain_error:\(status)")
}

private func handle(line: String) -> HelperResponse {
    guard line.utf8.count <= maxInputBytes, let data = line.data(using: .utf8) else {
        return .failure("request_too_large")
    }

    let request: HelperRequest
    do {
        request = try JSONDecoder().decode(HelperRequest.self, from: data)
    } catch {
        return .failure("invalid_json")
    }

    let account = request.account.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !account.isEmpty, account.utf8.count <= 512 else {
        return .failure("invalid_account")
    }

    switch request.action {
    case "put":
        guard let value = request.value else {
            return .failure("missing_value")
        }
        return put(account: account, value: value)
    case "get":
        return get(account: account)
    case "delete":
        return delete(account: account)
    default:
        return .failure("unsupported_action")
    }
}

private func write(response: HelperResponse) {
    // stdout 只输出单行 JSON；禁止将请求、秘密或系统异常写入 stderr。
    guard let data = try? JSONEncoder().encode(response),
          let encodedLine = String(data: data, encoding: .utf8) else {
        print(#"{"ok":false,"error":"encoding_error"}"#)
        return
    }
    print(encodedLine)
}

while let line = readLine(strippingNewline: true) {
    autoreleasepool {
        write(response: handle(line: line))
    }
}
