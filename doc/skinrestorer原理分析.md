# SkinRestorer 原理分析

## 背景

你提到的理解基本是对的：`SkinRestorer` 在 **不切换服务器、不让玩家重连** 的前提下刷新皮肤时，核心不是“运行中重新定义客户端的 self UUID / self name”，而是：

1. 先在服务端侧把这名玩家对应的 `GameProfile/textures` 改掉；
2. 对**别人视角**，强制客户端把这个玩家当作“旧实体消失，再按新资料重新出现”；
3. 对**自己视角**，先把 self `PlayerInfo` 重新塞进客户端，再发一个 `respawn` 风格的重建包，让客户端重建 `LocalPlayer`，从而丢掉旧缓存并重新按新的 `PlayerInfo` 取皮肤。

所以大家口语里说的“复活玩家换肤”，更准确地说其实是：

> **用一次客户端侧的 `respawn` / 本地玩家重建，配合 `PlayerInfo REMOVE/ADD`，来迫使客户端重新读取自己的皮肤来源。**

---

## 先说结论

### 结论 1：SkinRestorer 走的是“两条刷新链”

不是一条包就能搞定，而是分成：

- **别人怎么看你**：`PlayerInfo` 更新 + 实体重刷；
- **你自己怎么看自己**：self `PlayerInfo` 更新 + `respawn` 触发本地玩家重建。

### 结论 2：`respawn` 的作用不是改“我是谁”，而是清掉旧的 self 渲染缓存

从客户端源码看，`respawn` 会重建一个新的 `LocalPlayer`，但它仍然使用原本保存好的 `localGameProfile`。

也就是说：

- `respawn` **不会**重新定义客户端认知中的 self UUID / self name；
- 它真正起的作用是：
  - 重建本地玩家对象；
  - 让旧 `AbstractClientPlayer.playerInfo` 缓存失效；
  - 之后再次通过 UUID 去 `playerInfoMap` 取一份新的 `PlayerInfo`；
  - 这份新的 `PlayerInfo.profile.properties` 里已经是新的 `textures`。

### 结论 3：只发 self `ADD_PLAYER` 往往不够稳定，所以还要补 `respawn`

原因在于客户端有两层缓存：

1. `AbstractClientPlayer.playerInfo` 会缓存第一次拿到的 `PlayerInfo`；
2. `PlayerInfo.getSkin()` 里又会缓存 `skinLookup`。

因此如果 self 旧缓存已经建立，仅仅再发一次 `ADD_PLAYER`，未必能让当前本地玩家立刻换皮。

而 `respawn` 通过“重建新的 `LocalPlayer` 实例”，就绕过了这个问题。

---

## 一、SkinRestorer 在 Bukkit/Spigot 侧到底做了什么

### 1. 入口：`SkinApplierBukkit.applySkinSync(...)`

文件：`ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/SkinApplierBukkit.java`

关键逻辑是：

```text
applyAdapter.applyProperty(player, property);

if (settingsManager.getProperty(AdvancedConfig.TELEPORT_REFRESH)) {
    teleportOtherRefresh(player);
} else {
    normalOtherRefresh(player);
}

refresh.refresh(player);
```

这里可以直接拆成三步：

1. `applyAdapter.applyProperty(player, property)`
   - 先把服务端这边玩家资料上的皮肤属性改掉；
2. `normalOtherRefresh(...)` / `teleportOtherRefresh(...)`
   - 刷新**别人眼里**的这个玩家；
3. `refresh.refresh(player)`
   - 刷新**玩家自己眼里**的自己。

这已经说明：

> SkinRestorer 明确把“别人看到的刷新”和“自己看到的刷新”分成了两套处理。

---

### 2. 刷新别人：`hide/show` 或 `resendInfo + teleport`

同文件里：

#### 普通路径

```text
private void normalOtherRefresh(Player player) {
    for (Player otherPlayer : getSeenByPlayers(player)) {
        hideAndShow(otherPlayer, player);
    }
}
```

#### 备选路径

```text
private void teleportOtherRefresh(Player player) {
    for (Player otherPlayer : getSeenByPlayers(player)) {
        refresh.resendInfoPackets(player, otherPlayer);
    }

    Location location = player.getLocation();
    player.teleport(...far away...);
    player.teleport(location);
}
```

