# vc皮肤分析

## 背景

当前 `velocity` 模块里的皮肤相关尝试，核心目标是：

1. 登录阶段继续使用随机 `GameProfile`（例如 `HZLxxxxxx`）以满足前置一致性校验；
2. 但仍然希望客户端能够显示正确皮肤；
3. 因此尝试了两条路径：
   - 在登录/后端转发链路里把 `textures` 叠加到最终 `GameProfile`；
   - 通过 tablist 添加一个带正确 profile 的虚拟 entry，尝试让玩家“看到自己皮肤”。

实测后，tablist 虚拟 entry 方案没有达到目标，因此需要从 Velocity 源码角度确认：

- 为什么必须把皮肤放进 Velocity 的真实 player profile；
- 为什么 UUID 必须和玩家登录时使用的 UUID 一致；
- 为什么单独往 tablist 塞 entry 不够。

---

## 项目内现有链路

### 1. 登录阶段强制使用随机档案

`velocity/src/main/kotlin/icu/h2l/login/listener/EventListener.kt`

- `onStartAuth(event: OpenStartAuthEvent)` 中会生成 `RemapUtils.randomProfile()`；
- 该随机 profile 会写入 `event.gameProfile`；
- 同时保存到 `HyperZonePlayer.temporaryForwardingProfile`，供后续 lobby/fallback 服转发时复用。

### 2. 预登录阶段会校验随机 name / UUID

同文件 `onPreLogin(event: GameProfileRequestEvent)` 中：

- 要求 `gameProfile.name` 以 `HZL` 前缀开头；
- 要求 `gameProfile.id == RemapUtils.genUUID(name, REMAP_PREFIX)`；
- 这意味着登录阶段使用的 `GameProfile` 身份是强约束，不是可随意替换成“真实玩家名/UUID”的。

### 3. 皮肤修复链路本质上只补 `textures`

项目中皮肤修复的职责主要在：

- `velocity/src/main/kotlin/icu/h2l/login/player/ProfileSkinApplySupport.kt`
- `profile-skin/src/main/kotlin/icu/h2l/login/profile/skin/service/ProfileSkinService.kt`

这条链路的作用是：

- 以某个 base profile 为基础；
- 触发 `ProfileSkinApplyEvent`；
- 从缓存、初始认证 profile、MineSkin 修复等来源拿到 `textures`；
- 最后只把 `textures` 属性叠加回去。

所以皮肤链路本身并不要求把 `name/UUID` 改成真实玩家值。

---

## Velocity 源码里的关键结论

以下源码均来自 `ref/Velocity`。

---

### 一、真实玩家身份是在登录阶段固定的

关键类：

- `proxy/src/main/java/com/velocitypowered/proxy/connection/client/AuthSessionHandler.java`
- `proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java`
- `proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/ServerLoginSuccessPacket.java`

#### 1. `GameProfileRequestEvent` 的结果直接用于创建 `ConnectedPlayer`

在 `AuthSessionHandler.activated()` 中：

```java
ConnectedPlayer player = new ConnectedPlayer(server, profileEvent.getGameProfile(),
    mcConnection, ..., inbound.getIdentifiedKey());
```

这说明：

> `GameProfileRequestEvent` 最终产出的 `event.gameProfile`，会直接成为 Velocity 内部真实玩家对象的 profile。

也就是说，登录阶段如果想让客户端把某份皮肤认成“这名玩家自己的皮肤”，最重要的落点不是 tablist，而是这里进入 `ConnectedPlayer` 的那份 `GameProfile`。

---

#### 2. `ConnectedPlayer` 的 name / UUID / properties 全部绑定在 `profile` 上

`ConnectedPlayer.java` 中：

```java
public String getUsername() {
  return profile.getName();
}

public UUID getUniqueId() {
  return profile.getId();
}

public GameProfile getGameProfile() {
  return profile;
}

public List<GameProfile.Property> getGameProfileProperties() {
  return this.profile.getProperties();
}

public void setGameProfileProperties(List<GameProfile.Property> properties) {
  this.profile = profile.withProperties(Preconditions.checkNotNull(properties));
}
```

这说明：

