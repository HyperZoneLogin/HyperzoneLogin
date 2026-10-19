# HyperZoneLogin Agent Rules

本文件记录 HyperZoneLogin 登录链路的强制工程规范。

## 1. 登录流程必须使用明确语义

HyperZoneLogin 的登录链路属于**严格状态机**，禁止使用会混淆状态边界的模糊命名或混合流程。

### 强制规则

- `resolve` 只允许表示：**按既有 `profileId` 解析已有 Profile**。
- `create` 只允许表示：**以指定注册名尝试新建 Profile**。
- `canResolve` 只允许表示：**既有 `profileId` 是否可解析**。
- `canCreate` 只允许表示：**给定 `(userName, uuid?)` 是否允许建档**。

### 明确禁止

以下语义在本项目中视为不合规，禁止重新引入：

- `resolveOrCreate`
- `canResolveOrCreate`
- 任何把 `resolve / create / rename / 回退匹配 / 自动改名` 混在一个入口中的 API

原因：登录流程必须可审计、可推导、可断言，不能依赖“也许解析、也许创建、也许顺便改名”的模糊副作用。

## 2. `clientOriginalName` 与 `registrationName` 必须严格区分

- `clientOriginalName`：客户端初始上报的**不可信原始名**，创建后不得改写。
- `registrationName`：当前等待区会话在“若要新建 Profile”时应使用的**待建档注册名**，可由 `/rename` 在等待区中显式修改。

禁止把 `clientOriginalName` 当成：

- 可信认证结果
- 正式游戏身份
- 当前可变注册名

## 3. `/rename` 的职责边界

`/rename` 只能用于：

- 当前玩家仍在等待区；
- 当前玩家尚未 attach 正式 Profile；
- 当前会话存在待建档上下文；
- 新名称已经通过严格的 `canCreate(...)` 检查。

`/rename` 触发后应：

1. 修改事件发起者当前会话的 `registrationName`；
2. 触发 `event.auth.LoginRenameEvent`；
3. 仅让**事件发起者本人**的等待区流程重试；
4. 不得影响其他等待区玩家。

额外强调：

- `/rename` **不得**作为 `attachProfile` 之后的游戏区身份热改入口；
- 已 attach 正式 Profile 后的任何 name / UUID 热改，若未来存在，只能由**核心内部受控流程**驱动，不得由等待区命令直接触发；
- 等待区的名称、UUID、凭证组合都属于**不可信且高变化的临时态**，不得草率把等待区操作外溢到正式游戏区身份。

## 4. 已落库绑定关系不得被 rename 静默改写

`rename` 只允许修改**当前等待区会话的临时待建档状态**。

禁止：

- 因 `/rename` 直接修改已存在 Profile 的正式名称；
- 因 `/rename` 直接改写已落库的正式认证绑定；
- 用 `/rename` 充当 merge / remap / rebind 工具。

同理，`/reUUID` 也只允许服务于等待区临时建档流程，禁止把它扩展成已 attach Profile 的在线 UUID 热改入口。

## 5. 认证凭证规范

认证模块提交到核心层的 `HyperZoneCredential` 必须遵守：

- `getBoundProfileId()` 只表示当前是否已绑定；
- 未绑定凭证如需参与严格建档，可通过 `getSuggestedProfileCreateUuid()` 显式提供建议 UUID；
- 如需支持等待区 rename，只能通过 `onRegistrationNameChanged(...)` 修改**临时状态**；
- 不得在 rename 时隐式篡改正式落库绑定。

## 5.1 `attachProfile` 后的游戏区流程边界

`attachProfile` 之后，玩家身份已进入正式 Profile 语义。

此阶段若存在 GameProfile 显示优化、正式 name/UUID 补偿同步、切服后身份一致性修复等需求，必须遵守：

- 只允许由核心层**内部受控机制**处理；
- 必须与等待区建档/改名/重算 UUID 流程彻底隔离；
- 不得复用 `/rename`、`/reUUID`、等待区 credential 回调等不可信入口直接驱动；
- 设计与实现时必须明确标注“这是正式游戏区流程，不是等待区流程”。

## 5.2 `VelocityHyperZoneProfileService` / `DatabaseHelper` 的禁止越界项

`VelocityHyperZoneProfileService` 与相关 `DatabaseHelper` 默认只承担：

- 既有 Profile 读取；
- 新 Profile 创建；
- attach / bind 所需业务逻辑；
- 围绕创建/读取的必要校验与查询辅助。

除非需求被**明确、单独、强语义**地说明，否则禁止：

- 在这些类中新增“修改现有 Profile 数据”的接口；
- 在这些类中直接改写既有 Profile 的正式 `name` / `UUID`；
- 把运行时显示修正、Velocity 内存补偿同步、等待区临时态修正，错误地下沉为正式 Profile 数据更新。

## 6. 等待区放行条件

玩家离开等待区必须同时满足：

- 认证已通过；
- 正式 Profile 已 attach。

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

