vc-runtest
==========

本模块用于本地启动 Velocity 代理做运行时测试。

## 运行方式

```powershell
.\gradlew.bat :vc-runtest:runVelocity
```

### 行为

- 启动前会生成/补齐 `vc-runtest/run/` 下的基础配置文件；
- 会依赖根项目的 `collectSplitPluginJars`，使用 split 版插件包，而不是 monolith `all` 包；
- 启动入口是 `icu.h2l.login.vcruntest.VcRuntestBootstrap`，它会把 split bundle 里的插件 jar 连接/复制到 `run/plugins/`，然后直接反射启动 Velocity。

如需指定外部 bundle 目录，可以传：

```powershell
.\gradlew.bat :vc-runtest:runVelocity -Dh2l.vcRuntest.bundleDir=D:\path\to\HZL-split
```

