
Data Merge 模块 (hzl-data-merge)
================================

目的
----
本模块负责将外来数据（目前支持 MinecraftLink/旧 ML 数据与 AuthMe 数据）迁移到 HyperZoneLogin 的数据模型中，主要用于从其他登录/身份插件迁移账号、Entry 与离线认证数据。

运行时集成（*不包含构建步骤*）
--------------------------------
- 将 `hzl-data-merge` 放入 Velocity 的 `plugins/` 目录，并确保主插件 `hyperzonelogin` 在运行时可用。
- 模块在检测到主插件后会通过 `registerModule(MergeSubModule())` 完成注册，并注册命令 `/hzl-merge` 到代理命令管理器。

主要功能与技术细节
------------------
- 迁移器：
  - `MlDataMigrator` 处理 ML（MinecraftLink / 旧版）到目标模型的迁移；
  - `AmDataMigrator` 处理 AuthMe 数据到目标模型的迁移（包含对密码格式的兼容检测与转换）。
- 配置：
  - 模块在数据目录下创建 `merge/merge-ml.conf` 与 `merge/merge-am.conf`（HOCON），首次创建时会生成带注释的默认配置文件并提示修改；
  - 使用 Configurate HOCON loader，并在首次创建时保存默认文件。
- 命令：
  - 注册 `/hzl-merge` 管理命令，支持子命令 `ml` 和 `am`，执行相应的迁移并返回迁移摘要；
  - 迁移过程会输出日志（分别写入 `merge-ml.log` 或 `merge-am.log`，并在命令完成后提示）。

可用命令
--------
- /hzl-merge ml
  - 说明：运行 ML 数据迁移（会读取 `merge/merge-ml.conf`）。
- /hzl-merge am
  - 说明：运行 AuthMe 数据迁移（会读取 `merge/merge-am.conf`）。

权限（Permission）
-------------------
由于迁移是高权限操作，命令受限：

| 命令 | 说明 | 所需权限 |
|------|------|----------|
| /hzl-merge ml | 运行 ML 数据迁移 | hyperzonelogin.admin |
| /hzl-merge am | 运行 AuthMe 数据迁移 | hyperzonelogin.admin |

运行提示与注意事项
-------------------
- 首次运行时模块会创建 `merge/` 目录与配置文件，请在执行迁移前根据实际环境调整配置；首次生成会在日志中发出警告提示。
- 迁移是不可逆操作：建议在生产环境运行之前备份数据库，并在测试环境验证迁移结果。
- 迁移过程中可能会遇到密码格式不兼容的情况（AuthMe 特殊格式），模块会尝试识别并记录无效或需要人工干预的记录。

开发者提示
-----------
- `MlDataMigrator` 与 `AmDataMigrator` 为轻量实现，若需支持更多来源，可在 `merge` 模块下新增迁移器并扩展 `/hzl-merge` 子命令逻辑。

