# Profile Skin 模块

`profile-skin` 是 `HyperZoneLogin` 的独立子模块，用于在档案替换链路中修复与应用玩家皮肤属性。

## 功能

- 在 `auth-yggd` 成功认证后抛出 `ProfileSkinPreprocessEvent`
- 从上游 `GameProfile` 提取 `textures` / 皮肤源 URL / 模型
- 优先缓存上游已签名的皮肤数据
- 当上游只有未签名 `textures` 且可解析出 `skinUrl` 时，参考 `ref/SkinsRestorer/shared/src/main/java/net/skinsrestorer/shared/connections/MineSkinAPIImpl.java` 的 MineSkin 处理思路进行修复
- 当 MineSkin 的 URL 模式无法直接读取源图（例如返回 `invalid_image` / `Invalid image file size: undefined`）时，可自动退回上传模式重试
- 解析 MineSkin 恢复结果时兼容旧版 `data.texture` 与 SkinsRestorer 当前使用的 `skin.texture.data` 成功响应结构，并强制要求 `value` 与 `signature` 同时存在
- 预处理阶段将皮肤数据缓存到 `skin_cache`，并在 profile 绑定完成后写入 `skin_profile`
- 在 `ProfileSkinPreprocessEvent` 阶段记录最近一次可用的 self `textures`，并在连接可写时直接补发一次 self `ADD_PLAYER`
- 当上游初始 `textures` 缺失或不可用于补发时，若玩家已经绑定 `profile`，则回退到该 `profile` 的缓存皮肤继续补发 self `ADD_PLAYER`
- 在 `PlayerFinishConfigurationEvent` 后根据最近缓存的 self `textures` 再 replay 一次 self `ADD_PLAYER`，避免 vanilla 客户端切换 configuration 生命周期后丢失自己的皮肤资料
- 监听 `ServerLoginSuccessEvent`，把登录成功包 UUID 改写职责从核心转移到皮肤修复模块
- 在 `ToBackendPacketReplacer` 与 `GameProfileRequestEvent` 的最终替换阶段，通过 `ProfileSkinApplyEvent` 将缓存后的 `textures` 注入最终档案

## 配置文件

模块启动后会在主插件数据目录下生成：

- `profile-skin.conf`

主要配置项：

- `enabled`：是否启用模块
- `preferUpstreamSignedTextures`：是否优先使用并缓存上游已签名 `textures`
- `restoreUnsignedTextures`：遇到未签名 `textures` 时是否尝试修复
- `mineSkin.method`：`URL` 或 `UPLOAD`
- `mineSkin.retryUploadOnUrlReadFailure`：URL 模式遇到 MineSkin 远端读图失败时，是否自动改走上传模式

说明：self replay 能力仍受核心 `misc.enableReplaceGameProfile` 开关约束；只有开启档案替换时，模块才会向客户端补发 self `ADD_PLAYER`。

## 与 SkinsRestorer 的对比

- 当前 `ref/SkinsRestorer` 源码里的 MineSkin 入口是 `/v2/generate`，成功响应会把 `skin.texture.data.value` 与 `skin.texture.data.signature` 直接组装成必须带签名的 `SkinProperty`
- `profile-skin` 原先上传/URL 恢复逻辑使用的是旧版 `generate/url` 与 `generate/upload` 返回结构 `data.texture`，并允许把缺失 `signature` 的结果解析为 `ProfileSkinTextures`
- 现已对齐为：MineSkin 恢复结果必须包含非空 `signature` 才能进入缓存，同时兼容两种响应结构，避免把无法注入到 Velocity 的半残 `textures` 持久化
- 相关第三方参考与改编说明见根目录 `THIRD_PARTY_NOTICES.md` 中的 `SkinsRestorer` 条目

## API 事件

### `ProfileSkinPreprocessEvent`

认证成功后由 `auth-yggd` 抛出，供其他模块自定义预处理：

- `hyperZonePlayer`
- `authenticatedProfile`
- `entryId`
- `serverUrl`
- 可写字段：`source`、`textures`

### `ProfileSkinApplyEvent`

在最终构造转发给后端的 `GameProfile` 时抛出：

- `hyperZonePlayer`
- `baseProfile`
- 可写字段：`textures`

### `ServerLoginSuccessEvent`

在代理层即将向客户端写出登录成功包时抛出：

- `hyperZonePlayer`
- `currentUuid`
- `currentUsername`
- `currentProperties`
- 可写字段：`rewritePacket`、`uuid`、`username`、`properties`

该事件与 `ServerLoginSuccessPacket` 对齐。`profile-skin` 会在这里请求回写整包，并把其中的 UUID 改为 `clientOriginalUUID`，从而把原先核心中的皮肤修复相关副作用迁移到模块内部。

### `HyperZonePlayerProfileAttachedEvent`

当 `HyperZonePlayer` 成功 attach 到正式 `Profile` 后抛出：

- `hyperZonePlayer`
- `profile`

`profile-skin` 会在此时把预处理阶段暂存的皮肤结果关联到 `skin_profile`，从而解决“预处理早于 attach”导致的时序问题。

## 数据表

默认表名：

- `${tablePrefix}skin_cache`
- `${tablePrefix}skin_profile`

`skin_cache` 主要字段：

- `id`
- `source_hash`
- `source_cache_eligible`
- `skin_url`
- `skin_model`
- `texture_value`
- `texture_signature`
- `updated_at`

`skin_profile` 主要字段：

- `profile_id`
- `skin_id`
- `updated_at`

说明：

- `skin_cache` 只存皮肤本体，不再直接存 `profile_id`
- `skin_profile` 只负责 `profile -> skin` 关联
- `source_hash = SHA-256(originalSkinUrl|model)` 继续用于同源复用缓存判断

## 接入链路

1. 玩家在 `auth-yggd` 完成认证
2. `YggdrasilAuthModule` 保存初始 `GameProfile` 到 `HyperZonePlayer`
3. `YggdrasilAuthModule` 抛出 `ProfileSkinPreprocessEvent`
4. `profile-skin` 模块完成提取 / 修复 / `skin_cache` 缓存，并尝试立即补发一次 self `ADD_PLAYER`
5. 核心完成 `HyperZonePlayer -> Profile` attach 后抛出 `HyperZonePlayerProfileAttachedEvent`
6. `profile-skin` 将预处理得到的 `skinId` 写入 `skin_profile`
7. 客户端完成 configuration 后，`profile-skin` 再 replay 一次 self `ADD_PLAYER`
8. 核心在写出登录成功包前抛出 `ServerLoginSuccessEvent`
9. `profile-skin` 请求把登录成功包 UUID 改为 `clientOriginalUUID`
10. `ToBackendPacketReplacer` 与核心最终替换链路抛出 `ProfileSkinApplyEvent`
11. 模块先查 `skin_profile`，再回到 `skin_cache` 取回 `textures` 并写回最终 `GameProfile`

