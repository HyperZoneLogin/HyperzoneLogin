# HyperZoneLogin

[![GitHub Release](https://img.shields.io/github/v/release/HyperZoneLogin/HyperzoneLogin?label=Release)](https://github.com/HyperZoneLogin/HyperzoneLogin/releases)
[![License](https://img.shields.io/github/license/HyperZoneLogin/HyperzoneLogin?label=License)](./LICENSE)
[![Discord](https://img.shields.io/discord/1492467475810484244.svg?logo=discord&label=Discord)](https://discord.gg/dCAeNyR9TA)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-832210691-12B7F5?logo=tencentqq&logoColor=white)](https://qm.qq.com/q/GZWVfEyokS)
[![Proxy Stats](https://img.shields.io/bstats/servers/30691?logo=minecraft&label=Servers)](https://bstats.org/plugin/velocity/HyperZoneLogin/30691)
[![Proxy Stats](https://img.shields.io/bstats/players/30691?logo=minecraft&label=Players)](https://bstats.org/plugin/velocity/HyperZoneLogin/30691)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20Project-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/ksqeib)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E6%94%AF%E6%8C%81%E9%A1%B9%E7%9B%AE-946CE6)](https://afdian.com/a/ksqeib445)

实现多种登入方式的框架

## 先看用户文档

如果你是第一次接触本项目，请优先阅读文档站：

- **用户文档入口：<https://docs.h2l.icu>**
- 当前公开的安装、配置、模块说明、问题反馈与常见问题均已迁移到该站点维护。
- 与实现原理、内部设计相关的文档目前仅在组织内私有维护，不在本仓库公开。

对大多数服主而言，推荐阅读顺序是：

1. 先看文档站中的“服务器基础配置”，确认 Velocity 与后端服环境正确；
2. 再看“模块介绍”，决定你需要安装哪些模块；
3. 然后阅读“基础安装”，完成首次部署；
4. 最后参考“配置摘要”调整配置。

如果你正在从旧方案迁移，请直接查看文档站中的迁移与运维说明；涉及实现原理、内部设计的补充材料目前仅在组织内私有维护。

## 模块概览

当前仓库主要由以下模块组成：

- `velocity`：主插件 / 核心模块，负责 Profile、数据库、命令与模块注册；
- `auth-yggd`：Yggdrasil 在线验证模块，适用于 Mojang 与第三方皮肤站；
- `auth-offline`：离线 / 本地账号模块，适用于混合登录场景；
- `data-merge`：数据迁移模块，用于从 AuthMe、MultiLogin 导入历史数据；
- `profile-skin`：皮肤修复模块，用于补全与缓存皮肤属性；
- `api`：提供给开发者和扩展模块使用的 API。

## 运行时动态依赖加载

从当前版本开始，`velocity` 主插件与部分子模块会在首次启动时动态下载所需运行库，并缓存到 `plugins/hyperzonelogin/libs/`（子模块会复用该缓存目录）。这样可以显著减小发布包体积。

- 首次启动需要能够访问 Maven 仓库镜像；
- 下载后的 jar 会进行 SHA-256 校验；
- 后续启动会优先复用本地缓存；
- 主插件与子模块均不再要求额外安装 `MCKotlin-Velocity`；
- 相关实现参考并改编自 LuckPerms，详见 [`THIRD_PARTY_NOTICES.md`](./THIRD_PARTY_NOTICES.md)。

## 当前已确认范围

### 可导入数据的插件

- [x] AuthME
- [x] MultiLogin

### 兼容性
登入方式：支持主流登入方式  
MC版本：1.19.1+暂无问题 低于1.19.1则只能使用域名作为识别方式  
启动器：支持市面常见  

## 开发计划

排名有先后顺序，我会先实现在前面的内容  

- [x] 基础原理实现
- [x] 添加基础toml配置
- [x] 取代AuthMe实现离线登入
- [x] 取代MultiLogin实现多Yggd档案管理
- [x] 取代MultiLogin实现皮肤修复
- [x] 提供一定的防御功能
- [x] 实现后端真实服务器作为vServer
- [x] 开发账户绑定功能
- [x] 美化离线交互逻辑
- [ ] 支持floodgate
- [ ] 添加地图二维码功能
- [ ] 实现自己的vServer/参与limbo维护
- [ ] 为档案管理添加一套可用的Web页面

## 开发时间

作者很忙，年更插件，有需要的话，你可以提交PR，但本仓库不接受低质量的PR，有问题就发issue

## 赞助

如果这个项目对你有帮助，欢迎支持开发与维护。  
你的支持会直接用于作者的日常生活与治疗支出，也能帮助项目继续更新。  

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20Project-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/ksqeib)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E6%94%AF%E6%8C%81%E9%A1%B9%E7%9B%AE-946CE6)](https://afdian.com/a/ksqeib445)

当前已在仓库内可确认的公开支持方式：

- Ko-fi：<https://ko-fi.com/ksqeib>
- 爱发电：<https://afdian.com/a/ksqeib445>

如果你暂时不方便赞助，也欢迎通过以下方式支持项目：

- 提交 [Issue](https://github.com/HyperZoneLogin/HyperzoneLogin/issues) 反馈问题；
- 完善文档或提交高质量 PR；
- 将项目推荐给有需要的人。

如果你需要先沟通再决定是否支持，可以先查看文档站中的问题反馈 / 联系方式部分：<https://docs.h2l.icu>。目前仓库内可确认的公开交流渠道包括 GitHub、Discord 与 QQ 群（`832210691`，<https://qm.qq.com/q/GZWVfEyokS>）。
