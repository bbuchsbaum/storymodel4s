# Recorded semantic evidence through inference

This surface-embedding recipe augments the existing lexical nominations, then uses the same
local evidence gates, costs and sparse sequence inference. It does not change narrative
identity or either account. Source construction remains independent of recall.

```python
from storymodel import prepare_semantic, semantic_prompt, resolve_semantic

request = prepare_semantic(source, recall, lexical_spec, pair,
                           max_comparisons=4096, max_semantic_candidates=32)
# Supply actual declared-model vectors or explicit missing/ineligible/failed outcomes.
prompt = semantic_prompt(request)
result = resolve_semantic(request, recorded_reply)
posterior = result.inference
```

For an executable offline example, run `python examples/semantic_inference.py OUTPUT` from
`port/`. It extends the existing account-inference controls: one source, two recalls, lexical
and semantic inference artifacts, exact recorded replies and cross-recall cache reuse. Its
vectors are scripted geometry controls; no model execution or accuracy improvement is claimed.

## Contract

- Spaces bind provider, model/revision/checksum, dimension, normalization, query/document role,
  instruction, preprocessing and surface view. Comparison requires compatible spaces; instruction
  asymmetry requires explicit permission. Imported metadata is a declaration, not model attestation.
- Cache keys bind the complete space and exact UTF-8 material. Only observed vectors are cached.
  Repeated text can share a vector while distinct source anchors/recall occurrences remain distinct.
  Source material joins ordered support spans with a single space; it does not include unsupported
  gaps. Material construction is part of the versioned recipe.
- L2 validation accepts a norm tolerance of `1e-6 + ulp(1)`, normalizes outside a two-ulp rounding
  band, and preserves that band on repeated reads. Unnormalized zero vectors are valid stored
  observations but have undefined cosine distances. Nonfinite/wrong-dimension vectors are refused.
  Max-scaled cosine avoids overflow and underflow for finite extreme-scale inputs.
- Raw cosine distance is in `[0, 2]`; the local channel explicitly uses half-cosine in `[0, 1]`.
  Neither is a calibrated probability. Missing evidence retains the existing declared pricing
  policy and remains excluded from measured support. Ineligibility remains separate.
- Per recall unit, scan at most `max_comparisons` observed targets in stable ID order. Keep at most
  `max_semantic_candidates` nearest semantic nominees, breaking ties by stable target ID. Merge
  those nominees with lexical targets; do not allocate a dense all-pairs matrix. The bound is per
  unit, not a global budget. Stable-prefix scanning is a bounded baseline, not approximate-neighbor
  indexing or a guarantee that the globally closest candidate was visited.
- Explicit caller targets define the entire admissible universe, including an empty universe.
  Filter that universe before counting comparisons. Unscanned lexical targets retain an explicit
  missing-semantic reason; undefined zero-vector comparisons do not masquerade as budget loss.
- Result artifacts retain lexical retrieval, semantic availability, scan/cutoff limits, local
  cells and actual inference separately. Gates run before prices; perfect vector similarity
  cannot restore a faithful state ruled out by polarity, role or context evidence.
- Malformed vector rows become failed outcomes without discarding valid siblings. Foreign,
  duplicate or already-cached keys reject the batch. Omitted requested keys remain unattempted.
  Empty material is ineligible and is not requested. Request hashes bind complete account
  snapshots, policies and input cache. Verification recomputes against independently held inputs.

`semantic_prompt`, recording import/replay and CLI export check both account grants. Private
in-memory resolution is allowed, consistent with other computational APIs. The cache contains
vectors and plain content hashes, not a privacy-preserving HMAC store; handle it according to the
source data's permissions. No raw model/provider execution is hidden in this API.

## CLI and recording

```text
storymodel prepare-semantic SOURCE RECALL LOCAL_SPEC PAIR --cache CACHE --output REQUEST
storymodel semantic-prompt REQUEST --output PROMPT
storymodel import-semantic REQUEST RECORDING_ENVELOPE RECORDING_DIR
storymodel replay-semantic REQUEST RECORDING_DIR --output RESULT
storymodel verify-semantic REQUEST RAW_REPLY RESULT
```

`--cache` is optional. The recording envelope is `SemanticRecording(request_sha256, origin,
reply)`, with the exact raw response string in `reply`; verification takes the raw reply file.
Import records bytes before vector parsing. Replay makes no provider calls and checks the
recorded input/profile and exact reply. `--expected-import-sha256` additionally binds the import
file to an independently retained digest. Interrupted import recovery is not yet a capability
of this wrapper; the shared execution engine remains the basis for the study recovery work.

The new request/result schemas are in [semantic.json](../schemas/semantic.json). Existing
account/local-evidence schemas and old recipes are unchanged. See the
[preservation map](preservation.md) for Scala source pointers and remaining obligations.

## Still owed

Learned embedding execution, ONNX/WL and structured chart channels, all retained semantic
profiles, privacy-aware persistent cache policy, native-media numerical inference, approximate
search where justified, and dataset-level semantic evaluation remain separate work. This
recorded surface channel does not finish P3/P4/P5 or prove scientific accuracy.
