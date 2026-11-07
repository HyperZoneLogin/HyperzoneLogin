# HyperZoneLogin Agent Rules

本文件记录 HyperZoneLogin 登录链路的强制工程规范。

## 1. 登录流程必须使用明确语义

HyperZoneLogin 的登录链路属于**严格状态机**，禁止使用会混淆状态边界的模糊命名或混合流程。

### 强制规则

- `resolveUser` 只允许表示：**按既有 `userUUID` 解析已有 User**。
- `createUser` 只允许表示：**以指定注册名尝试新建 User**。
- `canResolveUser` 只允许表示：**既有 `userUUID` 是否可解析**。
- `canCreateUser` 只允许表示：**给定 `(userName, uuid?)` 是否允许建档**。

### 明确禁止

以下语义在本项目中视为不合规，禁止重新引入：

- `resolveOrCreate`
- `canResolveUserOrCreate`
- 任何把 `resolve / create / rename / 回退匹配 / 自动改名` 混在一个入口中的 API

原因：登录流程必须可审计、可推导、可断言，不能依赖“也许解析、也许创建、也许顺便改名”的模糊副作用。

## 2. HyperZonePlayer 的身份字段

- `userUUID`：当前登录会话已绑定的 HZL User UUID；attach 成功后由核心写入，一个会话最多对应一个。
- `attachedUser` / `activeInGameProfile`：attach 后的对象缓存；高频路径必须直接读取，不得回查数据库。
- `channelLoginName` / `channelLoginUuid` 不再单独存在；统一由 `loginIdentity: ClientLoginIdentity` record 提供，仅用于协议对齐。
- `submittedCredential`：当前会话最近提交的认证凭证快照，用于读取渠道登录信息。
- 模块只允许读取上述身份，不得直接修改。
- `registrationName`：当前等待区会话在“若要新建 User”时应使用的**待建档注册名**，可由 `/rename` 在等待区中显式修改。
- `clientOriginalName` / `clientOriginalUUID` 已删除；日志/诊断统一使用 `logLabel`，它不是可信身份。
- 禁止把临时等待区名称、渠道认证名或代理玩家名当成正式 User 身份。
- 登录成功包（`ServerLoginSuccessEvent`）必须回写 `loginIdentity.uuid` / `loginIdentity.name`，不得写入 HZL User UUID 或活动 InGameProfile UUID；否则客户端不会认领自己的皮肤。
- `loginIdentity` 必须在连接开始时捕获，渠道后续返回的认证名称/UUID 不得覆盖它。
- self skin replay 也必须使用同一个 `loginIdentity` 与登录成功包保持一致。

## 3. `/rename` 的职责边界

`/rename` 只能用于：

- 当前玩家仍在等待区；
- 当前玩家尚未 attach 正式 User；
- 当前会话存在待建档上下文；
- 新名称已经通过严格的 `canCreateUser(...)` 检查。

`/rename` 触发后应：

1. 修改事件发起者当前会话的 `registrationName`；
2. 触发 `event.auth.LoginRenameEvent`；
3. 仅让**事件发起者本人**的等待区流程重试；
4. 不得影响其他等待区玩家。

额外强调：

- `/rename` **不得**作为 `attachUser` 之后的游戏区身份热改入口；
- 已 attach 正式 User 后的任何 name / UUID 热改，若未来存在，只能由**核心内部受控流程**驱动，不得由等待区命令直接触发；
- 等待区的名称、UUID、凭证组合都属于**不可信且高变化的临时态**，不得草率把等待区操作外溢到正式游戏区身份。

## 4. 已落库绑定关系不得被 rename 静默改写

`rename` 只允许修改**当前等待区会话的临时待建档状态**。

禁止：

- 因 `/rename` 直接修改已存在 User 的正式名称；
- 因 `/rename` 直接改写已落库的正式认证绑定；
- 用 `/rename` 充当 merge / remap / rebind 工具。

`/reUUID` 已移除。UUID 冲突应视为渠道账号未正确 link，引导玩家通过 `/bindcode use` 绑定到已有档案；禁止重新引入等待区 UUID 重算/热改入口。

## 5. 认证凭证规范

认证模块提交到核心层的 `HyperZoneCredential` 必须遵守：

- `getBoundUserUUID()` 只表示当前是否已绑定；
- 未绑定凭证可通过 `getSuggestedGameUuid()` 提供渠道游戏 UUID，但该 UUID 只能用于附加渠道 InGameProfile，禁止作为新 User 的默认身份 UUID；
- 如需支持等待区 rename，只能通过 `onRegistrationNameChanged(...)` 修改**临时状态**；
- 不得在 rename 时隐式篡改正式落库绑定。

## 5.1 `attachUser` 后的游戏区流程边界

`attachUser` 之后，玩家身份已进入正式 User 语义。

