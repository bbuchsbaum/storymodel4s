# storymodel4s embed-onnx

`embed-onnx` is the JVM-only local sentence-encoder adapter from ADR 0001 D4a. It implements the
portable `Embedder[Id]` contract; no ONNX Runtime or DJL type crosses into `embed-core`.

The first admitted encoder is pinned, not looked up by a mutable model name:

- model: `sentence-transformers/all-MiniLM-L6-v2`
- revision: `1110a243fdf4706b3f48f1d95db1a4f5529b4d41`
- model file: `onnx/model.onnx`
- model SHA-256: `6fd5d72fe4589f189f8ebc006442dbb529bb7ce38f8082112682524616046452`
- tokenizer file: `tokenizer.json`
- tokenizer SHA-256: `be50c3628f2bf5bb5e3a7f17b1f74611b2561a3a27eeab05e5aa30f411572037`
- license: Apache-2.0

The 90 MB model is deliberately not vendored. Supply the two exact files as local paths. Startup
checks both checksums and the ONNX input/output schema before constructing an embedder.

ONNX Runtime 1.29 initializes native telemetry before Java can disable it, so the adapter refuses
startup unless telemetry was disabled before the JVM began:

```sh
export ORT_DISABLE_TELEMETRY=1
```

The ordinary test gate uses a tiny committed non-learned fixture and needs no download. To also run
the pinned real-model differential and War of the Ghosts comparison, export:

```sh
export STORYMODEL4S_ONNX_MODEL=/absolute/path/to/model.onnx
export STORYMODEL4S_ONNX_TOKENIZER=/absolute/path/to/tokenizer.json
sbt embedOnnx/test embedBench/test
```

The differential golden was produced independently with ONNX Runtime Python 1.23.2, Hugging Face
tokenizers 0.21.4, and NumPy 2.2.6. The Java side uses ONNX Runtime 1.29.0 and DJL tokenizers 0.36.0.
The committed WOG report is diagnostic evidence only: it does not calibrate or select a default.
