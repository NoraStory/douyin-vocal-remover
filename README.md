<div align="center">

# 🎵 抖音去人声 · Douyin Vocal Remover

**下载抖音视频 · 本地 AI 分离人声 · 一键导出伴奏**

<p>
  <img src="https://img.shields.io/github/v/release/NoraStory/douyin-vocal-remover?style=for-the-badge&color=FB7299&label=Release" alt="release">
  <img src="https://img.shields.io/github/license/NoraStory/douyin-vocal-remover?style=for-the-badge&color=00AEEC" alt="license">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android" alt="platform">
  <img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin" alt="kotlin">
  <img src="https://img.shields.io/badge/推理-完全本地-00BFA5?style=for-the-badge" alt="offline">
</p>

*B 站风格 UI · 霞鹜文楷字体 · 无后端 · 无上传*

</div>

---

## ✨ 特性

| 能力 | 说明 |
|---|---|
| 🔗 **多格式解析** | 分享链接、分享口令、视频 ID、用户主页，四种输入全支持 |
| 🛡 **风控免疫** | 完整 Argus 签名体系（指纹参数 + x-secsdk-web-signature），告别 403 |
| 🔐 **登录直通** | 内置 WebView 登录抖音账号，登录态豁免匿名限流；触发验证码自动弹窗恢复 |
| 🎧 **本地分离** | htdemucs ONNX 模型纯本地推理，人声/伴奏分离不经过任何服务器 |
| 🧠 **流式内存** | 7.8 秒分段推理 + 重叠混合，内存占用恒定，长视频不 OOM |
| ⚡ **双模型自适应** | fp32 真机高精度 / fp16 低内存设备自动降级，4 线程 CPU 推理 + 前台服务保活 |
| 🎚 **多格式导出** | MP3 320k / 256k / 128k、WAV、FLAC，支持静音裁剪 |
| 📂 **公开保存 + 历史** | 伴奏存入 `Download/抖音去人声/伴奏/`（文件管理器可见），应用内历史列表一键分享 |
| 🔄 **自动更新** | 双源检测（Gitee 优先 + GitHub 兜底）每 5 小时，APK 瘦身至 ~129MB，普通更新一键装；落后一个大版本强制更新 |
| 🎨 **B 站美学** | 主色 #FB7299 + 天蓝 #00AEEC，明暗双主题，弹性动画 |

## 📥 下载安装

### 直接安装（推荐）

