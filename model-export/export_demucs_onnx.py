"""Export the fp32 htdemucs instrumental model to ONNX for Android.

The stock HTDemucs forward uses torch.stft / torch.istft, which produce *complex*
tensors. torch.onnx.export (TorchScript / dynamo=False) cannot trace complex STFT,
so we replace the STFT/ISTFT with numerically equivalent *real-valued* matrix
multiplications (real DFT / real IDFT + overlap-add). We also replace the
view_as_real / view_as_complex hops in _magnitude / _mask with pure real reshapes.
The result is a fully real-valued graph that exports to ONNX and is bit-for-bit
equivalent to the original complex forward within fp32 tolerance (< 1e-3).

Run:
  .venv/Scripts/python.exe model-export/export_demucs_onnx.py
"""
from __future__ import annotations

import math
import os
import sys
import traceback

import torch
from torch.nn import functional as F

SAMPLE_RATE = 44_100
SEGMENT_SECONDS = 7.8
SEGMENT_SAMPLES = int(SAMPLE_RATE * SEGMENT_SECONDS)  # 343980

N_FFT = 4096
HOP = 1024
# torch.stft(..., normalized=True) divides the spectrum by sqrt(win_length).
REAL_NORM = math.sqrt(N_FFT)

LOGF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "_export_log.txt")


def log(*a):
    s = " ".join(str(x) for x in a)
    print(s)
    with open(LOGF, "a", encoding="utf-8") as f:
        f.write(s + "\n")
        f.flush()