含义分别是：

- `hide/show`
  - 借 Bukkit/Paper 的玩家可见性机制，让其他客户端把这个玩家实体先移除、再重新出现；
  - 从效果上看，相当于“别人客户端上的你被重刷了一次”。
- `resendInfoPackets + 双传送`
  - 先把 tab/player info 资料补发给别人；
  - 再用传送触发客户端对实体状态的重新同步。

所以对**别人视角**来说，SkinRestorer 的思路很朴素：

> 让别人的客户端重新建立“这个 UUID 对应玩家”的展示资料与实体展示。

---

### 3. 刷新自己：`SpigotSkinRefresher.refresh(...)`

文件：`ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/refresher/SpigotSkinRefresher.java`

最关键的顺序是：

```text
resendInfoPackets(player, player);
...
sendPacket(player, respawn);
...
sendPacket(player, pos);
sendPacket(player, slot);
...
player.updateInventory();
...
OPRefreshUtil.refreshOP(player, adapter);
```

而 `resendInfoPackets(player, player)` 的内容是：

```text
removePlayer = new PacketPlayOutPlayerInfo(REMOVE_PLAYER, List.of(entityPlayer));
addPlayer = new PacketPlayOutPlayerInfo(ADD_PLAYER, List.of(entityPlayer));

sendPacket(toSendTo, removePlayer);
sendPacket(toSendTo, addPlayer);
```

也就是它会先对**玩家自己**发送：

1. `REMOVE_PLAYER`
2. `ADD_PLAYER`

然后再发：

3. `RESPAWN`
4. 位置同步包
5. 手持栏位同步包
6. 能力 / 血量 / 背包等补同步

这条顺序非常关键，因为它表明：

> SkinRestorer 的 self 刷新，本质上是“先替换 self `PlayerInfo`，再让客户端重建本地玩家并回到稳定状态”。

---

### 4. Paper 分支

文件：`ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/refresher/PaperSkinRefresher.java`

Paper 分支没有手写整套包，而是：

```text
refreshPlayerMethod.invoke(player);
triggerHealthUpdate.accept(player);
```

也就是直接调用 `CraftPlayer.refreshPlayer()`。

从 SkinRestorer 当前仓库里，**可以确认它把 self 刷新委托给了 Paper 自带实现**；但 `refreshPlayer()` 内部细节不在当前工作区源码里，因此下面分析 MC 客户端路径时，以 `SpigotSkinRefresher` 这条显式包链为主。

---

## 二、放到 Minecraft 客户端里，这条路径到底怎么走

下面把它翻译成 vanilla 客户端的真实处理链。

---

### 1. 客户端先有两份“和自己相关”的资料

要理解 SkinRestorer，必须先区分：

#### A. `localGameProfile`：客户端认知中的“我是谁”

登录阶段：

文件：`ref/mc/net/minecraft/network/protocol/login/ClientboundLoginFinishedPacket.java`

```text
public record ClientboundLoginFinishedPacket(GameProfile gameProfile)
```

文件：`ref/mc/net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java`

```text
public void handleLoginFinished(final ClientboundLoginFinishedPacket packet) {
   GameProfile localGameProfile = packet.gameProfile();
   ...
   new CommonListenerCookie(..., localGameProfile, ...)
}
```

这份 `GameProfile` 之后会进入 `ClientPacketListener`，成为本地身份来源。

#### B. `PlayerInfo`：客户端渲染皮肤时真正优先读取的资料

文件：`ref/mc/net/minecraft/client/player/AbstractClientPlayer.java`

```text
protected @Nullable PlayerInfo getPlayerInfo() {
   if (this.playerInfo == null) {
      this.playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(this.getUUID());
   }
   return this.playerInfo;
}
```

```text
public PlayerSkin getSkin() {
   PlayerInfo info = this.getPlayerInfo();
   return info == null ? DefaultPlayerSkin.get(this.getUUID()) : info.getSkin();
}
```

也就是说，客户端渲染“自己皮肤”时，优先看的不是 `LocalPlayer` 构造时那份 profile，而是：

