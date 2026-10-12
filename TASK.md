# TASK: 隐藏/锁定认证服，防止玩家通过 /server 进入

## 问题
HZL 的 outpre 模式自动注册 `outpre-auth` 到 Velocity servers 列表，`paper` 作为 fallback-auth-server 也在列表中。玩家可以通过 `/server outpre-auth` 或 `/server paper` 进入认证服，这是不应该的。

## 需求
1. 认证服（`outpre-auth` 和 `fallback-auth-server` 配置的服务器）对玩家**不可见且不可进入**
2. 玩家仍然可以用 `/server` 切换到其他游玩服（如 folia、folia2）
3. HZL 自身的内部转移（认证等待、post-auth 转移）**不受影响**

## 实现方案
在 `velocity/src/main/kotlin/icu/h2l/login/vServer/outpre/OutPreVServerAuth.kt` 的 `onServerPreConnect` 方法中（约 line 319）：

1. 检查 `event.originalServer` 的服务器名是否匹配 `authTargetLabel`（即 `outpre-auth`）
2. 如果匹配，检查是否是 HZL 内部发起的转移（通过 ThreadLocal 标志或跟踪 HZL 发起的连接请求）
3. 如果是玩家主动通过 `/server` 发起的转移，`event.result = ServerPreConnectEvent.ServerResult.denied()`

同时需要在 `BackendAuthHoldListener.kt` 的 `onServerPreConnect`（约 line 174）做类似处理，覆盖 backend 模式。

## 配置扩展
在 `VServerConfig.kt` 中添加：
```kotlin
// 隐藏认证服，禁止玩家通过 /server 进入
val hideAuthServers: Boolean = true,
```

## 约束
- 遵守 AGENT.MD 的所有规范
- 不改动登录流程状态机
- 只新增连接过滤逻辑
- 使用 i18n（如果需要玩家提示消息）
- 构建产物：`gradlew :velocity:build` 生成 shadowJar

## 测试
构建后部署到 `D:\Documents\HermesProjects\minecraft\mc-server-test\velocity\plugins\HyperZoneLogin.jar`
