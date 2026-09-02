# Captioning lane admission record: Qwen3-VL-Instruct

**Bead:** filed with this record (see the mote op committed alongside)

**Court:** the captioning court of the movie plan (`docs/plans/2026-08-29-movie-narrative-architecture.md` §4 timed-language lattice, §6 authority gradient, §10.4 calibration), the lane the admission ledger's `interpret.multimodal` row deferred on 2026-08-29 with "no exact variant, role, runtime, or service selected". This record selects them.

**Owner instruction:** 2026-09-01, "proceed with Qwen3 but we'll be flexible". Nomination and the local court are authorized under ledger §6.3; the flexibility is recorded as the comparison design in §5 below, which admits a second model on the same terms without reopening this record.

**Inspection date:** 2026-09-01. Every fact below was read from the public Hugging Face model API and the small text files of the pinned revisions (`config.json`, `preprocessor_config.json`, `video_preprocessor_config.json`, `README.md`). No weights, tokenizer, or code were downloaded or executed while preparing this record.

## 1. Five axes

| Key | Component | Selection | Rights/access | Role | Operational posture | Realization |
|---|---|---|---|---|---|---|
| `caption.qwen3vl.8b` | `Qwen/Qwen3-VL-8B-Instruct` at revision `0c351dd01ed8` | `Nominated` for the captioning court and the §10.4 visual-only and late-fusion arms | `Conditional`: card licence `apache-2.0`, ungated; weights-licence leaf `HoldUnknown` (§3) | `Proposal` | `LocalRecipe` | `RecipeOnly` |
| `caption.qwen3vl.4b` | `Qwen/Qwen3-VL-4B-Instruct` at revision `ebb281ec70b0` | `Nominated` for the local adapter court only (receipts, preprocessing identity, replay); not a calibration arm | as above | `Proposal` | `LocalRecipe` | `RecipeOnly` |
| `caption.runtime.mlx` | `mlx-vlm` (MIT) on `mlx` (MIT), Apple Silicon | `CandidateOnly` | `PaperworkAdmitted` (MIT); exact versions pinned at realization | runtime only | `LocalRecipe` | `RecipeOnly` |
| `caption.runtime.transformers` | `transformers` (Apache-2.0) with `Qwen3VLForConditionalGeneration`, MPS or CPU | `CandidateOnly` | `PaperworkAdmitted` | runtime only | `LocalRecipe` | `RecipeOnly` |

Rejected for this lane, with reasons: `google/gemma-3-12b-it` (gated, `license:gemma`; owner term acceptance required; ledger rule that no agent accepts gated terms); `Qwen/Qwen2.5-VL-3B-Instruct` (no licence tag at its revision; the 3B tier of that series is not Apache); any remote captioning API (`RemoteConditional`: Sherlock frames are non-redistributable and every transmitted-data question of ledger §3 would open at once). Kept as later candidates on the same terms: `OpenGVLab/InternVL3_5-8B` (Apache-2.0, ungated, revision `9bb6a56ad9cc`) and `HuggingFaceTB/SmolVLM2-2.2B-Instruct` (Apache-2.0, ungated, revision `482adb537c02`).

## 2. Identity

| Field | 8B | 4B |
|---|---|---|
| Upstream locator | `https://huggingface.co/Qwen/Qwen3-VL-8B-Instruct` | `https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct` |
| Revision (git sha, first 12) | `0c351dd01ed8` | `ebb281ec70b0` |
| Last modified (API) | 2025-10-15 | 2025-10-15 |
| Architecture | `Qwen3VLForConditionalGeneration`, `model_type` `qwen3_vl` | same |
| Text config | hidden 4096, 36 layers, vocab 151936, max positions 262144, interleaved MRoPE sections [24, 20, 20] | as read at realization |
| Vision config | patch 16, temporal patch 2, spatial merge 2, hidden 1152, depth 27, 3 channels | same patch, temporal patch, merge |
| Weights | 4 safetensors shards, 17,534,339,512 bytes; LFS sha256 prefixes `d5d0aef0…`, `8be88fb5…`, `83de00ea…`, `0a88b98e…` | 2 shards, 8,875,719,344 bytes; `30a01a05…`, `046296a2…` |
| Tokenizer | `tokenizer.json` (7,032,403 bytes, `c6cc1014…`), `vocab.json`, `merges.txt`, `tokenizer_config.json`, `chat_template.json` | identical `tokenizer.json`, `vocab.json`, `merges.txt` digests |
| Preprocessor | `preprocessor_config.json` `27225450…` and `video_preprocessor_config.json` `7768af27…` (identical across the two variants) | same |
| Config file digests (sha256 of the raw text) | `config.json` `5cd45286…`, `README.md` `6d5d06e0…` | `config.json` `edac7703…`, `README.md` `a884e5e7…` |