> `UUID -> ClientPacketListener.playerInfoMap -> PlayerInfo -> PlayerInfo.profile.properties(textures)`

这就是 SkinRestorer 之所以必须去动 `PlayerInfo` 的根本原因。

---

### 2. `ADD_PLAYER` 会把新的 profile/textures 放进 `playerInfoMap`

文件：`ref/mc/net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java`

```text
ADD_PLAYER((entry, input) -> {
   String name = ByteBufCodecs.PLAYER_NAME.decode(input);
   PropertyMap properties = ByteBufCodecs.GAME_PROFILE_PROPERTIES.decode(input);
   entry.profile = new GameProfile(entry.profileId, name, properties);
})
```

客户端接收端：

文件：`ref/mc/net/minecraft/client/multiplayer/ClientPacketListener.java`

```text
for (ClientboundPlayerInfoUpdatePacket.Entry entry : packet.newEntries()) {
   PlayerInfo playerInfo = new PlayerInfo(Objects.requireNonNull(entry.profile()), this.enforcesSecureChat());
   if (this.playerInfoMap.putIfAbsent(entry.profileId(), playerInfo) == null) {
      ...
   }
}
```

而 `REMOVE` 则是：

```text
for (UUID profileId : packet.profileIds()) {
   PlayerInfo info = this.playerInfoMap.remove(profileId);
   ...
}
```

所以 self 刷新的前半段可以翻译成：

1. 先 `REMOVE_PLAYER(self uuid)`；
2. 再 `ADD_PLAYER(self uuid, new GameProfile[name + textures])`；
3. 客户端中的 `playerInfoMap[self uuid]` 变成一份新的 `PlayerInfo`。

这一步解决的是：

> “同一个 UUID 现在对应哪份 `textures`？”

但它还没有解决：

> “当前已经活着的那个本地玩家对象，会不会继续抓着旧缓存不放？”

---

### 3. 为什么要再发 `respawn`

文件：`ref/mc/net/minecraft/client/multiplayer/ClientPacketListener.java`

```text
public void handleRespawn(final ClientboundRespawnPacket packet) {
   ...
   LocalPlayer oldPlayer = this.minecraft.player;
   ...
   LocalPlayer newPlayer;
   ...
   newPlayer = this.minecraft.gameMode.createPlayer(...);
   ...
   this.minecraft.player = newPlayer;
   ...
   this.level.addEntity(newPlayer);
}
```

而 `createPlayer(...)`：

文件：`ref/mc/net/minecraft/client/multiplayer/MultiPlayerGameMode.java`

```text
public LocalPlayer createPlayer(...) {
   return new LocalPlayer(this.minecraft, level, this.connection, ...);
}
```

最终 `LocalPlayer` 构造时：

文件：`ref/mc/net/minecraft/client/player/LocalPlayer.java`

```text
super(level, connection.getLocalGameProfile());
```

这里能得到两个非常重要的结论：

#### 结论 A：`respawn` 会重建一个新的 `LocalPlayer`

这意味着旧对象上的缓存字段，包括：

- `AbstractClientPlayer.playerInfo`

都会跟着旧实例一起丢掉。

#### 结论 B：`respawn` 仍然用的是旧 `localGameProfile`

所以它不会让客户端重新认知“我换了 UUID / 名字”。

它做的只是：

- 丢弃旧的本地玩家实例；
- 建立一个新的本地玩家实例；
- 新实例之后第一次渲染皮肤时，会重新走 `getPlayerInfo()`。

这正是 SkinRestorer 需要的效果。

---

### 4. 新的 `LocalPlayer` 会怎样拿到新皮肤

新的 `LocalPlayer` 刚被建出来时，`AbstractClientPlayer.playerInfo` 还是 `null`：

```text
private @Nullable PlayerInfo playerInfo;
```

当之后渲染自己皮肤时，会执行：

```text
this.playerInfo = Minecraft.getInstance().getConnection().getPlayerInfo(this.getUUID());
```

而 `ClientPacketListener.getPlayerInfo(UUID)` 只是简单取 map：

文件：`ref/mc/net/minecraft/client/multiplayer/ClientPacketListener.java`

