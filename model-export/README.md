# Model Export

Run from the repository root with the project virtual environment:

```powershell
$env:HF_HOME="C:\Users\story\Desktop\视频转音频\.hf_cache"
.\.venv\Scripts\python.exe .\model-export\export_demucs_onnx.py
```

The script downloads or loads the `htdemucs` model and writes:

```text
android/app/src/main/assets/models/htdemucs_fp32.onnx
```

The script always runs the full validation (equivalence + `onnx.checker` +
ONNXRuntime CPU inference) and appends a human-readable report to
`model-export/_export_log.txt`.

The ONNX contract is fixed for the Android inference path:

- input `[1, 2, 343980]`, 44.1 kHz stereo float32
- output `[1, 2, 343980]`, instrumental float32, clamped to `[-1, 1]`

## How the export works

PyTorch's TorchScript ONNX exporter (`dynamo=False`, opset 17) cannot trace the
`aten::_native_multi_head_attention` fused kernel, and the stock HTDemucs forward
relies on `torch.stft` / `torch.istft`, which produce complex tensors that the
exporter also cannot trace. The script works around both:

1. **Real-valued STFT/ISTFT.** `demucs.htdemucs` `spectro`/`ispectro` and the
   `_magnitude`/`_mask` complex reshapes are monkey-patched with numerically
   equivalent real-only implementations (explicit cos/sin DFT basis matrices +
   Hermitian-symmetry overlap-add IDFT). `torch.stft(..., normalized=True)`
   divides by `sqrt(win_length)`, which is reproduced exactly (`REAL_NORM =
   sqrt(4096) = 64`). `cac=True` stays enabled.
2. **Manual multi-head attention.** `torch.nn.MultiheadAttention.forward` is
   replaced with an explicit `softmax(Q @ K^T / sqrt(head_dim)) @ V`
   implementation (eval mode: dropout off, no attention mask). This removes the
   fused `_native_multi_head_attention` op and produces a fully traceable graph.

The `InstrumentalHTDemucs` wrapper sums all non-vocal sources (`drums`, `bass`,
`other`) and clamps to `[-1, 1]`.

## Current export status

`htdemucs_fp32.onnx` is generated and validated. Latest run (PyTorch 2.14.0,
onnx 1.23.0, onnxruntime 1.30.0):

- **File**: `android/app/src/main/assets/models/htdemucs_fp32.onnx`
- **Size**: ~231.3 MB (fp32)
- **ONNX checker**: PASS
- **ONNXRuntime CPU inference**: output shape `(1, 2, 343980)`, all finite
- **Numerical equivalence vs original complex forward** (max abs error):
  - model sources: `3.49e-04` (< `1e-3`)
  - instrumental: `2.36e-04` (< `1e-3`)
  - manual MHA vs native fused MHA: `2.15e-06` (effectively exact)

The exported graph is a drop-in for the Android inference path: feed
`[1, 2, 343980]` stereo float32 @ 44.1 kHz and read back the instrumental.