Full LFS digests are recorded at realization when the shards are verified; the prefixes above come from the repository tree listing and identify the exact files to expect.

## 3. Rights and access

- **Code.** The modelling code is in `transformers` (Apache-2.0) or `mlx-vlm` (MIT); neither repository carries model code of its own (`library_name: transformers`, no `.py` files in the tree).
- **Weights.** The model card front-matter declares `license: apache-2.0` and the API reports `gated: false`. **No `LICENSE` file exists in either repository at the pinned revision.** Ledger §1 rule 1 says a repository licence does not license separately distributed weights, and here the only licence statement is the card's metadata line. Disposition: `HoldUnknown` on the weights-licence leaf until either (a) a `LICENSE` text appears at a revision and is recorded by digest, or (b) the owner records that the card's `apache-2.0` declaration by the publisher (Alibaba Qwen team) is accepted as the licence of record. This is an owner decision under ledger §1 ("unknown is not admitted"); it blocks realization, not nomination.
- **Access.** No login, credential, or gated term is required; public availability is not permission (§1 rule 5), which is why the leaf above is held rather than assumed.
- **Copyright notices.** The card names the Qwen team; no NOTICE file exists at the revision. A realized bundle must preserve the card text.

## 4. Purpose, stage, permitted and forbidden output

- **Stage.** Plan G1 (timed language) and the §10.4 calibration; input is a bounded set of frames the `media` module has already identified by PTS through its packet index (the same `FrameSet` the detector court consumes), never a media file or a URL.
- **Permitted output family.** Timed visual descriptions and their raw generation records: for each requested extent, the text the model produced, the prompt and sampling identity, and the frame ordinals shown. These land as `Proposal` values (plan §6 table row "ASR text or visual description → derived text or semantic proposal with exact support"); their support is the frame extent, re-anchored to PTS by the adapter.
- **Forbidden authority (ledger §5 pattern).** Cannot establish a shot, scene, event, character identity, action, causality, story-world time, direct observation, dialogue content, or narrative truth; cannot own time (the model's timestamp tokens, if any, are not evidence; the adapter's packet index is); cannot self-accept (plan §6 step 4: only the deterministic resolver accepts); cannot become a `TimedSegment` without a receipt-carrying adapter (the recall-to-video pipeline treats segments as trusted source text today, so a caption enters it only as a proposal with provenance stated).

## 5. The court and the comparison design

**Local adapter court (4B first, then 8B).** F0 (three flat-colour shots) and F1 (the Big Buck Bunny excerpt) frames from the existing `FrameSet`s are grouped into extents (per detector proposal window, and per fixed window as a second condition), the model is asked for a description of each extent under one declared prompt, and the outcome is joined exactly as the detector outcome is: request identity, frame digests, count, geometry, preprocessing identity, applied generation parameters echoed and read back, coverage, and a `ToolRealization` for the runtime observed independently of the outcome. F0's declared colours are the only ground truth and they test the plumbing, not the model. Everything is `Draft`.