```text
public @Nullable PlayerInfo getPlayerInfo(final UUID player) {
   return this.playerInfoMap.get(player);
}
```

由于前面 `REMOVE + ADD_PLAYER` 已经把 self UUID 对应条目替换成了新 `PlayerInfo`，于是新建出来的 `LocalPlayer` 第一次取皮肤时命中的就是新资料。

随后：

文件：`ref/mc/net/minecraft/client/multiplayer/PlayerInfo.java`

```text
public PlayerSkin getSkin() {
   if (this.skinLookup == null) {
      this.skinLookup = createSkinLookup(this.profile);
   }
   return this.skinLookup.get();
}
```

于是完整 self 路径就是：

> `REMOVE_PLAYER(self)`
> → `ADD_PLAYER(self, new textures)`
> → `playerInfoMap[self] = 新 PlayerInfo`
> → `RESPAWN`
> → 客户端新建 `LocalPlayer`
> → 新 `LocalPlayer.playerInfo == null`
> → 第一次渲染时重新 `getPlayerInfo(self uuid)`
> → 命中新 `PlayerInfo`
> → `PlayerInfo.getSkin()` 用新 `profile.properties` 解析皮肤
> → 自己看到自己换皮

这就是“复活玩家 + 重发 Info”在 MC 里的核心路径。

---

## 三、为什么还要补位置、栏位、血量、背包这些包

`SpigotSkinRefresher.refresh(...)` 里，在 `respawn` 后又发了：

- `PacketPlayOutPosition`
- `PacketPlayOutHeldItemSlot`
- `updateAbilities`
- `updateScaledHealth`
- `updateInventory`
- `triggerHealthUpdate`
- `OPRefreshUtil.refreshOP(...)`

原因很简单：

> `respawn` 会让客户端把“本地玩家对象”重建一遍，但这个过程只解决“重建 self 实例”本身，不会自动把所有 UI / 状态 / 能力细节都补齐到插件期望的样子。

所以 SkinRestorer 需要再把这些内容补一轮，避免出现：

- 位置跳动；
- 手持槽显示不对；
- 血量 / 饥饿 / 经验显示不同步；
- 权限等级或 OP 状态表现异常；
- 背包界面短暂不同步。

因此从工程角度看，`respawn` 不是孤立的一包，而是一整套：

> `PlayerInfo` 重建 + 本地玩家重建 + 状态补同步

---

## 四、别人视角的路径和自己视角的路径有什么区别

### 1. 别人视角：重点是“重建你这个外部实体”

别人客户端看到你的皮肤，关键在于：

- `PlayerInfo` 里这名玩家的 `GameProfile.properties(textures)`；
- 世界里这名玩家实体何时重新生成 / 重新关联。

所以 SkinRestorer 对别人用的是：

- `REMOVE/ADD_PLAYER` 或 tab/info 重发；
- `hide/show` 或传送导致的实体重刷。

重点是：

> 让“别人客户端里的你”重新刷出来。

### 2. 自己视角：重点是“让当前本地玩家对象别再抓着旧缓存”

自己客户端的问题更特殊：

- 自己不是一个普通的远端玩家实体；
- 本地玩家对象已经存在；
- 它还会缓存 `playerInfo`；
- `PlayerInfo` 本身又缓存了 `skinLookup`。

所以 self 刷新比别人多一步：

- 还得让 `LocalPlayer` 重建一次。

这就是为什么 SkinRestorer 要额外发 `respawn`。

---

## 五、这是不是“真正复活玩家”

严格说，不是服务端逻辑意义上的“死亡后复活”，而是：

- 发送一个客户端会按“重新出生 / 重新进入世界状态”处理的包；
- 客户端因此重建本地玩家对象；
- 从而达到刷新自己皮肤缓存的目的。

所以更准确的叫法应当是：

- **伪重生**
- **客户端侧 respawn refresh**
- **利用 respawn 重建 LocalPlayer**

而不是传统意义上的 gameplay 复活。

---

## 六、为什么这条路能做到“不切换服务器换肤”

因为它没有改连接层，没有让玩家重新走登录流程，只是在**当前连接内**补发一组“足以让客户端重建展示状态”的游戏阶段包。

也就是说它依赖的是：

