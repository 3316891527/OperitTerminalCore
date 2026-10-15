# 宿主提供 Ubuntu 安装归档

个人发行分支不再随 Android 库打包 ubuntu-noble-aarch64-pd-v4.18.0.tar.xz。宿主需在创建 TerminalManager 单例之前注册归档准备接口：

```kotlin
TerminalManager.setUbuntuArchivePreparer { context, destination ->
    // 宿主先校验固定资源，再复制为终端安装流程使用的工作副本。
    OfflineResources.prepareUbuntuArchive(context, destination)
}
```

回调只在 Ubuntu 尚未安装时调用。已有 .operit_installed_ok 或 etc/issue.net 的环境沿用原来的识别规则，不需要归档或网络。小型 setup_fake_sysdata.sh 仍随库携带，每次初始化刷新。

回调应只准备已经下载或本地导入的资源，缺失时提示用户完成准备后重试，不应在现有会话 30 秒初始化等待期内下载大型文件。异常会使当前初始化失败，取消继续向上传递。

安装脚本会删除 destination 的归档，因此宿主需要单独保留可导出的原始资源缓存。本接口不改变已安装系统的用户文件、会话与命令完成事件。
