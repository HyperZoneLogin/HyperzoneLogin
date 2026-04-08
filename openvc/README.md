openvc / 主插件 (HyperZoneLogin 核心)
===================================

目的
----
`openvc` 是 HyperZoneLogin 的主插件/核心模块，负责：
- 档案（Profile）管理（注册、查找与绑定）；
- 提供主运行时的 API（例如 `HyperZoneLoginMain`、数据库管理、命令/事件集成点）给其他子模块使用；
- 提供基础数据库接口与部分工具（如离线 UUID 识别与 profile 表定义）。

集成与模块注册
----------------
- 子模块应通过主插件暴露的 API 注册自己：调用 `HyperZoneLoginMain.getInstance().registerModule(...)` 注册 `HyperSubModule` 实例。
- 主插件在启动时会注册若干内置子模块（如 `OfflineSubModule`、`YggdrasilSubModule`、`MergeSubModule` 等）；外部模块也可以在运行时检测到主插件并主动调用 `registerModule` 完成集成。

主要技术细节
-------------
- API & Provider：
  - 核心提供 `HyperZoneDatabaseManager`、`HyperZonePlayerAccessorProvider`、`HyperChatCommandManagerProvider` 等接口供子模块使用；
  - 提供 `ProfileTable` 等数据库表定义，子模块可借此创建或查询与 profile 相关的数据。
- 命令：
  - 内置命令实现 `HyperZoneLoginCommand`，支持如下用例（示例）：
	- `reload`：重载配置/状态（输出 `Reloaded!`）。
	- `re`：对玩家触发重新认证（仅玩家可执行，会调用 `triggerLimboAuthForPlayer`）。
	- `uuid`：显示代理 Player 与 HyperZonePlayer 的信息（包括 profile、uuid 等）。
  - 管理命令受限于权限 `hyperzonelogin.admin`（见 `hasPermission` 实现）。
- 事件与表管理：
  - 主插件负责触发表结构事件（`TableSchemaEvent`）以便子模块统一创建/删除表结构；
  - 子模块可以监听并响应这些事件进行表的创建或清理。

- 无 `limboapi` 时的认证等待区：
  - 可在 `misc.conf` 中配置 `fallbackAuthServer` 为一个真实后端服务器名；
  - 当未安装 `limboapi` 时，未认证玩家会被固定送入该服务器等待认证；
  - 认证完成前，玩家不能进入其他后端；
  - 若 `rememberRequestedServerDuringAuth=true`，则会记住玩家原本想去的服务器，并在认证成功后自动连接过去。

权限（Permission）
-------------------
| 命令 | 说明 | 所需权限 |
|------|------|----------|
| /hzl reload | 重载插件配置/状态 | hyperzonelogin.admin |
| /hzl re | 触发重新认证（玩家专用） | 无（命令仅允许玩家） |
| /hzl uuid | 显示 player/profile 信息 | hyperzonelogin.admin |

开发者/扩展提示
----------------
- 若实现子模块，请在 `register(...)` 中要求合适的 provider（例如 `HyperChatCommandManagerProvider`、`HyperZonePlayerAccessorProvider`），并在缺失时抛出明确异常以便快速定位集成问题。
- 主插件尽量暴露必要的轻量 API，避免子模块通过反射或 classloader hack 进行耦合调用。