- 游戏阶段 `PlayerInfo` 可以更新 profile/textures；
- 游戏阶段 `respawn` 可以重建 `LocalPlayer`；
- 客户端渲染自己皮肤时依赖 `PlayerInfo`，而不是只看登录时那份 profile。

三者叠加以后，就能在**不重连**的前提下做到“看起来像热切换皮肤”。

---

## 七、对 HyperZoneLogin 的直接启示

结合当前项目已有分析与实现，可以把 SkinRestorer 方案拆成两层理解：

### 1. Bukkit/Spigot 服务端能做得更彻底

因为它能直接控制：

- 玩家真实实体；
- `respawn`；
- 位置、能力、背包等补同步；
- 其他玩家对该实体的 hide/show。

所以它能把“别人视角刷新”和“自己视角刷新”一起做全。

### 2. 代理侧更容易做到的是“补 self ADD_PLAYER”

你们当前 `profile-skin` 里已经有：

- 立即补发一次 self `ADD_PLAYER`；
- `PlayerFinishConfigurationEvent` 后再 replay 一次 self `ADD_PLAYER`。

文件：`profile-skin/src/main/kotlin/icu/h2l/login/profile/skin/service/ProfileSkinSelfReplayService.kt`

它更接近 SkinRestorer 方案里的前半段：

> **先让客户端收到一份新的 self `PlayerInfo(profile + textures)`**。

但如果没有 Bukkit 这类服务端上下文去补 `respawn + 状态重建`，那么它和 SkinRestorer 的完整度仍然不完全一样。

换句话说：

> SkinRestorer 的关键不只是“补一个 self `ADD_PLAYER`”，而是“补一个 self `ADD_PLAYER` 之后，再想办法让当前本地玩家实例重新吃到它”。

---

### 3. SkinRestorer 在 VC 侧实际上是怎样实现的

如果把 `ref/SkinsRestorer/velocity` 单独拿出来看，它的重点并不是“在代理侧复刻 Bukkit 的整套热刷新链”，而是两件事：

#### A. 登录阶段，把 `textures` 注入 `GameProfileRequestEvent`

文件：`ref/SkinsRestorer/velocity/src/main/java/net/skinsrestorer/velocity/listener/GameProfileRequest.java`

它在 `GameProfileRequestEvent` 上包装出 `SRLoginProfileEvent`，最终在：

```text
event.setGameProfile(skinApplier.updateProfileSkin(event.getGameProfile(), property));
```

这里的含义是：

- 以当前 `event.getGameProfile()` 为 base；
- 只替换 / 叠加 `textures`；
- 再把新的 profile 回写给 Velocity 的登录阶段。

而 `SkinApplierVelocity.updateProfileSkin(...)` 本身也只是：

```text
return new GameProfile(profile.getId(), profile.getName(), updatePropertiesSkin(profile.getProperties(), property));
```

也就是说，SkinRestorer 在 VC 侧最核心的落点，仍然是：

> **在 `GameProfileRequestEvent` 阶段，把皮肤放进最终会进入 `ConnectedPlayer.profile` 的那份真实 profile。**

这和前面 `doc/vc皮肤分析.md` 的结论是一致的。

#### B. 运行期 apply 时，只更新 proxy profile，并通知后端

文件：`ref/SkinsRestorer/velocity/src/main/java/net/skinsrestorer/velocity/SkinApplierVelocity.java`

关键逻辑是：

```text
player.setGameProfileProperties(updatePropertiesSkin(player.getGameProfileProperties(), appliedProperty));
...
srPlayer.sendToMessageChannel(new SRServerPluginMessage(new SRServerPluginMessage.SkinUpdateV3ChannelPayload(...)));
```

这说明它在运行期做的其实是：

1. 用 `player.setGameProfileProperties(...)` 改掉 **Velocity 里的玩家 profile properties**；
2. 再通过 plugin message 把新的皮肤资料告诉后端服。

但要特别注意：

> 这段代码里**没有** Bukkit 那种 `REMOVE_PLAYER -> ADD_PLAYER -> RESPAWN -> 位置/能力/血量补同步` 的客户端刷新闭环。

