# Model Export

Run from the repository root with the project virtual environment:

```powershell
.\.venv\Scripts\python.exe .\model-export\export_demucs_onnx.py --validate
```

The script downloads or loads the `htdemucs` model and writes:

```text
android/app/src/main/assets/models/htdemucs_fp32.onnx
```

The ONNX contract is fixed for the Android inference path:

- input `[1, 2, 343980]`, 44.1 kHz stereo float32
- output `[1, 2, 343980]`, instrumental float32
