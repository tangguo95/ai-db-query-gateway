# macOS Keychain helper

这个小型 Swift 进程为网关服务提供 macOS Keychain 的最小读写接口。数据库连接
信息和审计密钥只通过 stdin/stdout 传递，不允许放入命令参数、环境变量或普通日志。

## 构建

```bash
./native/macos-keychain/build.sh
```

默认产物为 `native/macos-keychain/build/keychain-helper`，与后端默认配置一致。也可以将临时输出目录
作为唯一参数传入：

```bash
./native/macos-keychain/build.sh /tmp/ai-db-keychain-build
```

传入自定义输出目录时，二进制名称仍为 `keychain-helper`。helper 使用系统
`Security.framework`，Keychain service 固定为
`com.tangguo.ai-db-query-gateway`。首次访问时，macOS 可能根据当前钥匙串策略显示授权
提示。

## 协议

helper 支持逐行读取 UTF-8 JSON，每个请求产生一行 JSON 响应。当前 Java 服务为每次
Keychain 操作启动一个短生命周期 helper、写入一条请求后关闭 stdin；helper 随 EOF
退出，不在后台常驻：

```json
{"action":"put","account":"datasource:550e8400-e29b-41d4-a716-446655440000","value":"secret-json"}
{"action":"get","account":"datasource:550e8400-e29b-41d4-a716-446655440000"}
{"action":"delete","account":"datasource:550e8400-e29b-41d4-a716-446655440000"}
```

成功响应为 `{"ok":true}`，`get` 成功时增加 `value`；失败响应为
`{"ok":false,"error":"稳定错误码"}`。`delete` 是幂等操作，不存在的条目也返回成功。
helper 不会把 account、value 或 Keychain 本地化错误说明写入 stderr。

生产运行时应由网关后端直接启动 helper，并保持 stdin/stdout 管道私有；不要在终端
手工输入真实数据库凭据。后端会拒绝符号链接、非可信 owner、组/其他用户可写或运行期
摘要变化的 helper，并清空子进程环境。

这不是同一 UID 内的权限隔离。拥有当前 macOS 用户任意 Shell 权限的进程仍可直接运行
helper 并尝试访问该用户的 Keychain；相应部署边界见仓库根目录 `SECURITY.md`。