换句话说，VC 侧 SkinRestorer 的运行期 apply，更像：

- 更新代理内存中的 profile；
- 尝试让后端也知道这次换肤；
- 但它本身不负责强制当前客户端立即刷新 self 皮肤显示。

---

### 4. 为什么 SkinRestorer 在 VC 侧不能像 Bukkit 一样实时换肤

核心原因不是“Velocity 完全不能发包”，而是：

> **代理侧缺的不是某一个包，而是缺少 Bukkit/Spigot 那种“对本地玩家重建链拥有完整控制权”的上下文。**

可以拆成四点看。

#### 4.1 登录阶段之后，客户端 self 身份已经固定在 `ConnectedPlayer.profile -> ServerLoginSuccessPacket` 这条链上

从 `ref/Velocity` 可知：

- `AuthSessionHandler.activated()` 会把 `GameProfileRequestEvent` 的结果拿去创建 `ConnectedPlayer`；
- `ConnectedPlayer.profile` 决定 `getUsername()` / `getUniqueId()` / `getGameProfileProperties()`；
- `AuthSessionHandler.completeLoginProtocolPhaseAndInitialize()` 会把这份 profile 写进 `ServerLoginSuccessPacket` 发给客户端。

也就是说：

> 在 VC 侧，**最稳的皮肤注入点是登录阶段**；一旦这一步过去，代理再去改 `ConnectedPlayer.profile`，并不等于客户端 self 身份链就被重跑了一次。

#### 4.2 运行期就算能补 `ADD_PLAYER`，也仍然缺少 self `respawn` 重建链

前面已经分析过，self 热换肤真正关键的是：

1. 替换 self UUID 对应的 `PlayerInfo`；
2. 再让客户端重建 `LocalPlayer`，清掉旧 `playerInfo` / `skinLookup` 缓存。

而 VC 侧并没有像 Bukkit 插件那样：

- 直接持有服务端玩家实体；
- 能稳定伪造一套和当前世界状态一致的 `respawn` 后续同步；
- 能补位置、能力、血量、背包、权限等整套状态；
- 能确保后端世界状态与代理临时发出的这些包不冲突。

因此即使代理“理论上”能塞一点 `PlayerInfo`，也不等于它能稳定完成：

> `REMOVE/ADD_PLAYER` + `RESPAWN` + 世界状态补同步

这个真正让 self 立刻换肤的闭环。

#### 4.3 Velocity 的 tablist / player info 机制，本身也不适合拿来做稳定 self 热刷新

`VelocityTabList.addEntry()` 与 `UpsertPlayerInfoPacket` 的语义决定了：

- 只有 `ADD_PLAYER` 会携带完整 `profile + properties`；
- 对已存在 entry，通常只会发 `UPDATE_*`；
- `UPDATE_*` 不会重新发送 `textures`；
- 即使 remove/add 一个 entry，也仍然无法替代 self `LocalPlayer` 的重建。

所以在 VC 侧：

> tab / player info 能做“补资料”，但不能等价替代 Bukkit 那种“让客户端把自己重新出生一遍”。

#### 4.4 代理还要面对“后端才是真正 gameplay authority”的冲突

这是 VC 与 Bukkit 最大的结构差别。

在 Bukkit 里，插件就在真正的游戏服上，发 `respawn`、位置、血量、背包等同步，都是直接代表当前世界 authoritative state。

但在 Velocity 里：

- 世界和玩家实体属于后端服；
- 代理若强插一套 self 重建包，必须与后端马上要发来的真实状态保持完全一致；
- 否则就容易出现回滚、覆盖、不同步、甚至协议时序问题。

所以更准确的说法是：

> SkinRestorer 在 VC 侧不是“完全不能动运行期皮肤”，而是**没有 Bukkit 那样足够完整、足够稳定的 self 热刷新控制面**，因此不能把“运行期实时换肤”作为可靠主能力。

---

### 5. 为什么当前 HZL 在 VC 侧也暂时做不到

当前 HZL 和 VC 侧 SkinRestorer 一样，都处在“代理能碰到一部分 profile / player info，但碰不到完整 self 重建链”的位置；而且 HZL 还有自己的额外约束。

