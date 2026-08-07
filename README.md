# SolCesto Android Port

将 Epic Games 免费游戏 **Sol Cesto**（Construct 3 引擎，Windows WebView2 版）移植为 Android APK 的完整工程。

> ⚠️ **版权声明**：Sol Cesto 及其全部游戏素材（美术、音频、代码）版权归原开发者所有。本仓库仅包含**移植工程、打包脚本与文档**，不包含解包的游戏资源。APK 仅供个人学习与游玩，请勿商用或二次分发。

## 📦 下载 APK

所有 APK 版本均通过 **GitHub Releases** 发布：

👉 **[Releases 页面](https://github.com/zhenliuMr/solcesto-android/releases)**

在 Releases 页面可以看到**所有历史版本**，每个版本均可下载对应的 `SolCesto-Mobile-vX.Y.Z.apk`。

当前版本：**v1.01.3**

| 版本 | 说明 |
|---|---|
| v1.01.3 | 当前稳定版：长按 250ms 显示 tooltip 并拦截误触点击；存档/设置/语言持久保存；退出游戏正常关闭；保存并退出返回标题页 |

## ✨ 移植内容

- **Android WebView 封装**：`MainActivity` + 本地 HTTP 服务器（`LocalHttpServer`）加载游戏
- **触摸适配**：修复 Android WebView 触摸合成 mouse 事件导致的长按误触点击
  - `Touch` 插件：hold 检测（250ms）设置全局标志
  - `Mouse` 插件：`OnClick` / `OnObjectClicked` / `OnRelease` 延迟到 mouseup 触发并检查 hold 状态
  - 长按 → 只显示 tooltip，不触发点击；短按 → 正常点击
- **存档持久化**：本地 HTTP 服务器固定端口（18929），保证 IndexedDB 存档按稳定 origin 持久保存，游戏进度/设置/语言重启不丢失
- **退出与返回**：标题页"退出游戏"直接关闭进程（AndroidBridge）；游戏内"保存并退出"返回标题页（重置 RetourTitreDepuisRun 标志，避免停在下屏 META 界面）
- **横屏锁定、全屏沉浸**等移动端适配

## 📄 许可证

移植脚本与工程代码：见 [LICENSE](LICENSE)。
游戏本体素材版权归原作者所有，不在本仓库内。
