Auth Floodgate 模块 (`hzl-auth-floodgate`)
=========================================

用途
----
- 识别由 Floodgate 接入的 Bedrock 玩家。
- 在主插件的初始 `GameProfile` 校验阶段，通过 Floodgate API 对匹配玩家放行，避免被核心 remap 前缀校验误拦截。
- 将 Floodgate 视为独立可信认证渠道，在后续 `VServerAuthStartEvent` 阶段提交 Floodgate 凭证并完成统一认证。

运行时行为
----------
- 依赖主插件 `hyperzonelogin` 提供 API。
- 仅在代理已安装 `floodgate` 插件时注册模块；若未安装，则自动跳过，不启用任何相关监听。
- 单文件版中也支持内置加载：若 `modules.conf` 中 `authFloodgate=true` 且检测到 `floodgate`，则会自动启用内置版本。
- 模块会在数据目录生成 `floodgate-auth.conf`；其中 `stripUsernamePrefix=true` 默认开启，用于自动去除 Floodgate API 当前配置的玩家名前缀。
- `passFloodgateUuidToProfileResolve=true` 默认开启；若关闭，则在 `resolveOrCreateProfile(...)` 时不透传 Floodgate 原始 UUID，而是改传 `null`。
- 由于 Floodgate 渠道会跳过 HZL 自订的 `OpenPreLoginEvent` 与 `OpenStartAuthEvent`，模块会在 `VerifyInitialGameProfileEvent` 阶段自行创建登录期 `HyperZonePlayer` 并记录 Floodgate 会话。
- 后续不会尝试把 Floodgate 解释成 HZL 的在线/离线模式，而是始终按独立 `floodgate` 渠道提交可信凭证。