此阶段若存在 GameProfile 显示优化、正式 name/UUID 补偿同步、切服后身份一致性修复等需求，必须遵守：

- 只允许由核心层**内部受控机制**处理；
- 必须与等待区建档/改名/重算 UUID 流程彻底隔离；
- 不得复用 `/rename`、等待区 credential 回调等不可信入口直接驱动；
- 设计与实现时必须明确标注“这是正式游戏区流程，不是等待区流程”。

## 5.2 `VelocityHyperZoneUserService` / `UserDatabaseHelper` / `InGameProfileDatabaseHelper` 的禁止越界项

`VelocityHyperZoneUserService` 负责 User 与 InGameProfile 的明确业务编排；
`UserDatabaseHelper` 只允许操作 HZL user 账号表；
`InGameProfileDatabaseHelper` 只允许操作 in_game_profile 表。

数据库 helper 默认只承担：

- 单表查询；
- 单表插入；
- 单表更新；
- 单表删除。

除非需求被**明确、单独、强语义**地说明，否则禁止：

- 在数据库 helper 中新增跨表业务编排；
- 在数据库 helper 中提供隐式 upsert / ensure / saveOrUpdate；
- 在数据库 helper 中直接改写既有 User 的正式 `name` / `UUID`；
- 把运行时显示修正、Velocity 内存补偿同步、等待区临时态修正，错误地下沉为正式 User 数据更新。

## 6. 等待区放行条件

玩家离开等待区必须同时满足：

- 认证已通过；
- 正式 User 已 attach。

任何仅完成其中一半的状态，都必须继续停留在等待区。

## 7. 模块实现建议

- Offline / Yggdrasil / Floodgate 等认证模块，如遇“可信认证完成但当前名称无法建档”，应保留玩家在等待区并提供明确提示；
- 后续恢复流程应通过显式 `create(...)` + 绑定逻辑推进，不得重新拼回模糊入口；
- 新模块接入时，优先复用核心事件与明确接口，不得私自引入“自动 resolveOrCreate”捷径。

## 8. 模块对齐原则

所有子模块都必须与现有模块保持一致的基础能力，不允许出现“功能已做但外围体验缺失”的半成品接入。

### 8.1 i18n 为强制项

凡是面向玩家展示的模块消息，必须接入统一 i18n：

- 提供模块级消息 helper（如 `YggdrasilMessages`、`FloodgateMessages`）；
- 提供 `messages/<namespace>/en_us.conf`、`zh_cn.conf`；
- 在模块注册阶段复制 bundled locale 到数据目录；
- 禁止在监听器 / service 中长期保留玩家可见硬编码字符串。

### 8.1.1 禁止消息 fallback

消息渲染**只允许**从消息服务读取真实资源，不允许在代码里再保存一份硬编码文案作为兜底：

- 消息 helper 必须使用 `HyperZoneMessageServiceProvider.get()`，禁止 `getOrNull()`、`?.`、`?:` 或 `if (service != null)` 之类的降级分支；
- 禁止 `render(key, fallback, ...)` 这种同时传 key 和 fallback 文案的设计；
- 消息 key 缺失时，由消息服务统一向服务器后台输出警告，并渲染明确的缺失标记；不得静默替换成另一段硬编码文本；
- 消息服务本身不可用时必须直接抛错，不允许返回空组件、默认文本或无操作结果；
- 插件对外显示名称统一使用 `HyperZoneLogin`，禁止使用“玩家系统”“Player System”等旧称。

原因：双份文案会让资源文件与代码逐渐不一致，修改消息时极易漏改，并且会把真正的配置/资源缺失隐藏起来。

### 8.1.2 不支持的接口必须抛异常

接口能力未实现、未支持或当前实现无法提供时，必须显式抛出异常：

- 使用 `UnsupportedOperationException`（或该接口明确约定的异常类型），并在异常信息中写清接口/能力名称；
- 禁止用默认返回值静默表示“不支持”，例如直接返回 `false`、`null`、空集合、空字符串或空实现；
- 禁止调用方用 `getOrNull()`、`runCatching { ... }.getOrNull()`、`?: default` 等方式吞掉“不支持”状态；
- 调用方必须显式处理异常，或让异常向上传播，不能假装功能可用。

例外仅限**纯粹的查询型可选值**（例如“是否存在某个可选配置”）。只要语义是“能力不存在 / 接口未实现”，就必须抛异常。

### 8.2 模块行为与核心提示必须对齐

- 若模块会把玩家留在等待区，就必须给出下一步操作提示；
- 若模块支持 `/rename` / `/bindcode use` 这类恢复路径，就必须在对应失败提示中明确告知；
- 不允许某个模块具备功能，但提示链路缺失，导致玩家只能“猜命令”。

