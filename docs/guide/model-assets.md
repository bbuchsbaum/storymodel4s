# Model assets on demand

Model weights live outside the checkout. The tracked
[manifest](../../tools/model-assets/manifest.json) pins the repository, full immutable
revision, download URL, byte count and SHA-256 of every required asset. The stdlib-only
[manager](../../tools/model-assets.py) downloads or verifies them without running inference.

```sh
python3 tools/model-assets.py list
python3 tools/model-assets.py fetch minilm-onnx --link
python3 tools/model-assets.py verify minilm-onnx --legacy
```

The default cache is `/Volumes/My Passport for Mac/data/models`. The drive must be
mounted; the manager refuses an absent mount instead of creating a substitute directory.
For another location outside the checkout, set `STORYMODEL4S_MODEL_ROOT` or supply `--root`
before the command:

```sh
python3 tools/model-assets.py --root "$HOME/Library/Caches/storymodel4s/models" fetch minilm-onnx --link --relink
```

`--link` installs missing compatibility file symlinks at the exact legacy paths in the
manifest. Existing correct regular files are retained. `--relink` explicitly allows an
existing symlink to point to a newly verified cache; it never deletes that link's old target.
This supports temporarily using an alternate cache while Passport is unavailable.

| ID | Model | Weight bytes, approximately |
| --- | --- | --- |
| `minilm-onnx` | Pinned all-MiniLM-L6-v2 sentence encoder | 90 MB |
| `whisper-cpu` | Systran faster-whisper-large-v3 | 3.09 GB |
| `whisper-mlx` | MLX Whisper large-v3 | 3.08 GB |
| `parakeet-mlx` | MLX Parakeet TDT 0.6B v3 | 2.51 GB |
| `qwen3-asr-8bit` | MLX Qwen3-ASR-1.7B, 8-bit | 2.46 GB |
| `qwen3-vl-4b` | Qwen3-VL-4B-Instruct | 8.88 GB |

Choose an ID to avoid downloading unused models; `all` selects all six. `fetch` without
`--link` only populates the external cache. `link` installs aliases for an already complete
verified bundle. `verify --legacy` checks both cached assets and every legacy alias.
Cache bundles use `<root>/<upstream-repository>/<full-revision>/<upstream-file>`.

The manifest includes tokenizers/configuration as well as weights. It preserves the pinned
bytes and original local file paths: historical ASR method pins, model receipts and source
identities remain unchanged. Qwen3-ASR's existing external location already follows this
layout. Small runtime-bundled VAD assets and tiny generated test fixtures remain in their
installed environments; they are not independent bulk model downloads.

## Relocating existing weights

Migration is an explicit operation with a durable receipt and expected external volume UUID:

```sh
python3 tools/model-assets.py migrate whisper-cpu whisper-mlx parakeet-mlx minilm-onnx --copy-only \
  --expected-volume-uuid YOUR_VOLUME_UUID --receipt /path/to/copy-receipt.json
python3 tools/model-assets.py migrate whisper-cpu whisper-mlx parakeet-mlx minilm-onnx \
  --expected-volume-uuid YOUR_VOLUME_UUID --receipt /path/to/relocation-receipt.json
```

The manager copies complete model bundles from existing local files, verifies the external
copies against the pinned hashes, and replaces only manifest-listed regular weight files
with file symlinks. Small local configuration files and download metadata remain intact.
Additional ONNX copies in `tmp/` and the recorded source03 input directory point to the
same verified external artifact. Receipts count logical bytes replaced, not guaranteed
physical free-space changes on filesystems with clones/hardlinks.

Migration requires an explicit model-ID allowlist; omitted IDs and `all` are refused.
Exclude every model used by a running experiment, including from copy-only operations,
until that entire experiment has terminated. A momentary gap in open handles between
model loads is insufficient clearance.

Before replacement, all selected weight aliases must have their expected bytes and no
open-file handles. The manager rechecks the volume, source identity and open handles
at each actual replacement. An active model job is left running. Use `--copy-only` to establish custody for inactive
bundles first, then perform relocation with a new receipt. macOS migration uses
`diskutil` and `lsof`; ordinary downloads and verification use Python's standard library.

Conflicting existing cache files, changed source files, unsafe paths, wrong volumes,
incomplete downloads and failed hashes refuse. Only this invocation's partial/lock files
are cleaned. Existing receipts are never overwritten; a retry uses a new receipt and reuses
verified cached files. No model weights, participant data or large generated outputs enter Git.

```sh
python3 -m unittest discover -s tools -p 'test_model_assets.py' -v
```
