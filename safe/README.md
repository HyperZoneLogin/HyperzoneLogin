Safe 模块 (hzl-safe)
====================

目的
----
本模块为 HyperZoneLogin 提供入口层安全防护，优先处理“连接太快、同 IP 过多尝试、用户名明显异常”等在认证前即可拦截的问题。

首轮能力
--------
- 基于 `OpenPreLoginEvent` 的全局连接频率限制
- 基于 `OpenPreLoginEvent` 的同 IP 连接频率限制
- 用户名长度 / 字符集 / 去空白基础校验
- 通过 `safe.conf` 配置开关与阈值

注意事项
--------
- 本模块是独立 Velocity 子插件，需要与主插件 `hyperzonelogin` 一起放入 `plugins/`。
- 当前实现为单机内存限流，不跨代理同步。
- 若需更强的 anti-bot（如验证码、指纹、外部黑名单、Redis 共享封禁），建议在此模块继续扩展。

