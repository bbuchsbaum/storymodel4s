# Model weights on demand

Model bytes stay outside Git. `tools/model-weights.json` pins public download URLs,
revisions, byte counts and SHA-256 hashes for MiniLM, Whisper, Parakeet and Qwen.
Bundles include their configuration and tokenizers. These pins preserve the existing
models; they are not new model-quality or inference qualification.

Run from the repository root with Python 3.9+ (standard library only):

```sh
python3 tools/model_weights.py list
python3 tools/model_weights.py fetch minilm
python3 tools/model_weights.py verify minilm
```

The default destination is `~/.cache/storymodel4s/weights`. Select `whisper`,
`parakeet`, `qwen` or `all` explicitly. No download occurs on import or during
normal library startup.

To use Passport (its existing model directory):

```sh
export STORYMODEL4S_WEIGHTS_ROOT="/Volumes/My Passport for Mac/data/models"
python3 tools/model_weights.py fetch whisper
python3 tools/model_weights.py verify all
```

Alternatively pass `--root /your/model/cache` on each command. Model directories
are printed by the script, and have the structure `<root>/<publisher>/<model>/<revision>`.
Point the relevant inference runner at that directory. For MiniLM set
`STORYMODEL4S_ONNX_MODEL` to its `model.onnx` and
`STORYMODEL4S_ONNX_TOKENIZER` to its `tokenizer.json`.

Downloads use temporary files beside the destination, verify bytes before atomic
publication, and refuse to overwrite any existing mismatched file. Interrupted
transfers restart; temporary files left by a killed process can be removed after
confirming that process has exited. Downloads through symlinked parent directories
are refused. Existing valid symlinks can be verified and reused.

## This checkout's storage migration

Whisper, Parakeet and MiniLM weights were moved to Passport's model directory using
copy, SHA-256 readback, then replacement of each original weight file with a symlink.
Small companion files remain locally and are also present in the external bundles.
Qwen was already external. The manifest's `legacy_directory` records compatibility
paths for this checkout; the downloader does not rewrite those paths automatically.

The local migration receipt is `data/models/weights-passport-20261021.json`.
Passport must be mounted to use those compatibility links. An unmounted drive
produces an error; the downloader will not silently recreate a directory under
`/Volumes` or replace a dangling link. To work without Passport, download into
the default local cache and point the runner at the printed bundle path.

No datasets, media, environments or historical analysis outputs are moved.