#### 5.1 HZL 当前更偏向“登录身份重写 + 后端转发修复”，不是“客户端侧完整重建”

从项目内实现看：

- `velocity/src/main/kotlin/icu/h2l/login/listener/EventListener.kt`
  - 登录早期会生成随机 `GameProfile`；
  - `GameProfileRequestEvent` 当前主要是在做 name/UUID 一致性校验；
- `velocity/src/main/kotlin/icu/h2l/login/inject/network/netty/ServerLoginSuccessPacketReplacer.kt`
  - 会在发往客户端的 `ServerLoginSuccessPacket` 上改 UUID；
- `velocity/src/main/kotlin/icu/h2l/login/inject/network/netty/ToBackendPacketReplacer.kt`
  - 会在转发给后端时，根据场景选 temporary / attached profile，并在正式服方向使用 `ProfileSkinApplySupport.apply(...)` 补 `textures`；
- `profile-skin/src/main/kotlin/icu/h2l/login/profile/skin/service/ProfileSkinSelfReplayService.kt`
  - 目前能做的是补发 self `ADD_PLAYER`，以及 configuration 后 replay 一次 self `ADD_PLAYER`。

这说明 HZL 现在更像是在做：

1. 登录链 / 转发链上的 profile 控制；
2. 运行期补 self `ADD_PLAYER`；

而不是在做：

3. self `REMOVE_PLAYER`；
4. self `RESPAWN`；
5. 位置 / 能力 / 血量 / 背包 / 权限等重同步。

所以它离 SkinRestorer-Bukkit 那条真正能稳定热刷的链，还差后半截。

#### 5.2 HZL 当前没有实现 `REMOVE + ADD + respawn + 状态补同步` 闭环

从当前代码可以直接看到：

- `profile-skin` 里存在 `sendAddPlayer(...)`；
- 但项目内没有看到 `RemovePlayerInfoPacket` 的补发实现；
- 也没有看到代理侧 `respawn` 注包实现；
- 更没有看到围绕 respawn 的位置、能力、血量、背包等补同步链。

因此当前 HZL 在 VC 侧最多只能说：

> **尝试把新的 self `PlayerInfo` 送到客户端面前**。

但它还做不到：

> **强制客户端把当前本地玩家实例重新吃一遍这份新资料。**

这正是“能补 self `ADD_PLAYER`，但还不能稳定实时换肤”的本质原因。

#### 5.3 HZL 还额外受“随机 remap 身份”约束

这一点和通用的 Velocity 皮肤插件不同，是 HZL 自己的业务边界。

当前 `EventListener.kt` 明确要求：

- 登录使用特定前缀的随机名字；
- UUID 必须与该随机名字按既定规则对应。

这意味着 HZL 不能简单照搬“把真实 Mojang profile 原样塞回登录链”的思路，而是必须同时满足：

1. 项目自己的 remap/profile 校验；
2. 客户端 self 身份链的一致性；
3. 皮肤 `textures` 的可见性；
4. 运行期 self 刷新时序。

这比普通“代理侧换肤”又多了一层工程难度。

#### 5.4 即便 HZL 现在已经有 self replay，它也更接近“补救”，不是“完整 authority”

`ProfileSkinSelfReplayService.kt` 当前做的是：

- 在可用时立即补一次 self `ADD_PLAYER`；
- `PlayerFinishConfigurationEvent` 后再 replay 一次。

这已经是当前 proxy 能做的一个积极尝试，但它本质上仍是：

- 在 `PlayerInfo` 层补资料；
- 试图绕开一部分 configuration 生命周期带来的丢失；
- 而不是掌控一个真正的 self `respawn` 重建链。

所以更准确的项目结论应该写成：

> 当前 HZL 在 VC 侧**不是完全不能让皮肤显示变好**，而是**暂时做不到像 Bukkit SkinRestorer 那样，稳定地、运行期无感地、立即强制刷新自己的皮肤**。

---

## 八、把整条链压缩成一张时序图

### 服务端侧

1. 修改玩家服务端档案中的 `textures`
2. 对其他玩家：
   - `REMOVE/ADD_PLAYER` 或等效 info 重发
   - `hide/show` 或传送重刷实体
