# 番茄混淆

离线安卓图片混淆工具，Kotlin + Jetpack Compose，Android 10 及以上。

## 功能

- 系统选图器导入 JPEG / PNG，不申请整个相册访问权限。
- Gilbert 曲线像素混淆、解混淆、多次处理、重置到导入状态。
- 当前图片预览、双指放大与拖动、处理进度及取消。
- 默认 PNG 保存，可选 JPEG（质量 80～100），设置会保留。
- 保存到 `Pictures/TomatoShuffle`，系统分享，通过 FileProvider 授予临时读取权限。
- 无网络权限、无广告和统计 SDK、不覆盖原图。

内测包：`dist/TomatoShuffle-0.1.0-debug.apk`。界面预览和验证结论见 `docs/VERIFICATION.md`，试用素材见 `samples/README.md`。

## 构建

需要 JDK 21、Android SDK Platform 35、Build Tools 35.0.0。Gradle 8.11.1 已通过 Wrapper 固定，AGP 8.9.2、Kotlin 2.1.20。版本用于本次内测，不代表当前商店上架要求。

在 Android Studio 中打开本目录，配置 SDK 并同步即可。也可以在 PowerShell 执行：

```powershell
.\scripts\build.ps1 -JavaPath '你的 JDK 21 目录' -SdkPath '你的 Android SDK 目录'
```

本机 `.tools` 中已有独立构建环境，脚本可以发现该 SDK 和本机已有的 IDEA JDK，不修改系统级环境变量。首次 Gradle Wrapper 运行需要下载发行包和依赖。

Windows 上本项目中文路径会导致 Gradle 测试进程读取 UTF-8 参数文件时找不到测试类。构建脚本自动建立同级英文目录联接 `tomato-shuffle-build` 并从该入口构建；联接指向同一份文件，不复制源码。本机亦可通过 `D:\workspace\tomato-shuffle-build` 在 IDE 中打开工程。

单独运行：

```powershell
.\gradlew.bat :core:test
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug :app:assembleDebug
```

内测包输出为 `app/build/outputs/apk/debug/app-debug.apk`，构建脚本另复制到 `dist/TomatoShuffle-0.1.0-debug.apk`。这是调试签名内测包，不是正式商店发行包。正式发布前固定签名密钥和商店资料；不要提交任何签名密钥。

## 算法互通的范围

以 2026-09-12 获取的参考网站规则为基准：沿 Gilbert 曲线，将像素循环移动 `round(((sqrt(5)-1)/2) × 像素数)` 个位置；解混淆执行反向映射。曲线中负数除法必须向下取整。

网站曲线函数产生的测试向量保存在 `core/src/test/resources/website-vectors.tsv`，来源摘要在 `docs/reference-capture.json`。网站源代码快照仅放在本地忽略目录 `.research`，不打包进应用；应用曲线实现改编自 BSD-2-Clause 的 Gilbert 项目，保留许可。

像素置换兼容不代表 JPEG 编码逐字节一致。网站每次操作都会重新压缩 JPEG；本 App 处理期间保留像素，导出时才编码。详细验证范围参见 `docs/VERIFICATION.md`。

## 当前限制

- 单张静态 JPEG / PNG；导入上限为 800 万像素、单边 4000，低内存设备会进一步降低像素上限。超限拒绝导入，不静默缩放待解混淆图片。
- 图片文件的编码体积上限为 32 MiB，低内存设备会降低为最大堆内存的 1/16。通过有上限的流读取适配文件和云端媒体提供方。
- 导入后转为不透明 sRGB，透明通道合成黑底。PNG 无损指保存当前规范化像素，不承诺与原文件、原始透明通道或色彩配置相同。
- 自动应用图片方向标签。外部图片若在处理后又被旋转、裁剪或缩放，不能保证解开。
- 屏幕旋转时 ViewModel 保留会话；进程被系统杀死或退出后不恢复未保存图片。导出的文件会保留。
- 不识别外部图片是否已混淆、算法种类或历史次数；不自动判断“恢复成功”。
- 分享缓存超过 24 小时后会在下一次分享时清理；系统也可清理缓存。无账号或云同步。
- 尚需在真实手机验证系统选图、分享目标、厂商相册与低内存行为。

## 目录

`app` 为安卓 UI 和文件处理，`core` 为可独立测试的像素算法，`scripts` 为构建和对照向量脚本，`docs` 为验证记录，`PLAN.md` 为原开发计划。