> 对 Velocity 来说，玩家的用户名、UUID、皮肤属性（textures）都归属于同一份真实 `ConnectedPlayer.profile`。

---

#### 3. 登录成功包会把这份真实 profile 发给客户端

在 `AuthSessionHandler.completeLoginProtocolPhaseAndInitialize()` 中：

```java
ServerLoginSuccessPacket success = new ServerLoginSuccessPacket();
success.setUsername(player.getUsername());
success.setProperties(player.getGameProfileProperties());
success.setUuid(player.getUniqueId());
mcConnection.write(success);
```

而 `ServerLoginSuccessPacket.encode()` 中会写：

```java
ProtocolUtils.writeUuid(buf, uuid);
ProtocolUtils.writeString(buf, username);
ProtocolUtils.writeProperties(buf, properties);
```

因此：

> 客户端在登录成功时，会拿到这名玩家自己的 UUID / username / properties（包含 textures）。

这一步是玩家本地“自己是谁、自己长什么样”的核心初始化来源。

---

### 二、tablist 虚拟 entry 不能替代真实玩家身份

关键类：

- `api/src/main/java/com/velocitypowered/api/proxy/player/TabList.java`
- `api/src/main/java/com/velocitypowered/api/proxy/player/TabListEntry.java`
- `proxy/src/main/java/com/velocitypowered/proxy/tablist/VelocityTabList.java`
- `proxy/src/main/java/com/velocitypowered/proxy/tablist/VelocityTabListEntry.java`
- `proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/UpsertPlayerInfoPacket.java`

#### 1. tablist entry 按 `GameProfile.id` 识别

`VelocityTabList.addEntry()` 中，内部 map 是按：

```java
entry.getProfile().getId()
```

来存储的。

所以如果 tablist 里加入的是一个 synthetic UUID：

- 客户端会把它看成“另一个玩家条目”；
- 它不是当前本地玩家自己；
- 最多影响 tab/player info 里的附加项，不等于重定义本地玩家实体。

因此：

> 用不同 UUID 的虚拟 entry，不可能直接替代客户端眼中的“自己”。

---

#### 2. 同 UUID 的已存在 entry，Velocity 不会重发 profile 本体

`VelocityTabList.addEntry()` 的语义非常关键：

- 如果 entry 是第一次出现，才会加上 `ADD_PLAYER`；
- 只有在 `ADD_PLAYER` 分支里，才会把 `entry.getProfile()` 写入包；
- 如果 entry 已存在，则只会发这些增量更新：
  - `UPDATE_DISPLAY_NAME`
  - `UPDATE_LATENCY`
  - `UPDATE_GAME_MODE`
  - `UPDATE_LISTED`
  - `UPDATE_LIST_ORDER`
  - `UPDATE_HAT`
  - `INITIALIZE_CHAT`

也就是说：

> 对同 UUID 的已存在 tablist entry，Velocity 不会再次发送完整 `GameProfile`，也不会重新发送 `textures` 作为 profile 本体的一部分。

---

#### 3. `textures` 只在 `ADD_PLAYER` 时进入 `UpsertPlayerInfoPacket`

在 `UpsertPlayerInfoPacket.Action.ADD_PLAYER` 里：

```java
ProtocolUtils.writeString(buf, info.profile.getName());
ProtocolUtils.writeProperties(buf, info.profile.getProperties());
```

而普通 `UPDATE_*` 分支不写 properties。

这意味着：

> 如果只是对已存在 entry 做更新，新的 `textures` 不会通过 `ADD_PLAYER` 的方式重新下发。

因此，tablist 虚拟 entry 方案即使使用同 UUID，也不一定能把新的皮肤信息重新灌给客户端。

---

## 为什么 UUID 必须是玩家进入时的 UUID

从上面的登录链路可以推出：

1. 客户端在登录成功时，会把 `uuid + username + properties` 绑定为“我自己”的身份；
2. 这份数据来源于 `ConnectedPlayer.profile`；
3. 因此，若想让客户端把某份 `textures` 认作“我自己的皮肤”，它必须属于这条真实身份链路。

也就是说：

