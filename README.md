# douyin-vocal-remover

一个基于 Kotlin + Jetpack Compose 的安卓 App，参考
[48tools](https://github.com/duan602728596/48tools) 的抖音解析逻辑，在手机本地使用
fp32 `htdemucs` ONNX 模型去除视频中的人声，并导出 MP3、WAV 或 FLAC。

## 功能

- 支持抖音短链接、视频链接、图文链接、用户主页、视频 ID 和用户 ID。
- 支持解析单个视频或用户视频列表，并选择清晰度下载。
- 支持本地文件去人声、静音裁剪、输出格式选择、Cookie 管理与代理设置。
- 推理不依赖后端服务，音频和视频文件不自动上传。

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

## 声明

本项目仅供学习、个人备份和已获授权内容使用。请遵守抖音相关服务条款和当地法律，
不要下载或传播未经授权的内容。本项目不绕过 DRM。

## License

本项目沿用上游 GPL-3.0 许可。上游项目：[duan602728596/48tools](https://github.com/duan602728596/48tools)。
