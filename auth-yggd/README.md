
Auth Yggdrasil 模块 (hzl-auth-yggd)
=================================

目的
----
本模块为 HyperZoneLogin 提供基于 Yggdrasil（Mojang 风格）在线验证的子模块能力：
- 通过配置的多个 Yggdrasil Entry 向第三方或官方服务发起并发验证请求；
- 在验证成功时可将 Entry 记录与本地 profile 绑定，实现一层登录/注册自动化流程；
- 提供配置管理、Entry 表管理与并发验证的实现细节。

集成与运行时行为（*不包含构建步骤*）
----------------------------------
- 将模块的 jar 放入 Velocity `plugins/` 文件夹并确保主插件 `hyperzonelogin` 已加载。
- 本模块在启动时会检测主插件并调用 `HyperZoneLoginMain.getInstance().registerModule(YggdrasilSubModule())` 完成注册；若主插件尚不可用，会记录警告并等待主插件就绪。

主要技术细节
-------------
- 配置管理：
  - `EntryConfigManager` 从数据目录的 `entry/` 子目录读取所有 `.conf`（HOCON）配置文件。首次运行会在 `entry/` 下创建示例与默认配置（例如 `mojang.conf`）。
  - 配置解析使用 Configurate（HOCON）并通过 object mapping 映射到 `EntryConfig` 数据类。
- 表管理：
  - `EntryTableManager` 为每个配置的 Entry 动态注册并管理数据库表，支持创建/删除和监听 `TableSchemaEvent`。
- 验证流程：
  - `YggdrasilAuthModule` 负责发起并管理并发的验证请求，使用 Java HttpClient（超时配置）、协程（kotlinx.coroutines）做异步并发处理，并维护 per-player 的临时状态（authResults、inFlightAuthJobs 等）。
  - 验证分两批：第一批优先查询数据库中已有的 Entry 记录并向这些 Entry 发起请求；若未命中或失败，再向所有配置的 Entry 并发请求。成功后会回调 Limbo handler 并可能创建/绑定 profile。
  - 每个 `entry/*.conf` 都可以单独配置 `yggdrasil.passYggdrasilUuidToProfileResolve`；默认 `true`，表示在 `canResolveOrCreateProfile(...)` / `resolveOrCreateProfile(...)` 时透传远端可信 UUID，关闭后改传 `null`，由核心侧回退到本地 remap UUID。

配置文件位置 & 行为
------------------
- 数据目录下的 `entry/`：放置每个 Entry 的配置文件（例如 `mojang.conf`）。
- `entry/example/`：示例配置不会被加载，作为模板保留。
- 在首次创建 `mojang.conf` 时，默认会写入 Mojang 官方 session URL（用于默认验证）。
- `entry/*.conf` 中的 `yggdrasil.passYggdrasilUuidToProfileResolve` 为逐 Entry 生效：你可以只对某些正版源透传 UUID，而让另一些源关闭透传。

命令与权限
-----------
模块本身不直接注册玩家命令（Yggdrasil 通过事件/监听器驱动）。常用管理命令（如 reload 或迁移）通常由主插件或其它子模块提供。

注意事项
---------
- 若需要添加自定义 Entry，请将自定义 `.conf` 放入 `entry/`（非 example），模块会在下次加载/重载时读取并注册相应 Entry 表与事件。
- 并发验证受全局超时与单个请求超时约束（在 `YggdrasilAuthModule` 中可看到相关实现）。
- 请审查你配置的第三方 Yggdrasil 服务的 URL 模板，确保 `{username}`、`{serverId}` 等占位符正确替换。

开发者提示
-----------
- 若要扩展验证器（比如支持自定义认证协议），可以实现新的 `AuthenticationRequest` 并将其加入 `ConcurrentAuthenticationManager` 的请求列表。

