Auth Offline 模块 (hzl-auth-offline)
===================================

目的
----
本模块为 HyperZoneLogin 提供「本地/离线」账号管理：支持玩家通过服务器内部存储的账号进行注册、登录、绑定、修改密码以及注销等操作。适用于不连接 Mojang/Yggdrasil 或需要额外本地账号的环境。

如何使用（运行时集成，*不包含构建步骤*）
-----------------------------------
- 将本模块作为单独的 Velocity 插件放入 `plugins/` 文件夹（插件 id 为 `hzl-auth-offline`）。
- 在运行时，本模块会检测主插件 `hyperzonelogin` 是否存在；若存在会直接调用主插件的 API：`HyperZoneLoginMain.getInstance().registerModule(OfflineSubModule())` 完成集成。
- 本模块依赖主插件在运行时提供的 API（`api` 项目由 `velocity` 模块在运行时提供），因此必须确保主插件已加载。

主要功能与技术细节
-----------------
- 注册表/数据库：
  - 使用 `OfflineAuthTableManager` 管理离线认证表结构，启动时会尝试创建所需表并监听表事件。表名按 `databaseManager.tablePrefix` 进行前缀化。
  - `OfflineAuthRepository` 封装对离线认证记录的 CRUD 操作（按用户名或 profileId 查询）。
- 服务逻辑：
  - `OfflineAuthService` 提供核心逻辑：register、bind、login、changePassword、unregister 等方法。
  - 密码处理支持多种存储格式：plain、sha256、authme（兼容 AuthMe 风格的存储），默认使用 sha256。
  - 密码校验逻辑位于 `OfflineAuthService.verifyPassword` 和 `verifyAuthMe` 中；内部使用 SHA-256 hex 编码（见 `sha256Hex`）。
  - 支持邮箱绑定与找回密码；恢复邮件可走 `LOG` 或 `SMTP` 投递模式，SMTP 基于 Jakarta Mail / Angus Mail 运行库。
  - 支持短期 session 自动登录：可按 IP 绑定、按分钟过期，并在登出、改密、邮箱找回改密后立即失效。
- 事件与集成：
  - 模块会在注册时向代理事件管理器注册 `OfflineAuthTableManager`、命令注册器和监听器（例如 `OfflineLimboEventListener`）。
  - 模块实现依赖的 provider 接口包括 `HyperChatCommandManagerProvider` 与 `HyperZonePlayerAccessorProvider`，注册时会对 owner 做类型断言并在缺失时抛出异常以防止错误集成。

可用命令（聊天命令，由模块在注册时通过 `HyperChatCommandManager` 注册）
-------------------------------------------------------------
以下命令为聊天命令（玩家在聊天框输入），示例语法与说明：

- /login <password>
  - 用法：立即使用指定密码尝试登录（适用于已注册账号）；登录成功后会按配置签发短期 session。
- /register <password>
  - 用法：为当前连接的玩家创建一个离线账号并自动登录（若允许注册）。
- /bind <password>
  - 用法：将当前玩家已有的 profile 与离线账号绑定（在已通过其他渠道验证后使用）。
- /changepassword <old> <new>
  - 用法：修改当前玩家的离线账号密码（需提供旧密码）。
- /logout
  - 用法：登出（终止当前会话验证状态，并清空当前 short session）。
- /unregister <password>
  - 用法：注销当前玩家的离线账号（需确认密码）。
- /email add <password> <email> <email>
  - 用法：绑定邮箱到当前离线账号。
- /email change <password> <oldEmail> <newEmail>
  - 用法：修改当前离线账号绑定邮箱。
- /email show <password>
  - 用法：查看当前离线账号绑定邮箱。
- /email recovery <email>
  - 用法：向已绑定邮箱发送找回验证码。
- /email code <code>
  - 用法：验证邮箱收到的恢复码。
- /email setpassword <newPassword> <newPassword>
  - 用法：在恢复码验证通过后重置密码并自动完成本次认证。

权限（Permission）
-------------------
本模块内置的聊天命令主要面向普通玩家，命令本身不要求特殊权限（由 HyperChatCommandManager 的注册方式决定）。下表列出命令与权限要求：

| 命令 | 说明 | 所需权限 |
|------|------|----------|
| /login | 登录本地账号 | 无（玩家可自用） |
| /register | 注册本地账号并登录 | 无（但会根据玩家状态限制） |
| /bind | 绑定 profile 与本地账号 | 无（需玩家先完成其他验证） |
| /changepassword | 修改密码 | 无（需提供旧密码） |
| /logout | 注销会话 | 无 |
| /unregister | 注销账号 | 无（需提供密码） |
| /email ... | 邮箱绑定 / 找回 | 无（部分子命令需提供密码） |

注意事项
---------
- 本模块不打包 `api`；运行时必须由 `velocity` 主模块或主插件提供 API。若未找到主插件，会在初始化时打印警告并在主插件就绪后尝试注册。
- 密码兼容注意：如果你从其他插件迁移数据，`authme` 格式会被识别并验证，但建议统一迁移为 sha256 以获得一致性和更简单的实现。
- 首次启动会生成 `offline-auth.conf`；若要启用真实邮件发送，请将 `email.deliveryMode` 改为 `SMTP` 并完整填写 `email.smtp.*` 配置。
- 若要启用或调整 short session 自动登录，请编辑 `offline-auth.conf` 中的 `session.enabled`、`session.expireMinutes`、`session.bindIp`、`session.issueOnRegister`。

开发者提示
-----------
- 若要在主插件中使用或测试本模块，请以主插件的 `registerModule(...)` 方式注册 `OfflineSubModule`，或直接运行 Velocity 并放置两个插件（主插件 + 本模块）以完成自动集成。


