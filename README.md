<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="150"></p>
<h1 align="center"><b>Auxio · 中文修复版</b></h1>
<h4 align="center">A simple, rational music player for Android —— 顺手修好了中文标签乱码，还加了歌词。</h4>
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

[Auxio](https://github.com/OxygenCobalt/Auxio) 的个人分支，在上游基础上只动了三处：

1. **修正中文 ID3 标签乱码** —— `ÊÅÈ¥ÈÕ×Ó` 显示成「逝去日子」，读的时候翻译，不改文件
2. **应用内修复标签编码** —— 把改错的编码直接写回文件，车机、foobar 也跟着正常
3. **歌词** —— 扫本地 `.lrc` 自动绑定，可选在线补全，播放时能看
4. **定时停止** —— 20 / 40 / 60 分钟三档，到点自动暂停

其余代码、界面、功能都跟上游保持一致。

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

## 修复文件本身（应用内）

上面那套只让 Auxio **显示**正确，文件里存的还是错的——拷到车机、Windows 资源管理器、foobar 里照样是 `ÊÅÈ¥ÈÕ×Ó`。所以这里另有一条写通道。

**入口**：设置 → **内容** → 「音乐」分组 → **修复标签编码**

设置首页第三项叫「内容」（图标是音符），这一页的标题也是「内容」，第一个分组是「音乐」，本项排在「隐藏合作者」之后，手机屏小的话要往下滑一点。

```
☑ 逝去日子.mp3            TIT2  ÊÅÈ¥ÈÕ×Ó → 逝去日子
☑ BEYOND - 25周年.mp3     TALB  BEYOND - 25ÖÜÄê → BEYOND - 25周年
[全选]                                    [修复 2 处]
```

进页面就自动扫描，不需要点任何东西；**但修复不会自动进行**——列出 `原值 → 修复后` 让你确认，勾选后点「修复 N 处」才写文件。

- 扫描范围是「音乐目录」里已添加的文件夹（`MusicSettings.safQuery.source`），不需要重新选目录，也不会和媒体库出现分歧
- 只读 ID3v2 头和帧，不读音频，几百首秒级
- 写完再解析一遍验证，确认没有残留才落盘
- **撤销**：写入前把原 ID3 区块存到 `filesDir/tag-backup/`，只存标签不存音频（几 KB），保留最近 3 批

| 位置 | 作用 |
|---|---|
| `musikr/.../tag/write/Id3v2Rewriter.kt` | 读帧 → 只重编码可疑文本帧 → 其余按字节搬运，ID3 版本不变 |
| `musikr/.../tag/write/Id3v2Rewriter.kt`（`TagEncoderRepair`） | 对外门面，`internal` 的帧表不外泄 |
| `app/.../repair/TagDirectoryScanner.kt` | 遍历已注册目录，读头部判定 |
| `app/.../repair/TagRepairManager.kt` | 读 → 内存重写 → 验证 → 备份 → 写；失败则回滚 |
| `app/.../repair/TagRepairFragment.kt` | 列表、勾选、进度、撤销 |

改完标签后文件修改时间会变，Auxio 的媒体库会自动重新解析，不用手动「重新扫描音乐库」。

**限制**

- 只处理 **MP3 的 ID3v2**。FLAC/Ogg 的 Vorbis comment、MP4 的 `©nam` 这一版没有覆盖
- 如果页面提示「还没有添加音乐目录」，说明媒体库用的是「系统媒体库」模式而不是文件夹模式，这时没有可扫的目录

## 下载

到 [Releases](https://github.com/xinghee/Auxio/releases) 取包。两个包**代码完全一样**，都是 R8 裁剪版，含应用内标签修复，区别只在打包了哪些 ABI 的原生库。

| 文件 | 大小 | ABI | 什么时候用 |
|---|---|---|---|
| `Auxio_v*-cjk*_arm64.apk` | **6.4 MB** | arm64-v8a | **默认选这个**。2017 年之后的手机基本都是 arm64 |
| `Auxio_v*-cjk*_universal.apk` | **10.7 MB** | arm64-v8a / armeabi-v7a / x86 / x86_64 | 安卓模拟器、老 32 位设备、不确定机型时 |

差的 4.3 MB 全在 `lib/` 里：通用包多带 armeabi-v7a 和 x86/x86_64 两份 `libtagJNI.so`、`libffmpegJNI.so`。功能、界面、dex 完全一致。

- 两个包名都是 `org.oxycblt.auxio.debug`，**可以和官方正式版并存**，不覆盖、也不共用媒体库缓存
- **签名固定**：从 `cjk.5` 起所有版本用同一个证书，新版本可以直接覆盖安装，播放列表与设置都不会丢
- 只有从 `cjk.4` 及更早版本升级到 `cjk.5` 时需要卸载一次（旧包是每次构建现生成的随机 debug key，对不上）
- 装完在系统设置里看版本号后缀即可分辨：`4.1.5-arm64` / `4.1.5-universal`
- 首次打开会重新扫描媒体库，中文歌名即刻生效

签名指纹（校验用）：

```
SHA-256  A1:E8:90:EA:A4:3C:DA:17:AC:D7:39:1C:7C:5E:AE:1C
         54:4D:71:B5:12:7D:E4:F2:20:B9:0F:30:35:84:59:81
```

```bash
apksigner verify --print-certs Auxio_v*-cjk*_arm64.apk
```

**如果个别歌曲还是乱码**：先用应用内的「修复标签编码」把文件改对（见上一节），这样所有播放器都正常；只想让 Auxio 显示正常的话，也可以用 设置 → 清除该应用数据 → 重新扫描，缓存以 `uri + 文件修改时间` 为键，文件没变就会命中旧缓存。

## 与上游同步

```bash
git remote add upstream https://github.com/OxygenCobalt/Auxio.git
git fetch upstream
git rebase upstream/dev
```

改动只集中在 `NativeTagMap.kt`（3 行）和一个新增文件，冲突面很小。

## 自己编译

上游要求 `cmake` + `ninja-build` + NDK `28.2.13676358` + JDK 21，并且**不能在 Windows 上构建**（Media3 的自定义构建跑的是 Unix shell 脚本）。所以这个分支走 GitHub Actions，产出 `canary` 变体（release 的 R8 配置 + `.debug` 包名）：

- 到 Actions 页面点 **Run workflow**（fork 仓库的 workflow 不会随 push 自动跑，实测 push 无反应）
- 一次构建出两个包，都在 run 的 **Artifacts** 里：`Auxio_Canary` 这个 artifact 内含 `Auxio_arm64.apk` 和 `Auxio_universal.apk`

打包哪些 ABI 由 Gradle 属性控制：

```bash
./gradlew app:packageCanary                        # 默认只出 arm64-v8a
./gradlew app:packageCanary -PauxioAbi=all         # 全 ABI（与官方包一致）
./gradlew app:packageCanary -PauxioAbi=armeabi-v7a # 也可以指定单个
```

`versionNameSuffix` 会跟着变成 `-arm64` / `-universal`，装完能分辨。

想本地跑单测（不产出 APK）：

```bash
./gradlew :musikr:testDebugUnitTest
```

体积参考：debug 变体全 ABI 是 23.5 MB（dex 未裁剪 12 MB + 4 个 ABI 的 .so 7.8 MB）；canary 走 R8 后，只留 arm64 是 6.4 MB、全 ABI 是 10.7 MB。

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
- 网络（`INTERNET`）：**仅用于可选的在线歌词补全**，默认关闭

上游 Auxio 没有网络权限、完全离线。这个分支加了 `INTERNET`，因为在线歌词必须有它——但也只做这一件事：只有你在「歌词」页面手动打开开关并点「在线补全」时才会发请求，抓到的歌词会缓存到本地，之后离线可看。播放过程本身从不联网。

## 歌词

**绑定**：设置 → **内容** → 「音乐」分组 → **歌词**

扫描「音乐目录」下所有子目录里的 `.lrc`（包括 `Music/Musiclrc` 这种独立歌词文件夹），跟库里的歌逐首匹配，如实报告绑上了多少首。

| 层 | 依据 |
|---|---|
| 1 | `[ti:]` 归一化后完全相等 |
| 2 | 从标题提取 `《》` → `[]` → `【】` 的内容后相等 |
| 3 | 字符二元组（Dice 系数）相似度 ≥ 0.82 |

`《》` 优先级最高是因为中文歌名习惯加书名号；`[]` 次之，通常装的是曲名；`【】` 最后，经常装的是作品名（比如「灌篮高手」）。视频站常见的 `AI翻唱`、`中文填词`、`1080P` 这类尾巴会被剥掉。

**在线补全**：页面顶部开关，打开后对未绑定的歌逐首搜索，取回同步歌词并缓存到 `filesDir/lyrics/`。多结果时按文件时长挑版本（差 8 秒内优先）。

**播放时看**：播放面板歌名右侧有一个**歌词图标**（在 ⋯ 左边），点开全屏歌词。长按歌名是同样的快捷方式。

当前行跟随播放位置高亮并自动滚动，双语（`原文^翻译`）分两行显示，点任意一行跳到该时间点。歌词页是叠在界面最上层的独立页面，不依赖导航图，返回键退出。

新增文件：

| 位置 | 作用 |
|---|---|
| `musikr/.../lyrics/LrcParser.kt` | 解析 LRC：多时间戳、`[mm:ss:xx]`、全局 offset、`原文^翻译` |
| `musikr/.../lyrics/LyricsTitle.kt` | 标题归一化、候选提取、Dice 相似度 |
| `app/.../lyrics/LyricsScanner.kt` | 递归扫 `.lrc`，UTF-8 / GB18030 自动识别 |
| `app/.../lyrics/LyricsMatcher.kt` | 三层匹配 |
| `app/.../lyrics/LyricsStore.kt` | 绑定表持久化（`filesDir/lyrics-index.json`） |
| `app/.../lyrics/NeteaseLyrics.kt` | 在线搜索与歌词抓取 |
| `app/.../lyrics/LyricsFragment.kt` | 扫描/绑定报告页 |
| `app/.../lyrics/LyricsPanelFragment.kt` | 全屏歌词页 |

**限制**：只处理文本 `.lrc` 与在线源；FLAC/Ogg 内嵌歌词、`.hmrc` 这类加密格式不在范围内。

## 定时停止

播放面板顶部工具栏的**时钟图标**，三档：20 / 40 / 60 分钟。

（上游工具栏上的「均衡器」入口已移除：它要启动系统的音效面板，国内 ROM 基本没有，点了只会提示「未找到可以处理此任务的应用」。）

- 按**真实时间**倒计时，中途手动暂停也继续走
- 到点只**暂停**播放，不退出、不清通知，醒来按播放键能接着听
- 计时期间图标变主题色，再点可查看剩余时间、换档或取消
- 不持久化：App 被系统杀掉后定时器消失，不会出现「重启后突然暂停」

## 关于页

指向本仓库：源代码、说明文档（README）、版本发布（Releases）、反馈（GitHub Issues）。

按 GPLv3 保留了版权署名、作者主页和许可证入口；**捐款与支持者区块已移除**——那些链接是给上游作者募捐的，与这个 fork 无关。上一版里指向 `feedback@auxio.app` 的邮件反馈也一并去掉了。

## 致谢与上游

全部功劳归 [OxygenCobalt](https://github.com/OxygenCobalt) 与 Auxio 贡献者。这个分支只加了几行编码修复，没有上游就没有它。

- 上游仓库：https://github.com/OxygenCobalt/Auxio
- 上游 Wiki：https://github.com/OxygenCobalt/Auxio/wiki
- 英文原版说明：[README.upstream.md](README.upstream.md)

## License

[![GNU GPLv3](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)

Auxio is Free Software: you can use, study, share and improve it at your will. Specifically you can redistribute and/or modify it under the terms of the [GNU General Public License](https://www.gnu.org/licenses/gpl.html) as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

本分支同样以 GPL-3.0 发布，源码改动公开可见。