3. 对自己：
   - `REMOVE_PLAYER(self)`
   - `ADD_PLAYER(self, new textures)`
   - `RESPAWN`
   - 位置 / 手持 / 能力 / 血量 / 背包等补同步

### 客户端侧（自己）

1. `REMOVE_PLAYER(self)`：旧 self `PlayerInfo` 从 `playerInfoMap` 被删掉
2. `ADD_PLAYER(self)`：新 self `PlayerInfo` 写回 `playerInfoMap`
3. `RESPAWN`：客户端丢弃旧 `LocalPlayer`，创建新 `LocalPlayer`
4. 新 `LocalPlayer` 首次渲染自己时：
   - `getPlayerInfo(self uuid)`
   - 读到新的 `PlayerInfo`
   - `PlayerInfo.getSkin()` 按新 `textures` 建立 `skinLookup`
5. 皮肤刷新完成

---

## 九、最终结论

如果把 SkinRestorer 这个思路用一句话概括，就是：

> **先把 self UUID 对应的 `PlayerInfo` 换成带新 `textures` 的版本，再利用 `respawn` 重建本地玩家对象，让客户端重新按这份新 `PlayerInfo` 解析自己的皮肤。**

所以你最开始说的“通过复活玩家 + 重新发送 Info 来让玩家在不切换服务器的情况下更换皮肤”，可以改写成更精确的版本：

> SkinRestorer 不是在运行中重新定义客户端的 self 身份，而是在游戏阶段通过 `PlayerInfo REMOVE/ADD` 重建皮肤来源，再用 `respawn` 让客户端重建 `LocalPlayer`，从而绕过旧缓存并完成 self 皮肤热刷新。

---

## 参考源码索引

### SkinRestorer

- `ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/SkinApplierBukkit.java`
- `ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/refresher/SkinRefresher.java`
- `ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/refresher/SpigotSkinRefresher.java`
- `ref/SkinsRestorer/bukkit/src/main/java/net/skinsrestorer/bukkit/refresher/PaperSkinRefresher.java`
- `ref/SkinsRestorer/velocity/src/main/java/net/skinsrestorer/velocity/SkinApplierVelocity.java`
- `ref/SkinsRestorer/velocity/src/main/java/net/skinsrestorer/velocity/listener/GameProfileRequest.java`

### Minecraft 客户端

- `ref/mc/net/minecraft/network/protocol/login/ClientboundLoginFinishedPacket.java`
- `ref/mc/net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl.java`
- `ref/mc/net/minecraft/client/multiplayer/ClientPacketListener.java`
- `ref/mc/net/minecraft/client/multiplayer/MultiPlayerGameMode.java`
- `ref/mc/net/minecraft/client/player/LocalPlayer.java`
- `ref/mc/net/minecraft/client/player/AbstractClientPlayer.java`
- `ref/mc/net/minecraft/client/multiplayer/PlayerInfo.java`
- `ref/mc/net/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket.java`
- `ref/mc/net/minecraft/network/protocol/game/ClientboundPlayerInfoRemovePacket.java`

### 项目内相关实现

- `profile-skin/src/main/kotlin/icu/h2l/login/profile/skin/service/ProfileSkinSelfReplayService.kt`
- `doc/mc皮肤分析.md`
- `doc/vc皮肤分析.md`
- `velocity/src/main/kotlin/icu/h2l/login/listener/EventListener.kt`
- `velocity/src/main/kotlin/icu/h2l/login/inject/network/netty/ServerLoginSuccessPacketReplacer.kt`
- `velocity/src/main/kotlin/icu/h2l/login/inject/network/netty/ToBackendPacketReplacer.kt`
- `velocity/src/main/kotlin/icu/h2l/login/player/ProfileSkinApplySupport.kt`

### Velocity 源码

- `ref/Velocity/api/src/main/java/com/velocitypowered/api/event/player/GameProfileRequestEvent.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/connection/client/AuthSessionHandler.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/connection/client/ConnectedPlayer.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/ServerLoginSuccessPacket.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/tablist/VelocityTabList.java`
- `ref/Velocity/proxy/src/main/java/com/velocitypowered/proxy/protocol/packet/UpsertPlayerInfoPacket.java`


