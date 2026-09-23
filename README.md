# douyin-vocal-remover

一个基于 Kotlin + Jetpack Compose 的安卓 App，参考
[48tools](https://github.com/duan602728596/48tools) 的抖音解析逻辑，在手机本地使用
`htdemucs` ONNX 模型去除视频中的人声，并导出 MP3、WAV 或 FLAC。

## 功能

- 支持抖音短链接、视频链接、图文链接、用户主页、视频 ID 和用户 ID。
- 支持解析单个视频或用户视频列表，并选择清晰度下载。
- 支持本地文件去人声、静音裁剪、输出格式选择、Cookie 管理与代理设置。
- 推理不依赖后端服务，音频和视频文件不自动上传。

## 界面

UI 采用 B 站风格设计语言，全局字体为开源手写风格字体
[霞鹜文楷 Lite（LXGW WenKai Lite）](https://github.com/lxgw/LxgwWenKai-Lite)
（SIL OFL 1.1 协议，声明见 [NOTICE](NOTICE)）。

- **配色**：主色哔哩哔哩粉 `#FB7299`，辅色天蓝 `#00AEEC`，点缀大会员金 `#FFB027`，
  明暗双主题跟随系统。
- **搜索方式下拉栏**：输入卡片顶部可切换三种搜索方式——分享链接 / 视频 ID / 用户主页，
  切换时输入框图标与占位文案联动，已输入内容保留。
- **排版体系**：4pt 网格间距（页面边距 20dp、区块间距 16dp、卡片内边距 16dp），
  结果分区带竖条强调标签与计数徽章。
- **动画**：空状态音符呼吸动画、卡片按压缩放、结果卡片弹性选中、错误横幅缩放淡入。

## 构建

```powershell
cd android
.\gradlew.bat assembleDebug
```

## 模型

本项目不直接提交 ONNX 二进制文件到普通 Git 对象，模型通过 Git LFS 管理。首次构建前先运行：

```powershell
.\.venv\Scripts\python.exe .\model-export\export_demucs_onnx.py
git lfs track "android/app/src/main/assets/models/*.onnx"
```

模型说明：

- `htdemucs_fp32.onnx`（243MB）：默认模型，内存充裕的真机使用，支持 NNAPI 加速。
- `htdemucs_fp16.onnx`（128MB）：低内存设备自动选用（总内存 < 4GB 或应用堆 < 192MB），
  禁用 NNAPI、限制 CPU 线程数并降级优化级别。
- 分离为流式分段处理（7.8 秒/段，0.25 秒重叠），内存占用恒定，不随歌曲长度增长。
- x86 模拟器无法运行 ONNX 推理（会阻塞线程），应用会明确提示使用真机。

## 声明

本项目仅供学习、个人备份和已获授权内容使用。请遵守抖音相关服务条款和当地法律，
不要下载或传播未经授权的内容。本项目不绕过 DRM。

## License

本项目沿用上游 GPL-3.0 许可。上游项目：[duan602728596/48tools](https://github.com/duan602728596/48tools)。