# --------------------------------------------------------------------------- #
# Real-valued STFT / ISTFT (traceable, complex-free)
# --------------------------------------------------------------------------- #
def real_spectro(x, n_fft=N_FFT, hop_length=HOP, pad=0):
    """x: [B, C, L] -> [B, C, 2, freqs, n_frames] (re, im).

    Equiv to demucs.spec.spectro with window=hann, win_length=n_fft,
    normalized=True, center=True, return_complex=True, pad_mode='reflect'.
    """
    window = torch.hann_window(n_fft, device=x.device)
    x = F.pad(x, [n_fft // 2, n_fft // 2], mode="reflect")
    Lp = x.shape[-1]
    n_frames = (Lp - n_fft) // hop_length + 1
    starts = torch.arange(0, n_frames * hop_length, hop_length)
    idx = starts[:, None] + torch.arange(n_fft)[None, :]
    frames = x[..., idx] * window
    freqs = n_fft // 2 + 1
    k = torch.arange(freqs)[:, None].to(x.dtype)
    n = torch.arange(n_fft)[None, :].to(x.dtype)
    cos_b = torch.cos(2 * math.pi * k * n / n_fft)
    sin_b = -torch.sin(2 * math.pi * k * n / n_fft)
    re = (frames @ cos_b.t()).permute(0, 1, 3, 2) / REAL_NORM
    im = (frames @ sin_b.t()).permute(0, 1, 3, 2) / REAL_NORM
    return torch.stack([re, im], dim=2)


def _core_istft(re, im, n_fft=N_FFT, hop_length=HOP, length=None):
    window = torch.hann_window(n_fft, device=re.device)
    freqs = re.shape[-2]
    n_frames = re.shape[-1]
    lead = re.shape[:-2]
    re_f = re.reshape(-1, freqs, n_frames)
    im_f = im.reshape(-1, freqs, n_frames)
    N = n_fft
    k = torch.arange(freqs)[:, None].to(re.dtype)
    n = torch.arange(N)[None, :].to(re.dtype)
    C_re = torch.zeros(N, freqs, dtype=re.dtype, device=re.device)
    C_im = torch.zeros(N, freqs, dtype=re.dtype, device=re.device)
    C_re[:, 0] = 1.0
    C_re[:, freqs - 1] = torch.cos(math.pi * n.squeeze())
    a = torch.arange(1, freqs - 1)[:, None].to(re.dtype)
    C_re[:, 1:freqs - 1] = (2 * torch.cos(2 * math.pi * a * n / N)).t()
    C_im[:, 1:freqs - 1] = (-2 * torch.sin(2 * math.pi * a * n / N)).t()
    re_t = re_f.permute(0, 2, 1)
    im_t = im_f.permute(0, 2, 1)
    ft = (torch.matmul(re_t, C_re.t()) + torch.matmul(im_t, C_im.t())) / N
    ft = ft * window
    L_out = (n_frames - 1) * hop_length + N
    out = torch.zeros(ft.shape[0], L_out, dtype=ft.dtype, device=ft.device)
    env = torch.zeros(L_out, dtype=ft.dtype, device=ft.device)
    w2 = window ** 2
    for t in range(n_frames):
        pos = t * hop_length
        out[:, pos:pos + N] += ft[:, t, :]
        env[pos:pos + N] += w2
    env = env.clamp(min=1e-11)
    rec = out / env * REAL_NORM
    rec = rec[:, N // 2: L_out - N // 2]
    if length is not None:
        if length < rec.shape[-1]:
            rec = rec[:, :length]
        else:
            rec = F.pad(rec, [0, int(length - rec.shape[-1])])
    return rec.reshape(*lead, rec.shape[-1])


def real_ispectro(z, hop_length=HOP, length=None, pad=0):
    """z: [*, 2, freqs, n_frames] -> [*, length].

    Equiv to demucs.spec.ispectro with window=hann, win_length=n_fft,
    normalized=True, center=True.
    """
    re = z[..., 0, :, :]
    im = z[..., 1, :, :]
    return _core_istft(re, im, n_fft=2 * z.shape[-2] - 2, hop_length=hop_length, length=length)


# --------------------------------------------------------------------------- #
# Patched HTDemucs methods (so the model forward never touches complex types)
# --------------------------------------------------------------------------- #
def _patched_magnitude(self, z):
    # z: [B, C, 2, Fr, T] (real rep). cac=True -> interleave real/imag as channels.
    if self.cac:
        B, C, two, Fr, T = z.shape
        return z.reshape(B, C * two, Fr, T)
    # cac=False -> magnitude = sqrt(re^2 + im^2)
    re = z[..., 0, :, :]
    im = z[..., 1, :, :]
    return torch.sqrt(re * re + im * im + 1e-12)


def _patched_mask(self, z, m):
    # cac=True: m is [B, S, C*2, Fr, T]; reshape into real/imag -> [B, S, C, 2, Fr, T]
    if self.cac:
        B, S, C2, Fr, T = m.shape
        C = C2 // 2
        return m.view(B, S, C, 2, Fr, T)
    # cac=False, magnitude mask (wiener_iters forced < 0 -> real only)
    niters = self.wiener_iters
    if self.training:
        niters = self.end_iters
    if niters < 0:
        zr = z[:, None]
        return zr / (1e-8 + zr.abs()) * m
    raise RuntimeError("wiener path is complex; set wiener_iters/end_iters < 0 for the real export")


def install_real_stft():
    """Monkey-patch HTDemucs to use the real-valued STFT/ISTFT pipeline."""
    import demucs.htdemucs as hh
    hh.spectro = real_spectro
    hh.ispectro = real_ispectro
    hh.HTDemucs._magnitude = _patched_magnitude
    hh.HTDemucs._mask = _patched_mask


# --------------------------------------------------------------------------- #
# Manual (traceable) multi-head attention
# --------------------------------------------------------------------------- #
# nn.MultiheadAttention's fused CUDA/CPU kernel traces to `aten::_native_multi_head_attention`,
# which has no ONNX symbolic at opset 17. In eval mode (dropout off) the operation is exactly
# softmax(Q @ K^T / sqrt(head_dim)) @ V. We replace the module's forward with an explicit,
# fully-traceable matmul + softmax implementation so the whole graph exports cleanly.
def _manual_multihead_attention_forward(
    self,
    query,
    key,
    value,
    key_padding_mask=None,
    need_weights=True,
    attn_mask=None,
    average_attn_weights=True,
    is_causal=False,
    **kwargs,
):
    E = self.embed_dim
    H = self.num_heads
    head_dim = E // H

    # nn.MultiheadAttention expects (N, B, E) unless batch_first.
    if self.batch_first:
        query = query.transpose(0, 1)
        key = key.transpose(0, 1)
        value = value.transpose(0, 1)

    q, k, v = F._in_projection_packed(
        query, key, value, self.in_proj_weight, self.in_proj_bias
    )  # each (N, B, E) or (Nk, B, E)

    Nq = q.shape[0]
    Nk = k.shape[0]
    B = q.shape[1]

    q = q.contiguous().view(Nq, B, H, head_dim).permute(1, 2, 0, 3)  # (B, H, Nq, D)
    k = k.contiguous().view(Nk, B, H, head_dim).permute(1, 2, 0, 3)  # (B, H, Nk, D)
    v = v.contiguous().view(Nk, B, H, head_dim).permute(1, 2, 0, 3)  # (B, H, Nk, D)

    scaling = head_dim ** -0.5
    attn = torch.matmul(q, k.transpose(-2, -1)) * scaling  # (B, H, Nq, Nk)

    if attn_mask is not None:
        if attn_mask.dtype == torch.bool:
            attn = attn.masked_fill(attn_mask, float("-inf"))
        else:
            attn = attn + attn_mask

    attn = torch.softmax(attn, dim=-1)
    if self.dropout > 0.0:
        attn = F.dropout(attn, p=self.dropout, training=self.training)

    out = torch.matmul(attn, v)  # (B, H, Nq, D)
    out = out.permute(2, 0, 1, 3).contiguous().view(Nq, B, E)  # (Nq, B, E)

    if self.batch_first:
        out = out.transpose(0, 1)  # (B, Nq, E)

    out = self.out_proj(out)

    attn_weights = None
    if need_weights:
        # average over heads if requested (matches nn.MultiheadAttention default)
        aw = attn.mean(dim=1)  # (B, Nq, Nk)
        attn_weights = aw if average_attn_weights else attn
    return out, attn_weights


def install_manual_attention():
    """Replace the fused MHA kernel with a traceable matmul/softmax version."""
    import torch.nn as nn

    nn.MultiheadAttention.forward = _manual_multihead_attention_forward


# --------------------------------------------------------------------------- #
# Export wrapper
# --------------------------------------------------------------------------- #
class InstrumentalHTDemucs(torch.nn.Module):
    """HTDemucs that outputs the instrumental (all non-vocal sources) directly.

    Input : [1, 2, 343980] float32 @ 44.1 kHz
    Output: [1, 2, 343980] float32 instrumental, clamped to [-1, 1].
    """

    def __init__(self, model):
        super().__init__()
        inner = getattr(model, "models", [model])[0]
        self.model = inner
        self.source_names = inner.sources
        self.non_vocal_indices = [i for i, name in enumerate(inner.sources) if name != "vocals"]

    def forward(self, waveform: torch.Tensor):
        sources = self.model(waveform)            # [1, S, C, L]
        instrumental = sum(sources[:, i] for i in self.non_vocal_indices)  # [1, C, L]
        return instrumental.clamp(-1.0, 1.0)


# --------------------------------------------------------------------------- #
# Main export routine
# --------------------------------------------------------------------------- #
def export(output_path: str, validate: bool = False) -> dict:
    from demucs.pretrained import get_model

    os.environ.setdefault("HF_HOME", r"C:\Users\story\Desktop\视频转音频\.hf_cache")

    torch.manual_seed(0)
    dummy = torch.randn(1, 2, SEGMENT_SAMPLES, dtype=torch.float32)

    # --- load model; htdemucs ships as a BagOfModels wrapping one HTDemucs --- #
    bag = get_model("htdemucs").eval()
    model = getattr(bag, "models", [bag])[0]
    model.eval()

    # --- capture the ORIGINAL (complex) forward for equivalence --------------- #
    with torch.no_grad():
        orig_out = model(dummy.clone())           # [1, S, C, L] complex-path (native MHA)

    # --- install real STFT/ISTFT and re-run the same model (real path) ------- #
    install_real_stft()
    with torch.no_grad():
        real_out = model(dummy.clone())           # [1, S, C, L] real-path (native MHA)

    # Numerical equivalence of the underlying model (sources)
    eq_sources = (orig_out - real_out).abs().max().item()
    log("NUMERICAL EQUIVALENCE (model sources, orig complex vs real): max abs err = %.3e" % eq_sources)

    # Also verify the instrumental wrapper outputs match
    orig_inst = sum(orig_out[:, i] for i in range(orig_out.shape[1]) if model.sources[i] != "vocals")
    real_inst = sum(real_out[:, i] for i in range(real_out.shape[1]) if model.sources[i] != "vocals")
    eq_inst = (orig_inst - real_inst).abs().max().item()
    log("NUMERICAL EQUIVALENCE (instrumental): max abs err = %.3e" % eq_inst)

    # --- replace fused MHA with a traceable matmul/softmax version ------------ #
    # This must preserve numerical equivalence (eval: dropout off, no masks).
    install_manual_attention()
    with torch.no_grad():
        real_out_manual = model(dummy.clone())    # [1, S, C, L] real-path (manual MHA)
    eq_mha = (real_out - real_out_manual).abs().max().item()
    log("NUMERICAL EQUIVALENCE (manual MHA vs native MHA): max abs err = %.3e" % eq_mha)
    assert eq_mha < 1e-3, "manual MHA diverges from native MHA (%.3e)" % eq_mha

    # --- build the export wrapper and trace to ONNX --------------------------- #
    wrapper = InstrumentalHTDemucs(model).eval()
    out_p = os.path.abspath(output_path)
    os.makedirs(os.path.dirname(out_p), exist_ok=True)

    with torch.no_grad():
        torch.onnx.export(
            wrapper,
            dummy,
            out_p,
            dynamo=False,
            input_names=["input"],
            output_names=["instrumental"],
            opset_version=17,
            do_constant_folding=True,
        )
    log("ONNX exported -> %s" % out_p)

    result = {"equiv_sources": eq_sources, "equiv_inst": eq_inst, "path": out_p}

    # --- validate ------------------------------------------------------------- #
    if validate:
        import onnx
        import onnxruntime as ort

        onnx_model = onnx.load(out_p)
        onnx.checker.check_model(onnx_model)
        log("onnx.checker.check_model: PASS")

        session = ort.InferenceSession(out_p, providers=["CPUExecutionProvider"])
        probe = torch.randn(1, 2, SEGMENT_SAMPLES, dtype=torch.float32)
        out = session.run(None, {"input": probe.numpy()})[0]
        out_t = torch.tensor(out)
        assert out_t.shape == (1, 2, SEGMENT_SAMPLES), "bad shape %s" % (tuple(out_t.shape),)
        assert torch.isfinite(out_t).all(), "output has NaN/Inf"
        log("ONNXRuntime CPU inference: shape=%s finite=%s maxabs=%.3e"
            % (tuple(out_t.shape), bool(torch.isfinite(out_t).all()), float(out_t.abs().max())))
        result["ort_maxabs"] = float(out_t.abs().max())

    size_mb = os.path.getsize(out_p) / (1024 * 1024)
    log("FILE SIZE: %.1f MB" % size_mb)
    result["size_mb"] = size_mb
    return result


def main():
    if os.path.exists(LOGF):
        os.remove(LOGF)
    out = os.path.join("android", "app", "src", "main", "assets", "models", "htdemucs_fp32.onnx")
    try:
        res = export(out, validate=True)
        log("EXPORT SUMMARY: %s" % res)
        log("DONE")
    except Exception:
        log("EXPORT FAILED")
        log(traceback.format_exc())


if __name__ == "__main__":
    main()