### 8.3 新模块接入检查项

新增或重构模块时，至少检查以下项目是否齐全：

1. 明确接口语义是否符合核心规则；
2. 玩家可见消息是否全部接入 i18n；
3. 是否提供中/英/俄三语资源；
4. 是否在失败/等待区状态下提供明确下一步引导；
5. 是否与现有模块的参数格式、提示风格、资源落盘方式保持一致。

## 9. 源码获取限制

- 寻找源码时不能去 cache 中找，如果需要源码且 `ref/` 目录下没有相关源码，请停止编码，并向用户请求对应的源码。

## 10. 文档生成规范

- **禁止生成总结文档**：完成功能后，不要生成总结性的 Markdown 文件（如 IMPROVEMENT_SUMMARY.md）
- **禁止生成快速参考卡**：不要生成 QUICKSTART.md、CHEATSHEET 类的文件
- 功能完成时直接向用户报告结果，无需额外的独立总结文件
- 只在用户明确要求时才生成辅助文档

## 11. 函数职责必须单一且确定

禁止新增或保留“多功能函数”。函数的行为、副作用和失败条件必须可以被一眼确定，不能依赖运行时状态隐式选择互斥行为。

### 强制规则

- `insert` / `create` 只允许插入；目标已存在时必须失败或显式报错，不得自动改成更新。
- `update` 只允许更新；目标不存在时必须失败或显式报错，不得自动改成插入。
- 禁止 `ensureXxx`、`upsertXxx`、`saveOrUpdateXxx`、`resolveOrCreateXxx` 这类同时包含查询、插入、更新、改名等多种副作用的入口。
- 禁止同一个函数内部根据“是否已存在”隐式选择插入或更新。
- 禁止用 `sync`、`fix`、`ensure` 等模糊动词掩盖实际的多分支写入行为。
- 调用方必须显式执行“先查询，再选择 create 或 update”的分支，使行为在调用点可审计。
- 函数名必须准确描述唯一职责；函数注释必须写明是否写库、写哪张表、失败条件是什么。
- 查询函数只允许接受明确的查询键（如 UUID、ID、名称），禁止接受携带可选字段的 DTO 后在内部推导默认值。
- 禁止在查询函数内部根据名称生成 UUID、补全渠道来源或做任何隐式规范化；这些步骤必须由调用方显式完成。
- 先查询后创建时，禁止在查询前构造完整候选对象；只有在查询未命中后才允许构造候选并调用 `create`，避免无意义的内存分配和额外计算。

### 示例

禁止：

```kotlin
fun ensureInGameProfile(userUUID: UUID, candidate: InGameProfileCandidate): InGameProfile {
    val existing = find(...)
    if (existing == null) {
        insert(...)
    } else {
        update(...)
    }
}
```

必须拆成：

```kotlin
fun findInGameProfileByUuid(uuid: UUID): InGameProfile?
fun createInGameProfile(...): InGameProfile   // 只插入
```

由调用方显式分支：

```kotlin
val existing = findInGameProfileByUuid(uuid)
if (existing == null) {
    createInGameProfile(...)
}
// 已存在则直接复用，不校验本次渠道名称是否一致
```

原因：隐式 upsert 会产生重复写入、覆盖正式绑定、难以回滚，并让调用方无法判断函数到底会插入还是更新。

## 12. 数据库表结构与访问层分层

- 所有核心 Exposed `Table` 定义必须放在 `api` 模块的 `icu.h2l.api.db.table` 下。
- `velocity` 的 `database` 包只允许放：
  - 数据库连接/配置；
  - 单表 database helper；
  - 存储抽象接口。
- 所有 database helper 统一放在 `icu.h2l.login.database.helper`，并统一命名为 `XxxDatabaseHelper`。
- 旧库结构迁移必须保留，但只能放在 `icu.h2l.login.database.migration`；禁止把 legacy 表定义放进 `api` 模块。
- 迁移器负责表名/列名升级和旧 `name/uuid` 数据迁移，不参与日常读写。
- 表结构统一使用新命名（`hzl_user`、`user_uuid` 等），迁移器负责把旧库升级到该结构。
- 单表 helper 统一命名为 `XxxDatabaseHelper`；数据库连接/生命周期管理类可使用 `XxxManager`；存储抽象接口可使用 `XxxStore`。
- `database` 包内必须按职责拆分，禁止重新制造一个同时管理 `hzl_user`、`in_game_profile`、`binding_code` 的混合 `DatabaseHelper`。
- 跨表业务编排必须放在 service 层（如 `VelocityHyperZoneUserService`），不得下沉到 database helper。
- 模块私有表保留在各自模块的 `api/db` 包中，不得为了“集中”而让核心反向依赖模块。

## 13. Credential、HZL User 与 InGameProfile 的职责边界

