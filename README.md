<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h1 align="center"><b>Auxio · 中文修复版</b></h1>
<h4 align="center">A simple, rational music player for Android —— 顺手修好了中文标签乱码。</h4>
<p align="center">
    <a href="https://github.com/xinghee/Auxio/releases/latest">
        <img alt="Latest Version" src="https://img.shields.io/github/v/release/xinghee/Auxio?include_prereleases&style=flat&color=64B5F6">
    </a>
    <a href="https://github.com/xinghee/Auxio/releases">
        <img alt="Downloads" src="https://img.shields.io/github/downloads/xinghee/Auxio/total.svg?color=4B95DE&style=flat">
    </a>
    <a href="https://www.gnu.org/licenses/gpl-3.0">
        <img src="https://img.shields.io/badge/license-GPL%20v3-2B6DBE.svg?style=flat">
    </a>
    <img alt="Minimum SDK Version" src="https://img.shields.io/badge/API-24%2B-1450A8?style=flat">
</p>

## 这个仓库是什么

[Auxio](https://github.com/OxygenCobalt/Auxio) 的个人分支。**只做一件事：修正中文 ID3 标签乱码**，其余代码、界面、功能都跟上游保持一致。

如果你有一批老 MP3，歌曲名显示成 `ÊÅÈ¥ÈÕ×Ó`、`ÕâÊÇ¸èÇú` 这种鬼东西，这里就是解药。

## 乱码是怎么来的

Auxio 4.0 起改用自带 TagLib 解析标签，不再依赖系统的 MediaStore。而早期中文打标工具（千千静听、酷狗那一代）写 ID3v2 时，`encoding` 字节声明的是 `ISO-8859-1`，实际塞进去的却是 **GBK 字节**。

TagLib 严格照声明用 Latin-1 解码，于是「逝去日子」的 GBK 字节 `CA C5 C8 A5 C8 D5 D7 D3` 被一个字节一个字符地读成了 `ÊÅÈ¥ÈÕ×Ó`。MediaStore 时代有系统兜底，所以升级到 4.x 才暴露出来。

好在 Latin-1 是**单射**（0x00–0xFF 一一映射到 U+0000–U+00FF），原字节一个没丢，可以完整还原后重新解码。

## 修复内容

| 位置 | 改动 |
|---|---|
| `musikr/.../util/EncodingFix.kt` | 新增 `String.fixLegacyEncoding()`：还原原始字节，先试严格 UTF-8，再试 GB18030 |
| `musikr/.../metadata/NativeTagMap.kt` | 在 `addID` / `addCustom` / `addCombined` 三处调用（id3v2、xiph、mp4 三种标签的唯一汇聚点） |
| `.github/workflows/android.yml` | 修掉 `branches: []` 导致 CI 永不触发的问题，改为监听 `dev` 并支持手动触发 |
| `app/build.gradle` | 新增 `canary` 变体：走 release 的 R8 + 资源裁剪，保留 `.debug` 包名，仅 arm64 |

三道保护，避免误伤本来正常的文件：

1. 已经含 U+00FF 以上字符的直接放行（正确的中文、日文标签不受影响）
2. 高位字节必须构成**偶数长度的连续段** —— GBK 每个汉字正好 2 字节，`Müller`、`Björk` 这种孤立高位字节不会被硬凑成汉字
3. 解码结果必须含 CJK 汉字、且没有控制字符，解出乱符号就原样返回

第 2 条不能写成"高位字节占比过半"：`BEYOND - 25周年` 这种以 ASCII 为主的专辑名，高位字节只占 4/21，会被误判成西文漏掉。

## 下载

到 [Releases](https://github.com/xinghee/Auxio/releases) 取包，两个版本二选一：

| 文件 | 大小 | 说明 |
|---|---|---|
| `Auxio_v*-cjk*_arm64.apk` | **6.4 MB** | **推荐**。R8 裁剪 + 仅 arm64-v8a |
| `Auxio_v*-cjk*_debug.apk` | 23.5 MB | 未裁剪的 debug 包，全 ABI，留着给 x86 模拟器 |

- 包名是 `org.oxycblt.auxio.debug`，**可以和官方正式版并存**，不会覆盖，也不共用媒体库缓存
- 签名是 CI 每次现生成的 debug keystore：两个包之间、不同次构建之间签名都不同，**换包前先卸载**
- 首次打开会重新扫描媒体库，中文歌名即刻生效

**如果个别歌曲还是乱码**：设置 → 清除该应用数据 → 重新扫描。缓存以 `uri + 文件修改时间` 为键，文件没变就会命中旧缓存。

## 与上游同步

```bash
git remote add upstream https://github.com/OxygenCobalt/Auxio.git
git fetch upstream
git rebase upstream/dev
```

改动只集中在 `NativeTagMap.kt`（3 行）和一个新增文件，冲突面很小。

## 自己编译

上游要求 `cmake` + `ninja-build` + NDK `28.2.13676358` + JDK 21，并且**不能在 Windows 上构建**（Media3 的自定义构建跑的是 Unix shell 脚本）。所以这个分支走 GitHub Actions，产出 `canary` 变体（release 的 R8 配置 + `.debug` 包名 + 仅 arm64）：

- 到 Actions 页面点 **Run workflow**（fork 仓库的 workflow 不会随 push 自动跑，实测 push 无反应）
- 产物在对应 run 的 **Artifacts** 里：`Auxio_Canary_arm64`

想本地跑单测（不产出 APK）：

```bash
./gradlew :musikr:testDebugUnitTest
```

体积参考：debug 变体全 ABI 是 23.5 MB（dex 未裁剪 12 MB + 4 个 ABI 的 .so 7.8 MB），canary 只留 arm64 并走 R8 后是 6.4 MB。

## 屏幕截图

<p align="center">
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot0.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot1.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot2.png" width=200>
    <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/shot3.png" width=200>
</p>

## 权限

- 存储（`READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE`）：读取并播放你的音乐文件
- 服务（`FOREGROUND_SERVICE` / `WAKE_LOCK`）：后台持续播放
- 通知（`POST_NOTIFICATIONS`）：显示播放状态与媒体库扫描进度

Auxio **没有网络权限**，完全离线、无广告、无账号。

## 致谢与上游

全部功劳归 [OxygenCobalt](https://github.com/OxygenCobalt) 与 Auxio 贡献者。这个分支只加了几行编码修复，没有上游就没有它。

- 上游仓库：https://github.com/OxygenCobalt/Auxio
- 上游 Wiki：https://github.com/OxygenCobalt/Auxio/wiki
- 英文原版说明：[README.upstream.md](README.upstream.md)

## License

[![GNU GPLv3](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)

Auxio is Free Software: you can use, study, share and improve it at your will. Specifically you can redistribute and/or modify it under the terms of the [GNU General Public License](https://www.gnu.org/licenses/gpl.html) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

本分支同样以 GPL-3.0 发布，源码改动公开可见。
