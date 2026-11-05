<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" width="120"></p>
<h1 align="center"><b>Auxio · 中文修复版</b></h1>
<h4 align="center">A simple, rational music player for Android —— 修好了中文标签乱码，还加了歌词和定时停止。</h4>
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
    <img alt="Minimum SDK" src="https://img.shields.io/badge/API-24%2B-1450A8?style=flat">
</p>

[Auxio](https://github.com/OxygenCobalt/Auxio) 的个人分支。如果你有一批老 MP3，歌名显示成 `ÊÅÈ¥ÈÕ×Ó`、`ÕâÊÇ¸èÇú` 这种鬼东西，这里就是解药。

## 和上游的区别

| | 功能 | 说明 |
|---|---|---|
| 1 | **修正中文标签乱码** | `ÊÅÈ¥ÈÕ×Ó` 显示成「逝去日子」，读的时候翻译，不改文件 |
| 2 | **应用内修复标签编码** | 把改错的编码写回文件，车机、foobar 也跟着正常 |
| 3 | **歌词** | 扫本地 `.lrc` 自动绑定，可选在线补全，播放时能看 |
| 4 | **定时停止** | 20 / 40 / 60 分钟三档，到点自动暂停 |

其余代码、界面、功能与上游保持一致。

## 下载

到 [Releases](https://github.com/xinghee/Auxio/releases) 取包。两个包代码完全一样（都是 R8 裁剪版），只差打包了哪些 ABI 的原生库。

| 文件 | 大小 | 什么时候用 |
|---|---|---|
| `Auxio_*_arm64.apk` | **6.4 MB** | **默认选这个**，2017 年之后的手机基本都是 arm64 |
| `Auxio_*_universal.apk` | **10.8 MB** | 模拟器、老 32 位设备、不确定机型时 |

版本号有两种形式，都是同一套代码，按时间取最新的即可：

- `v4.1.5-cjk.N` —— 手工发版，带完整说明
- `cjk-YYYYMMDD-HHMM` —— CI 自动构建，tag 是北京时间，标为 Pre-release

- 包名 `org.oxycblt.auxio.debug`，**可以和官方正式版并存**
- **签名从 `cjk.5` 起固定**，新版本直接覆盖安装即可，播放列表和设置都不会丢
- 装完在系统设置里看版本号后缀分辨：`4.1.5-arm64` / `4.1.5-universal`
- 签名指纹（校验用）：`A1:E8:90:EA:A4:3C:DA:17:…:35:84:59:81`
- 首次打开会重新扫描媒体库，中文歌名即刻生效

## 用法

### 修复标签编码

设置 → **内容** → 「音乐」分组 → **修复标签编码**

自动扫描已添加的音乐目录，逐条列出 `原值 → 修复后`，**勾选后才写入**。写入前把原 ID3 区块备份到应用私有目录，页面提供「撤销上次修复」。

只处理 MP3 的 ID3v2；FLAC/Ogg 的内嵌标签不在范围内。

### 歌词

**绑定**：设置 → 内容 → 音乐分组 → **歌词**。扫「音乐目录」下所有子目录的 `.lrc`（包括 `Musiclrc` 这种独立文件夹），逐首匹配并如实报告绑上了多少首。

匹配优先级：`[ti:]` 精确 → 从标题提取 `《》`→`[]`→`【】` → 字符二元组相似度 ≥ 0.82。

**在线补全**：页面顶部开关，打开后对未绑定的歌逐首搜索，结果缓存到本地，之后离线可看。

**播放时看**：播放面板歌名右侧的**歌词图标**（⋯ 左边），或长按歌名。歌词居中显示，当前行加粗并用主题色高亮，平滑滚动跟随播放位置；双语分两行，点任意一行跳到该时间点。

**歌词不对时**：歌词页右上角的**放大镜**，联网搜当前这首歌，列出候选让你挑（歌名 + 歌手 + 时长）。选中后下载替换，并标记为手动选择——之后再扫描不会被自动匹配覆盖。

只支持文本 `.lrc` 和在线源；FLAC/Ogg 内嵌歌词、`.hmrc` 等加密格式不在范围内。

### 定时停止

播放面板顶部工具栏的**时钟图标**，三档：20 / 40 / 60 分钟。

- 按真实时间倒计时，中途暂停也继续走
- 到点只暂停，不退出、不清通知，醒来按播放键能接着听
- 计时期间图标变色，再点可看剩余时间、换档或取消

## 权限

| 权限 | 用途 |
|---|---|
| 存储 | 读取并播放音乐文件 |
| 前台服务 / WAKE_LOCK | 后台持续播放 |
| 通知 | 显示播放状态与扫描进度 |
| **INTERNET** | **仅用于可选的在线歌词补全，默认关闭** |

上游 Auxio 没有网络权限。这里加 `INTERNET` 只为在线歌词：只有在「歌词」页面手动打开开关并点「在线补全」时才发请求，抓到的歌词缓存到本地。播放过程本身从不联网。

## 乱码是怎么来的

Auxio 4.0 起改用自带 TagLib 解析标签。而早期中文打标工具（千千静听、酷狗那一代）写 ID3v2 时，`encoding` 字节声明 `ISO-8859-1`，实际塞进去的却是 GBK 字节。TagLib 严格按声明用 Latin-1 解码，于是「逝去日子」被一个字节一个字符地读成 `ÊÅÈ¥ÈÕ×Ó`。

好在 Latin-1 是单射（0x00–0xFF 一一映射），原字节一个没丢，可以完整还原后重新解码。为了不误伤正常的西文标签，修复要求：已经含非 Latin-1 字符的放行、高位字节必须构成偶数长度的连续段、解码结果必须含汉字且无控制字符。

## 从源码构建

上游要求 `cmake` + `ninja-build` + NDK `28.2.13676358` + JDK 21，且**不能在 Windows 上构建**（Media3 的自定义构建跑 Unix shell 脚本）。所以走 GitHub Actions：

- Actions 页面点 **Run workflow**（fork 的 workflow 不随 push 自动跑）
- 一次出两个包，构建完成后**自动创建 Release 并上传**，不用再手动搬运
- 上游的 ffmpeg 子模块通过 GitHub 镜像拉取（`git.ffmpeg.org` 在 CI 上经常连不通）

本地可调 ABI：

```bash
./gradlew app:packageCanary                        # 默认 arm64-v8a
./gradlew app:packageCanary -PauxioAbi=all         # 全 ABI
./gradlew :musikr:testDebugUnitTest                # 只跑单测
```

与上游同步：

```bash
git remote add upstream https://github.com/OxygenCobalt/Auxio.git
git fetch upstream && git rebase upstream/dev
```

## 致谢与上游

全部功劳归 [OxygenCobalt](https://github.com/OxygenCobalt) 与 Auxio 贡献者，没有上游就没有这个分支。英文原版说明见 [README.upstream.md](README.upstream.md)。

## License

[![GNU GPLv3](https://www.gnu.org/graphics/gplv3-127x51.png)](https://www.gnu.org/licenses/gpl-3.0.en.html)

本分支同样以 GPL-3.0 发布，源码改动公开可见。
