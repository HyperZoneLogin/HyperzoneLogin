# HyperZoneLogin

**HyperZoneLogin** 是一个面向 Minecraft Velocity 代理网络的认证框架，
用于把多种登录方式统一到同一套 Profile / 档案体系中。

**语言 / Languages：** [English](README.md) | [简体中文](README.zh-CN.md)

[![GitHub Release](https://img.shields.io/github/v/release/HyperZoneLogin/HyperzoneLogin?label=Release)](https://github.com/HyperZoneLogin/HyperzoneLogin/releases)
[![License](https://img.shields.io/github/license/HyperZoneLogin/HyperzoneLogin?label=License)](./LICENSE)
[![Discord](https://img.shields.io/discord/1492467475810484244.svg?logo=discord&label=Discord)](https://discord.gg/dCAeNyR9TA)
[![QQ Group](https://img.shields.io/badge/QQ%20Group-832210691-12B7F5?logo=tencentqq&logoColor=white)](https://qm.qq.com/q/GZWVfEyokS)
[![Proxy Stats](https://img.shields.io/bstats/servers/30691?logo=minecraft&label=Servers)](https://bstats.org/plugin/velocity/HyperZoneLogin/30691)
[![Proxy Stats](https://img.shields.io/bstats/players/30691?logo=minecraft&label=Players)](https://bstats.org/plugin/velocity/HyperZoneLogin/30691)

## 项目简介

HyperZoneLogin 适合需要统一认证入口的代理服网络，例如：

- 同时支持多种登录方式
- 需要统一档案创建、绑定与解析流程
- 需要在认证完成前将玩家留在等待区 / 认证流程中
- 希望通过独立模块扩展安全、防护、迁移、皮肤处理等能力

## 支持的登录方式

目前项目内主要包含以下认证模块：

| 登录方式 | 模块 | 简介 |
|---|---|---|
| 本地 / 离线账号 | `auth-offline` | 支持注册、登录、改密、注销、邮箱找回、短期 session 自动登录，以及可选 TOTP 二步验证 |
| Yggdrasil 在线认证 | `auth-yggd` | 支持 Mojang 风格或兼容 Yggdrasil 的认证服务，并可配置多个 Entry |
| Floodgate / Bedrock 认证 | `auth-floodgate` | 识别通过 Floodgate 接入的 Bedrock 玩家，并作为可信认证渠道接入统一流程 |

## 其它可选模块

| 模块 | 作用 |
|---|---|
| `safe` | 连接限流、异常用户名检查、短时冷却等入口层安全防护 |
| `data-merge` | 从 MinecraftLink / 旧 ML、AuthMe 等旧系统迁移数据 |
| `profile-skin` | 处理皮肤提取、修复、缓存与最终档案皮肤应用 |

## 文档入口

如果你是第一次接触本项目，建议优先阅读：

- **用户文档站：** <https://docs.h2l.icu>
- **英文主 README：** [`README.md`](README.md)

如果你想查看子模块说明，也可以直接阅读：

- [`velocity/README.md`](./velocity/README.md)
- [`auth-offline/README.md`](./subplugin/auth-offline/README.md)
- [`auth-yggd/README.md`](./subplugin/auth-yggd/README.md)
- [`auth-floodgate/README.md`](./subplugin/auth-floodgate/README.md)
- [`safe/README.md`](./subplugin/safe/README.md)
- [`data-merge/README.md`](./subplugin/data-merge/README.md)
- [`profile-skin/README.md`](./subplugin/profile-skin/README.md)

## 社区

- Issue：<https://github.com/HyperZoneLogin/HyperzoneLogin/issues>
- Discord：<https://discord.gg/dCAeNyR9TA>
- QQ 群：<https://qm.qq.com/q/GZWVfEyokS>

## 赞助

如果这个项目对你有帮助，欢迎支持开发与维护。

[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20Project-FF5E5B?logo=kofi&logoColor=white)](https://ko-fi.com/ksqeib)
[![爱发电](https://img.shields.io/badge/%E7%88%B1%E5%8F%91%E7%94%B5-%E6%94%AF%E6%8C%81%E9%A1%B9%E7%9B%AE-946CE6)](https://afdian.com/a/ksqeib445)

如果你暂时不方便赞助，也欢迎通过以下方式支持项目：

- 提交 Issue 反馈问题
- 完善文档
- 提交高质量 PR
- 将项目推荐给有需要的人