这三类对象必须严格分离，禁止再次混合：

### Credential

只允许承担：

- 证明当前渠道认证已通过；
- 保存渠道内部账号标识；
- 解析/写入渠道账号与 HZL User 的绑定关系（`getBoundUserUUID` / `bind` / `validateBind`）；
- 为“创建 HZL User”提供注册名；渠道游戏 UUID 只能用于附加 InGameProfile。

禁止：

- 在 Credential 中携带 `InGameProfileCandidate`；
- 通过 Credential 选择、写入、更新 InGameProfile；
- 在 attach / bind 流程中扫描 Credential 来推导游戏内身份。

### HZL User

- `User` 只表示 HZL 账号本身，字段只允许 `id`（userUUID）与 `activeInGameProfileId`；
- 新建 User 时，默认应使用 `defaultGameUuid(name)`（HZL 前缀离线 UUID）作为初始/默认 InGameProfile；该行为由 `remap.useHzlUuidAsDefaultIdentity` 控制且默认开启；
- 关闭该配置时，才允许沿用渠道建议 UUID 作为初始身份；
- 渠道名称/UUID 仍必须写入 InGameProfile，但只能作为附加身份，不得自动替换默认活动身份；
- 禁止在 `User` 上投影 GameProfile 的 `name` / `uuid`；需要游戏内身份时必须显式读取当前活动的 `InGameProfile`；
- 任何需要显示/转发游戏内名称或 UUID 的流程，必须明确调用 `getActiveInGameProfile(userUUID)` 或显式选择某条 `InGameProfile`。
- attach 成功后，核心必须把 `User` 对象与活动 `InGameProfile` 显式缓存到 `HyperZonePlayer` 实例上；
- `getAttachedGameProfile()` 等高频路径必须直接读取玩家对象缓存，不得再次查询数据库；
- `userService` 不得维护长期 User/InGameProfile 缓存；对象缓存的生命周期必须跟随 `HyperZonePlayer` 的创建与销毁；
- 切换活动 InGameProfile 后，必须同步更新已 attach 玩家对象上的活动身份缓存。

### HZL User 解析

- 只允许有一个明确入口从已提交凭证解析 HZL User；
- 该入口只返回 HZL User，不得 attach，不得读写 InGameProfile；
- 推荐名称：`resolveAuthenticatedUser(...)`；
- 禁止重新引入“解析 + attach + 写 InGameProfile”的混合入口。

### InGameProfile

- `InGameProfile` 只表示游戏内身份，字段仅允许 `id` / `user_uuid` / `name` / `name_lower` / `uuid`；
- 禁止把 `channel` / `channel_entry` / `channel_account_id` 写入 `InGameProfile` 表；渠道来源属于渠道模块自己的账号表或临时候选；
- 多个渠道返回相同 UUID/名称时必须复用同一条 InGameProfile，而不是按渠道重复建行；
- UUID 在整个 InGameProfile 表中全局唯一，同一个 User 内也不允许重复；
- UUID 相同但名称不同视为“改名”，必须由玩家显式手动操作，登录流程不得自动改名；
- 渠道游戏内身份只能由核心内部根据已提交凭证推导，不得开放外部提交入口；
- 禁止在 API 中提供 `submitInGameProfileCandidate(...)`、`LoginHandleResult.inGameProfileCandidate` 等模块可写入候选的通道；
- 模块只允许提交认证凭证；凭证中的认证名称/UUID 仅作为认证事实，由核心自行转换为内部候选；
- 只有在 HZL User 确定后，才允许按 UUID 显式执行 `find`，不存在时才 `create`；
- 已存在的记录禁止更新其 `name` / `uuid`，按 UUID 命中后直接复用；
- 登录阶段不校验渠道名称与内部记录是否一致；只要认证成功，必须允许玩家继续进入游戏；
- 名称/UUID 的变更只能由玩家显式手动操作（相关命令当前未实现），不得由登录流程隐式完成；
- 禁止提供可修改 `name` / `uuid` 的通用 `updateInGameProfile(...)`；
- 禁止由 Credential 或 attach 流程隐式决定使用哪一条 InGameProfile。

### attach / bind

- `attachUser(...)` 只允许 attach HZL User 并触发事件；
- `bindSubmittedCredentials(...)` 只允许写渠道账号与 HZL User 的绑定关系；
- 两者都不得读取或写入 InGameProfile；
- InGameProfile 的记录必须由独立的登录编排步骤完成，且只允许 `create` 或复用，不得更新既有身份。

原因：认证凭证只回答“你是谁”，HZL User 回答“这是哪个账号”，InGameProfile 回答“这次进入游戏使用哪个游戏身份”。三者混在一个入口中会导致状态不可审计、错误地切换身份，并产生难以回滚的绑定副作用。