**Calibration (plan §10.4), predeclared before any Sherlock frame is shown.** Arms: transcript-only (the existing annotation adapter), visual-only (captions from this model), audio-only (held: no ASR is admitted), late fusion (captions plus transcript through the existing resolver), and one-shot omni (held: no such model is admitted). The question is falsifiable and stated in advance: does the visual-only arm change recall alignment on held-out recalls relative to transcript-only, measured by the existing HSMM courts; "no" is an admissible answer. Recipes for both arms are frozen before scoring; a second model (the owner's flexibility) is admitted only as a further arm under the same frozen recipe, never by swapping mid-comparison.

## 6. Learned artifacts and preprocessing identity

Declared at request time and bound into every receipt; no library default stands in:

- Frame selection: which `FrameSet` ordinals are shown, in which order, and their PTS (from the packet index). The video processor's own sampling (`fps`, `min_frames`, `max_frames` defaults of `Qwen3VLVideoProcessor`) is bypassed: frames are supplied explicitly.
- Resolution: the image processor resizes to a multiple of `patch_size × merge_size` = 32 within `[shortest_edge, longest_edge]` pixel-count limits (`65536`–`16777216` for images, `4096`–`25165824` per frame for video); the declared request states `min_pixels` and `max_pixels` explicitly, and the adapter records the resulting grid.
- Normalization: `image_mean` and `image_std` `[0.5, 0.5, 0.5]` (from the config; recorded, not assumed).
- Temporal patching: `temporal_patch_size` 2, so frames are consumed in pairs; an odd count is padded by the processor, which the outcome must report.
- Prompt: one fixed instruction text, recorded by digest, asking for a plain description of what is visible; no names, no story vocabulary.
- Generation: `max_new_tokens`, sampling switched off (greedy) unless a recipe says otherwise, seed if the runtime honours one; `generation_config.json` at the revision (digest `e116347d…`) is read back and its effective values echoed.
- Quantization: none for the nominated recipe (bf16); any quantized variant (for example an MLX 4-bit conversion) is a separate realization with its own digests and is not this record.

## 7. Runtime

Two candidate runtimes; the realization picks one and pins it:

| Runtime | Packages (PyPI, 2026-09-01) | Notes |
|---|---|---|
| MLX | `mlx-vlm` 0.6.17 (MIT), `mlx` 0.32.2 (MIT), Python ≥ 3.10 | Apple Silicon native; conversions to MLX format are new artifacts with their own digests (see §6 on quantization) |
| transformers | `transformers` 5.16.1 (Apache-2.0), `torch` with MPS, Python ≥ 3.10 | Loads the pinned safetensors directly; slower on Apple Silicon |

Execution form: an untrusted worker under `media/worker/` in the pattern of `scenedetect_worker.py`, uv-locked with hash pins, reading a request that names a frame file by digest and writing an outcome; it never opens a container and never computes a timestamp. Container, SBOM, and vulnerability record: not planned for the local court, stated as held.

## 8. Provenance, network, privacy, distribution

- **Training data.** Not disclosed in detail by the publisher; recorded as `Unknown`. The model cannot be used to argue about a film's content on the basis of what it has seen in training; every caption is a proposal against the shown frames only.
- **Network.** Weights are fetched once from Hugging Face at the pinned revision and verified against the LFS digests; the worker runs offline (`HF_HUB_OFFLINE=1`) and the court asserts no network use in its receipt. No telemetry endpoint is contacted by either runtime by default; this is checked, not assumed, at realization.
- **Privacy.** F0 and F1 carry no person. Sherlock frames stay local; nothing leaves the machine.
- **Distribution.** Weights are cached locally under an ignored path (like the F1 excerpt) and never committed; outcomes (caption text, receipts) are committable after the story-text admission checklist, since machine captions of a copyrighted film are derived text.

## 9. Evidence and resolution

- Sources: Hugging Face model API (`/api/models/<id>`, `/tree/main`) and the raw text files named in §2, read 2026-09-01; PyPI JSON for the runtimes.
- Recheck triggers: any change of the pinned revision (the API `sha`), a LICENSE file appearing, a runtime major version, or a yanked package.
- Remaining unknowns: weights-licence leaf (§3, owner decision); runtime choice and pins (realization); MLX conversion identity if MLX is chosen; the effective video processor behaviour on explicitly supplied frames (verified at realization by reading back the grid and token counts).
- Disposition: `Nominated`, `RecipeOnly`. Realization of the 4B local court proceeds under ledger §6.3 once the §3 leaf is closed by the owner; the 8B calibration arm follows the same path.
