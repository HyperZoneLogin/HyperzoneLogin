# Profile Skin 模块

`profile-skin` 是 `HyperZoneLogin` 的独立子模块，用于在档案替换链路中修复与应用玩家皮肤属性。

## 功能

- 在 `auth-yggd` 成功认证后抛出 `ProfileSkinPreprocessEvent`
- 从上游 `GameProfile` 提取 `textures` / 皮肤源 URL / 模型
- 优先缓存上游已签名的皮肤数据
- 当上游只有未签名 `textures` 且可解析出 `skinUrl` 时，按 `ref/skin/skinrestorer/SkinRestorerFlows.java` 的思路调用 MineSkin 修复
- 当 MineSkin 的 URL 模式无法直接读取源图（例如返回 `invalid_image` / `Invalid image file size: undefined`）时，可自动退回上传模式重试
- 将结果缓存到数据库表 `profile_skin_cache`
- 在 `ProfileSkinPreprocessEvent` 阶段记录最近一次可用的 self `textures`，并在连接可写时直接补发一次 self `ADD_PLAYER`
- 在 `PlayerFinishConfigurationEvent` 后根据最近缓存的 self `textures` 再 replay 一次 self `ADD_PLAYER`，避免 vanilla 客户端切换 configuration 生命周期后丢失自己的皮肤资料
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

## 数据表

默认表名：

- `${tablePrefix}profile_skin_cache`

主要字段：

- `profile_id`
- `source_hash`
- `source_cache_eligible`
- `skin_url`
- `skin_model`
- `texture_value`
- `texture_signature`
- `updated_at`

说明：当前没有单独的“镜像 URL 映射表”。原因是 `profile_skin_cache` 已经通过 `source_hash = SHA-256(originalSkinUrl|model)` 缓存恢复后的 `textures`，并借助 `source_cache_eligible` 区分“可作为同源复用缓存的已恢复/可信结果”和“仅供当前 profile 回退使用的 fallback 结果”。因此同一原始源图 URL 在后续玩家命中时，会直接复用可复用缓存，而不需要再次让 MineSkin 访问原地址。

## 接入链路

1. 玩家在 `auth-yggd` 完成认证
2. `YggdrasilAuthModule` 保存初始 `GameProfile` 到 `HyperZonePlayer`
3. `YggdrasilAuthModule` 抛出 `ProfileSkinPreprocessEvent`
4. `profile-skin` 模块完成提取 / 修复 / 缓存，并尝试立即补发一次 self `ADD_PLAYER`
5. 客户端完成 configuration 后，`profile-skin` 再 replay 一次 self `ADD_PLAYER`
6. `ToBackendPacketReplacer` 与核心最终替换链路抛出 `ProfileSkinApplyEvent`
7. 模块从缓存取回 `textures` 并写回最终 `GameProfile`

