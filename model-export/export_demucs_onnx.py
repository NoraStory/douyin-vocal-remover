"""Export the fp32 htdemucs instrumental model to ONNX for Android."""

from __future__ import annotations

import argparse
from pathlib import Path

import torch


SAMPLE_RATE = 44_100
SEGMENT_SECONDS = 7.8
SEGMENT_SAMPLES = int(SAMPLE_RATE * SEGMENT_SECONDS)


class InstrumentalHTDemucs(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model
        self.non_vocal_sources = [name for name in model.sources if name != "vocals"]

    def forward(self, waveform: torch.Tensor):
        ref = waveform.mean(dim=0)
        mean = ref.mean()
        std = ref.std() + 1e-8
        normalized = (waveform - mean) / std
        sources = self.model(normalized[None])[0]
        instrumental = sum(sources[name] for name in self.non_vocal_sources)
        return (instrumental * std + mean).clamp(-1.0, 1.0)


def export(output_path: Path, validate: bool = False) -> None:
    from demucs.pretrained import get_model

    model = get_model("htdemucs").eval()
    wrapper = InstrumentalHTDemucs(model).eval()
    dummy = torch.zeros(1, 2, SEGMENT_SAMPLES, dtype=torch.float32)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        wrapper,
        dummy,
        str(output_path),
        input_names=["input"],
        output_names=["instrumental"],
        opset_version=17,
        do_constant_folding=True,
    )

    if validate:
        import onnx
        import onnxruntime as ort

        onnx_model = onnx.load(str(output_path))
        onnx.checker.check_model(onnx_model)
        session = ort.InferenceSession(str(output_path), providers=["CPUExecutionProvider"])
        result = session.run(None, {"input": dummy.numpy()})[0]
        if tuple(result.shape) != (1, 2, SEGMENT_SAMPLES):
            raise RuntimeError(f"Unexpected ONNX output shape: {result.shape}")

    print(f"exported {output_path} with shape [1, 2, {SEGMENT_SAMPLES}]")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("android/app/src/main/assets/models/htdemucs_fp32.onnx"),
    )
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    export(args.output, args.validate)


if __name__ == "__main__":
    main()
