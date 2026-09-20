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

## Current export status

The exporter uses the legacy TorchScript ONNX pipeline. The `htdemucs` model
uses `istft` with complex tensor types internally, and PyTorch 2.14's ONNX
exporter does not support `istft` with complex outputs. The export script is
committed as the integration point, but the ONNX file generation requires either
a custom STFT/ISTFT wrapper that avoids complex types, or an older PyTorch
export path before the Android app can run local separation end to end.