前往 [Releases](https://github.com/NoraStory/douyin-vocal-remover/releases/latest) 下载最新版 APK：

```
douyin-vocal-remover-v1.2.0.apk
```

安装时允许「未知来源应用」即可。要求 **Android 8.0+（API 26）**、**arm64-v8a** 真机。

> 💡 x86_64 仅模拟器使用，模拟器无法运行本地人声分离（ONNX 推理限制，App 会明确提示）。

### 从源码构建

```powershell
cd android
.\gradlew.bat assembleRelease   # 需先按下方说明准备签名与模型
```

## 🏗 技术架构

```
douyin-vocal-remover
├── android/
│   ├── app/            # Compose UI（B 站风格、验证弹窗、搜索下拉栏）
│   ├── douyin/         # 抖音解析核心
│   │   ├── DouyinApi           # 请求链：指纹参数 + a_bogus + websign 签名
│   │   ├── DouyinTargetParser  # 链接/ID/分享口令识别
│   │   ├── DouyinWebSession    # WebView 预热 + cookie 白名单收集
│   │   └── JsSignatureEngine   # bdms.js / acrawler 本地签名
│   ├── audio-engine/   # 音频引擎：ffmpeg 提取 + ONNX 流式分离 + 编码
│   └── settings/       # 输出格式等设置
└── model-export/       # PyTorch → ONNX 导出脚本（fp32/fp16）
```

### 风控签名体系（403 免疫的关键）

抖音边缘网关 ArgusSecurityPlugin 对 `aweme/detail`、`aweme/post` 等接口做门禁校验，缺任一参数即 403：

```
完整指纹参数集（19 项，与 UA 严格一致）
  + a_bogus（bdms.js 本地签名）
  + x-secsdk-web-signature = md5(uifid_timestamp_SALT_query)
  + verifyFp / fp（与 s_v_web_id cookie 同源）
  + x-tt-argus 请求头
```

多通道降级链：`aid=6383` → 刷新 `__ac_signature` → `aid=1128` → 1/2/5s 退避重试 → 弹窗验证。

### 分享口令解析

抖音口令末尾的 `A@T.lc kCh:/` 是**服务端签发的令牌**，本地无法离线还原（公开调研结论，所有开源解析器均不支持）。本项目的两级兜底：

1. **短码试探** — `v.douyin.com/{code}` 302 命中 share 页则直接解析
2. **标题搜索** — 提取口令【作者】+《标题》，走登录态搜索接口找回视频

## 🧪 模型说明

模型文件已从 APK 分离（APK 从 394MB 瘦身到 ~129MB），**首次启动需联网下载一次**（应用内自动完成，支持断点续传），之后更新应用无需重复下载：

| 模型 | 大小 | 适用 |
|---|---|---|
| `htdemucs_fp32.onnx` | 231 MB | 内存充裕的真机（≥ 4GB RAM，默认） |
| `htdemucs_fp16.onnx` | 122 MB | 低内存设备自动选用（< 4GB RAM） |

模型资产随 Release 发布（仅首次上传，后续版本复用），下载时按设备内存自动选档，SHA-256 校验完整性。

**推理加速说明**：模型 92 个卷积里 80 个是 1D 卷积（Demucs 为时域模型），NNAPI/XNNPACK 只加速 2D 卷积，启用后绝大多数算子仍回退 CPU 且引入分区拷贝开销——因此全部走 ORT CPU EP（4 线程 + ALL_OPT），这是该模型的实测最优配置。处理期间应用会挂起前台服务（常驻通知），防止 vivo OriginOS 等激进后台管理系统在切后台/息屏后冻结进程导致推理"卡住"。

模型经 Git LFS 管理，首次构建前运行：

```powershell
.\.venv\Scripts\python.exe .\model-export\export_demucs_onnx.py
```

## 🔄 自动更新

- **双源检测**：Gitee 优先（国内直连），失败自动切 GitHub；每 5 小时周期检测 + 每次冷启动即时检测
- **差异化更新**：模型与 APK 分离，日常更新只下载 ~129MB 的 APK，模型不动
- **强制更新**：落后一个大版本及以上（如 1.4.x → 1.5.0）时全屏弹窗不可跳过；仅 patch 落后（1.4.0 → 1.4.1）为普通提醒
- **一键安装**：应用内下载（断点续传）→ 自动拉起系统安装器

发布新版本使用一键脚本（详见脚本内注释）：

```powershell
.\tools\publish_release.ps1 -Version "1.5.1" -NotesFile "tools\_release_notes_151.md"
```

## ❓ FAQ

**Q：解析一直提示「触发抖音风控」？**
A：新版已根治（签名体系），如仍出现说明触发了 IP 限流——切换网络（Wi-Fi/流量）或稍后再试。

**Q：必须登录吗？**
A：不需要。匿名 + 预热即可正常解析；登录可豁免匿名限流，且是分享口令标题搜索的前提。

**Q：视频/音频会上传到服务器吗？**
A：不会。人声分离完全在手机本地离线完成，仅解析阶段联网。

**Q：支持哪些输入？**
A：分享链接、分享口令（"复制打开抖音"文案）、纯数字视频 ID、用户主页链接。

## ⚖️ 免责声明

本项目仅供学习、个人备份和已获授权内容使用。请遵守抖音相关服务条款与当地法律，不要下载或传播未经授权的内容。本项目不绕过 DRM。

## 🙏 致谢

- [48tools](https://github.com/duan602728596/48tools) — 抖音解析与验证码恢复流程参考
- [Evil0ctal/Douyin_TikTok_Download_API](https://github.com/Evil0ctal/Douyin_TikTok_Download_API) — x-secsdk-web-signature 逆向成果
- [NanmiCoder/MediaCrawler](https://github.com/NanmiCoder/MediaCrawler) — x-tt-argus / uifid 风控参数
- [kd64i/dyparse](https://github.com/kd64i/dyparse) — WebView 预热与 cookie 白名单方案
- [霞鹜文楷 Lite](https://github.com/lxgw/LxgwWenKai-Lite) — 开源手写字体（SIL OFL 1.1）

## 📄 License

[GPL-3.0](LICENSE) · 沿用上游 48tools 许可