- **UUID 不同**：客户端只会把它看成另一个玩家；
- **UUID 相同但只是 tablist 增量更新**：Velocity 通常也不会重新发送 profile 本体；
- **只有登录阶段就进入真实 `ConnectedPlayer.profile` 的那份 UUID / properties**，才最有机会被客户端当作“自己”的皮肤。

所以会表现成：

> 必须置入到 Velocity 的真实 player profile 中，而且 UUID 必须是玩家进入时采用的那一个，客户端才会正确把这份皮肤认成自己。

---

## 对当前项目的直接结论

### 结论 1：`SelfSkinTabListManager` 不是正解

`velocity/src/main/kotlin/icu/h2l/login/manager/SelfSkinTabListManager.kt`

这个方案的问题在于：

- 如果使用 synthetic UUID，它只是一个“假玩家 entry”；
- 如果试图与真实 UUID 相同，又会受限于 `VelocityTabList.addEntry()` 对已有 entry 仅做增量更新，不重发 profile 本体；
- 因此它不能稳定地影响“玩家自己”的皮肤显示。

### 结论 2：正确落点仍然是 `GameProfileRequestEvent`

项目里真正应该控制的点，是：

- `EventListener.onPreLogin(event: GameProfileRequestEvent)`

这里产出的 `event.gameProfile` 会进入：

- `ConnectedPlayer.profile`
- `ServerLoginSuccessPacket`
- 后续客户端对“自己身份”的初始化链路

因此最合理的策略是：

1. 保留当前随机 `name/UUID`（满足项目前置校验）；
2. 以当前 `incomingProfile` 为 base；
3. 只把 `textures` 叠加上去；
4. 再回写到 `event.gameProfile`。

这正好与当前项目里的皮肤修复思路一致。

---

## 推荐实现方向

### 推荐做法

在 `GameProfileRequestEvent` 阶段：

- 使用当前登录链路里的 `incomingProfile` 作为 base；
- 调用皮肤修复链路，只补 `textures`；
- 不把 profile 改成真实玩家名；
- 不依赖 tablist 虚拟 entry 来“补显示”。

即目标应该是：

- `name`：仍然是随机登录名（例如 `HZLxxxxxx`）
- `uuid`：仍然是当前登录采用的 remap UUID
- `properties`：带有正确 `textures`

### 不推荐做法

- 指望通过额外 tablist entry 改变“自己”的皮肤显示；
- 指望使用不同 UUID 的 profile 让客户端把它认成自己；
- 指望在同 UUID 已存在时，通过 tablist 普通更新重新发送 profile textures。

---

## 最终总结

从 Velocity 源码可确认：

1. 玩家自己的皮肤显示，核心依赖登录阶段建立的真实 `ConnectedPlayer.profile`；
2. `ServerLoginSuccessPacket` 会把这份真实 profile（UUID / name / properties）发给客户端；
3. tablist 里的虚拟 entry 只是附加玩家列表项，不能稳定替代本地玩家实体；
4. 即便使用同 UUID，`VelocityTabList` 对已存在 entry 也通常只做增量更新，不会重新发送完整 profile；
5. 因此，若要正确显示皮肤，必须让皮肤进入真实登录身份链路；
6. 在本项目里，最正确的落点就是 `GameProfileRequestEvent` 阶段，对当前登录 profile 只叠加 `textures`，不改随机 name/UUID。

---

## 对应源码位置索引

### 项目内
- `velocity/src/main/kotlin/icu/h2l/login/listener/EventListener.kt`
- `velocity/src/main/kotlin/icu/h2l/login/player/ProfileSkinApplySupport.kt`
- `velocity/src/main/kotlin/icu/h2l/login/manager/SelfSkinTabListManager.kt`
- `velocity/src/main/kotlin/icu/h2l/login/inject/network/netty/ToBackendPacketReplacer.kt`

### Velocity 源码
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/connection/client/AuthSessionHandler.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/ServerLoginSuccessPacket.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/tablist/VelocityTabList.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/tablist/VelocityTabListEntry.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/UpsertPlayerInfoPacket.java`
- `ref/Velocity/api/src/main/java/com/velocitypowered/api/proxy/player/TabList.java`
- `ref/Velocity/api/src/main/java/com/velocitypowered/api/proxy/player/TabListEntry.java`

