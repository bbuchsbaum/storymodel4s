# Movie acquisition component admission ledger

**Bead:** `bd-01M188P265MZVEKBD5BBN34E6T`

**Court disposition:** `Authorized(bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7, local draft realization)` since 2026-09-01 (§6.1); was `Held` for the documentation-only B0 component-ledger leg of 2026-08-29

**Inspection date:** 2026-08-29

**Governing plan:** [`2026-08-29-movie-narrative-architecture.md`](../plans/2026-08-29-movie-narrative-architecture.md), accepted at SHA-256 `b12f79ceb61e020cd02c43c631ed015a9034d9c3b9c597da726337425a21e748`

**Owner-action bead:** `bd-01M184XP908JX51S3ZYFX20HC7`

This ledger answers one bounded question:

> Which exact acquisition components may be used for which declared movie
> courts, under what rights, operational, privacy, and scientific-authority
> constraints?

It does not admit a film, participant recall, benchmark annotation, provider
request, or finished runtime artifact. It does not close overall B0. Sherlock's
artifact-specific storage disposition is resolved: only actual episode video
contents remain external and non-redistributable, while non-video artifacts are
eligible for ordinary admission. Exact source/edition binding, the two-run
timebase repair, per-artifact provenance and story-text checks, segmentation and
semantic authority, P1, component realization, and implementation authorization
remain independent gates.

No gated terms were accepted and no login, credential, source media,
participant content, model weight, container, package, or executable was
obtained while preparing this record.

## 1. Non-conflation contract

A component is never described by one overloaded `Disposition`. Five axes are
recorded independently.

| Axis | Permitted values | Question answered |
|---|---|---|
| **Selection** | `Nominated`, `CandidateOnly`, `Deferred`, `Excluded` | Is this component part of a declared court? |
| **Rights/access** | `PaperworkAdmitted`, `Conditional`, `HoldUnknown`, `Incompatible`, `OwnerActionRequired` | Do public records establish the intended use and access path? |
| **Scientific role** | `DeterministicTransform`, `Measurement`, `Proposal`, `Critic`, `Unresolved` | What kind of output may it produce? |
| **Operational posture** | `LocalRecipe`, `LocalRealized`, `RemoteConditional`, `Unresolved` | Where does execution occur, and what data leaves the project? |
| **Realization** | `RecipeOnly`, `Realized(digest)`, `Unresolved` | Are we recording only a recipe/policy, identifying actual bytes, or recording that neither exists? |

`PaperworkAdmitted` means that public primary records support the declared,
purpose-bounded recipe. It does not mean that a future binary is bit-for-bit
reproducible, that every codec is patent-free in every jurisdiction, or that a
runtime artifact has been verified. Only a later `Realized(digest)` record may
make claims about actual executable, model, tokenizer, quantizer, container, or
native-library bytes.

`Conditional` is only a registry summary for a component whose leaf claims
have different dispositions. It is never sufficient for use: the detailed
record must identify every admitted and held leaf, and any required leaf that
is not admitted keeps the component unavailable.

Scientific role is a nonempty set when a component emits more than one typed
output family. Each output keeps its own authority: a raw `Measurement` does
not turn a derived `Proposal` into an observation, and a proposal never gains
acceptance merely because the same component also emitted measurements.

`RecipeOnly` likewise records a bounded build or acquisition policy; it does
not assert that the recipe has configured, built, run, or reproduced
bit-for-bit. Those are separately receipted realization and replay claims.

Court authorization is orthogonal to all five component axes. `Held` means no
component in that court may be installed or executed; a future
`Authorized(bead, scope)` must name the governing bead and exact permitted
scope. No component status in this ledger confers authorization by itself, and
no court is authorized here.

The following inheritance rules are binding:

1. a repository licence does not license separately distributed weights;
2. a package licence does not license its transitive dependencies, container,
   tokenizer, quantizer, training media, or provider service;
3. a source-asset licence does not license benchmark annotations or participant
   recalls;
4. a code licence does not establish empirical capability, calibration, or
   narrative authority;
5. public availability is not permission, and unknown is not admitted;
6. a composite such as WhisperX is admitted only when every selected leaf is
   independently identified and admitted.

## 2. Candidate registry

The registry prevents an attractive research menu from silently becoming an
implementation plan. Full leaf records are required only for `Nominated`
components.

| Key | Family or component | Selection | Rights/access | Role | Operational posture | Realization | Current reason |
|---|---|---|---|---|---|---|---|
| `decode.ffmpeg` | FFmpeg | `Nominated` | `Conditional` | `DeterministicTransform` | `LocalRealized` | `Realized(13d17dfd…, 85c119a5…)` (§6.2, 2026-09-01) | Exact source and LGPL-only policy paperwork-admitted; 9.0.1 built locally from the signature-verified tarball with the F0/F1-selected decoders (rawvideo, h264), static, no GPL/version3/nonfree; reproducible-build, SBOM, OCI, patent and jurisdiction claims still `HoldUnknown` |
| `boundary.pyscenedetect.content` | PySceneDetect `ContentDetector` | `Nominated` | `Conditional` | `Measurement` + `Proposal` | `LocalRecipe` | `RecipeOnly` | Exact code, raw-metric output, and existence/localization proposal policy are paperwork-admitted; realized runtime remains held |
| `asr.whisper` | OpenAI Whisper | `CandidateOnly` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Possible later timed-language court; no variant selected |
| `asr.parakeet` | NVIDIA Parakeet family | `CandidateOnly` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Comparison candidate only; no exact code, checkpoint, tokenizer, runtime, or permission record selected |
| `asr.remote` | Cloud transcription baseline | `Deferred` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | No provider selected; service terms, region, transmitted data, logging, retention, training, deletion, credentials, and cost all remain unknown |
| `align.mfa` | Montreal Forced Aligner plus language assets | `CandidateOnly` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Possible later word-alignment court; every language asset is separate |
| `pipeline.whisperx` | WhisperX composite | `Deferred` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Umbrella record is insufficient; every constituent leaf must be admitted |
| `diarization.pyannote` | pyannote.audio and `community-1` | `Deferred` | `OwnerActionRequired` | `Proposal` | `Unresolved` | `Unresolved` | Gated terms belong to the owner-action bead; no agent accepts them |
| `boundary.transnetv2` | TransNetV2 | `Deferred` | `HoldUnknown` | `Measurement` + `Proposal` | `Unresolved` | `Unresolved` | Scores and derived boundary candidates retain separate authority; code and checkpoint rights must be separated; learned-checkpoint-free baseline comes first |
| `speaker.active` | TalkNet, Light-ASD, and related active-speaker checkpoints | `Deferred` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Checkpoint permission and detector closure unresolved |
| `tracking.bytetrack` | ByteTrack association only | `CandidateOnly` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Association may consume typed detections and propose tracks; it cannot establish character or action identity |
| `tracking.visual` | Face encoders, SAM-family detection/tracking | `Deferred` | `HoldUnknown` | `Measurement` + `Proposal` | `Unresolved` | `Unresolved` | Vector measurements and detection/track proposals retain separate authority; no exact detector, checkpoint, or privacy posture selected |
| `feature.video` | DINO, V-JEPA, VideoMAE, InternVideo/InternVL, Perception Encoder | `Deferred` | `HoldUnknown` | `Measurement` | `Unresolved` | `Unresolved` | Research menu only; no exact provider selected |
| `feature.audio` | CLAP, OpenBEATs | `CandidateOnly` | `HoldUnknown` | `Measurement` | `Unresolved` | `Unresolved` | Later audio-feature court only; exact artifact and preprocessing not selected |
| `feature.audiovisual` | PE-AV and related shared AV spaces | `CandidateOnly` | `HoldUnknown` | `Measurement` | `Unresolved` | `Unresolved` | Later joint-feature court only; exact artifact, modality coverage, and preprocessing not selected |
| `audio.separation` | SAM Audio and related source-separation models | `Deferred` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Optional derived-evidence tool only; no exact code, weights, prompts, runtime, or stem-retention policy selected |
| `interpret.multimodal` | Qwen-family vision or omni models, Vid2Seq, ActionFormer/TriDet | `Deferred` | `HoldUnknown` | `Unresolved` | `Unresolved` | `Unresolved` | Bounded proposal/critique only; no exact variant, role, runtime, or service selected — superseded for the captioning court by the two `caption.qwen3vl.*` rows below (record: `docs/design/vlm-captioning-admission-record.md`, 2026-09-01) |
| `caption.qwen3vl.8b` | `Qwen/Qwen3-VL-8B-Instruct` @ `0c351dd01ed8` | `Nominated` (captioning court; §10.4 visual-only and late-fusion arms) | `PaperworkAdmitted` for local research use (card `apache-2.0`, ungated; owner decision 2026-09-02); redistribution `HoldUnknown` | `Proposal` | `LocalRecipe` | `RecipeOnly` | Timed visual descriptions as proposals over frames the `media` module identified; cannot own time or self-accept; see the admission record §4 |
| `caption.qwen3vl.4b` | `Qwen/Qwen3-VL-4B-Instruct` @ `ebb281ec70b0` | `Nominated` (local adapter court only) | as above | `Proposal` | `LocalRealized` | `Realized(30a01a05…, 046296a2…)` (2026-09-02; record §10) | Adapter, receipts, preprocessing identity and replay proven on F0; the 8B arm is not realized |
| `diarization.sortformer` | Sortformer family | `Deferred` | `HoldUnknown` | `Proposal` | `Unresolved` | `Unresolved` | Research menu only; no exact artifact selected |

`Big Buck Bunny`, Sintel, FilmFestival, Sherlock, Brain Treebank, MF2, and the
project-authored F0 bundle are source, corpus, or benchmark records. Their
current dispositions live in plan §9.1 and are not duplicated here. MF2,
Sherlock, FilmFestival, any human recall corpus, and the pyannote gated terms
remain linked to the owner's separate action list.

## 3. Required leaf record

Every `Nominated` component record must answer all of the following. A missing
answer is a typed hold, not an invitation to infer a favorable value.

| Family | Required fields |
|---|---|
| Identity | Stable key; pipeline role; exact upstream locator; commit, tag, release, API version, or model revision; inspection date |
| Purpose | Declared stage and court; permitted output family; forbidden claims and authority |
| Code | Exact licence evidence; copyright notices; source/archive checksum when realized |
| Learned artifacts | Exact checkpoint, weights licence, access gate, tokenizer, vocabulary, quantizer, preprocessing, schema, and configuration |
| Runtime | Execution form; build recipe; base image; native libraries; codecs; exact enabled and disabled build surface; configure flags; package lock or SBOM |
| Provenance | Training-data and benchmark provenance as known, explicitly unknown, or inapplicable |
| Network and privacy | Whether outbound calls are possible; offline enforcement when promised; exact endpoint or service class; transmitted data classes; region; logging; retention; provider training; deletion; credentials |
| Distribution | Vendoring, caching, CI, redistribution, and output-retention posture |
| Evidence | Immutable primary-source references; exact licence or policy document identity; checked version and date; independent reviewer; expiry or recheck trigger for mutable terms, model cards, weights, images, and service policies |
| Resolution | Remaining unknowns; owner-action link; purpose-bounded disposition; `RecipeOnly` or realized digests |

## 4. Nominated record: FFmpeg

The source coordinate and conservative LGPL-only policy are nominated from
public primary sources. No executable, container, fixture codec surface, or
native closure is admitted, so the component remains unavailable to
implementation.

| Field | Record |
|---|---|
| Key | `decode.ffmpeg` |
| Purpose | Deterministic ingest for the project-authored F0 bundle and one exact F1 excerpt; expose checked container/stream metadata, exact rational timebases, native PTS/DTS, sample duration or typed absence, and decode receipts |
| Forbidden authority | Cannot establish shot, scene, event, identity, transcript, causality, narrative truth, or cross-edition correspondence; successful decode does not admit the input asset |
| Exact source identity | FFmpeg 9.0.1 “Lei”, released 2026-08-12; tag `n9.0.1`; annotated-tag object `501bb49457b9dfb25d6a208832e0a6e6cd53108d`; peeled commit [`bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa`](https://github.com/FFmpeg/FFmpeg/commit/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa); source archive `https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz`; detached signature `https://ffmpeg.org/releases/ffmpeg-9.0.1.tar.xz.asc`. The official [download record](https://ffmpeg.org/download.html) is the release authority. Tarball SHA-256 and local signature verification are deliberately `Unknown` because neither artifact was acquired in this bead |
| Release authentication | Official release key fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8`; verification procedure recorded by the [FFmpeg project](https://ffmpeg.org/download.html). A later realization must verify the downloaded tarball and record its digest; publication metadata is not local verification |
| Code licence | Source policy: `PaperworkAdmitted` for an LGPL-2.1-or-later-only configuration. The [licence record pinned to the peeled commit](https://raw.githubusercontent.com/FFmpeg/FFmpeg/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa/LICENSE.md) states that `--enable-gpl` changes the resulting build to GPL, `--enable-version3` changes the applicable licence version, and `--enable-nonfree` makes the result unredistributable. All three are forbidden, as are external GPL codec libraries such as libx264 and libx265. Source-code licence and codec/patent exposure remain separate. “LGPL-only” is a claim about an exact realized configuration, never a repository-level label |
| Copyright and notices | A future corresponding-source bundle must preserve all per-file upstream copyright headers plus commit-pinned [`LICENSE.md`](https://raw.githubusercontent.com/FFmpeg/FFmpeg/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa/LICENSE.md), [`COPYING.LGPLv2.1`](https://raw.githubusercontent.com/FFmpeg/FFmpeg/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa/COPYING.LGPLv2.1), and [`COPYING.LGPLv3`](https://raw.githubusercontent.com/FFmpeg/FFmpeg/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa/COPYING.LGPLv3). Exact binary-notice and native-dependency notice bundles remain `HoldUnknown` until the selected source and runtime closure is realized |
| Patent posture | `HoldUnknown`. FFmpeg's [legal record](https://ffmpeg.org/legal.html) does not supply patent clearance and notes jurisdictional variation. A future court must record its deployment jurisdiction and exact codecs separately from source-code licensing |
| Learned artifacts and training provenance | Inapplicable to the nominated deterministic source build. No weights, tokenizer, quantizer, or training corpus enters this component. Codec and fixture rights remain separate rather than being relabelled as model provenance |
| Enabled functionality | Candidate probe-only surface: `ffprobe`, shared `libavformat`, `libavcodec`, and `libavutil`, local `file` protocol, and `mov` demuxer. No decoder is selected until F0 and F1 manifests establish exact stream requirements. Network, capture, GUI, hardware acceleration, filters, resampling, scaling, encoders, muxers, external codec libraries, and general autodetection remain disabled. Commit-pinned [`configure`](https://raw.githubusercontent.com/FFmpeg/FFmpeg/bf1b838f2ab88b4f8fd83443325c782ea0e0f7fa/configure) is the authority for available switches and transitive selections |
| Forbidden configuration | `--enable-gpl`, `--enable-version3`, `--enable-nonfree`, static linking, external GPL codecs, network protocols, devices, hardware acceleration, and undeclared autodetected libraries |
| Input-security policy | Admit only explicit local paths inside the staged attempt input. Network protocols remain disabled. MOV external data references and absolute-path loading remain disabled and are asserted in the attempt receipt. Edit-list handling is explicit and receipted because it affects presentation time. No media-controlled path may expand the admitted input set |
| Native and transitive closure | `HoldUnknown`: exact OS, architecture, compiler, linker, libc, package snapshot, selected components, dynamic dependencies, binary/shared-library hashes, SBOM, vulnerability record, and OCI digest do not yet exist. Expected probe-only closure is not accepted evidence |
| Execution | `LocalRecipe`; no remote media transfer |
| Network, telemetry, privacy, and credentials | The nominated recipe must be locally enforced offline: no service, endpoint, account, credential, outbound media, telemetry, provider logging, retention, training, or residency dependency |
| Distribution | Do not vendor a binary through this documentation bead. A later realized artifact requires digests, notices, exact corresponding source and configuration, relink/compliance posture, and platform-specific records. The project will follow the official [LGPL compliance checklist](https://ffmpeg.org/legal.html); this ledger is engineering policy, not legal advice |
| Caching and CI | No source archive, binary, shared library, or container is cached or installed by this bead. A future cache key must include exact source digest, configure report, toolchain/platform identity, native closure, and binary/container digests. Ordinary CI may use injected typed receipts and project-authored fixture envelopes; optional integration courts require an already admitted local artifact and may not download or build it silently. Final cache and CI policy remains `HoldUnknown` until realization |
| Output retention | A future court may retain receipted metadata, packet/sample indexes, hashes, diagnostics, and explicitly admitted derived excerpts. It must not silently copy source streams or preserve decoded media beyond the declared attempt policy. Exact output paths, formats, and deletion/retention rules are `HoldUnknown` until that court is specified |
| Realization | `Realized(13d17dfd…)` for `ffprobe` and `Realized(85c119a5…)` for `ffmpeg` (local static build from the verified 9.0.1 tarball, §6.2); the candidate configure line below remains the recipe, with the §6.2 deviations recorded |
| Evidence freshness | The peeled commit and content-addressed Git objects are the stable evidence coordinate. The tag-to-object mapping, release/signature page, current security and patch posture, legal guidance, fixture codecs, native closure, and deployment jurisdiction must be revalidated immediately before realization and whenever the selected release or build surface changes. A tag name or mutable project page is never treated as immutable evidence |
| Rights/access | Source coordinate and LGPL-only build policy: `PaperworkAdmitted`. Fixture-specific codec and patent posture: `HoldUnknown`. Realized runtime: `HoldUnknown` |
| Court authorization | `Authorized(bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7, local draft realization)` by §6.1 (2026-09-01); the nominated 9.0.1 recipe is realized locally as recorded in §6.2 and remains unproven as reproducible |

The smallest presently defensible configuration is a **candidate recipe**, not
a proven configure invocation:

```sh
./configure \
  --prefix=/opt/storymodel4s/ffmpeg-9.0.1 \
  --fatal-warnings \
  --disable-gpl \
  --disable-version3 \
  --disable-nonfree \
  --disable-autodetect \
  --disable-iconv \
  --disable-zlib \
  --disable-bzlib \
  --disable-lzma \
  --disable-network \
  --disable-avdevice \
  --disable-avfilter \
  --disable-swscale \
  --disable-swresample \
  --disable-hwaccels \
  --disable-asm \
  --disable-runtime-cpudetect \
  --disable-doc \
  --disable-debug \
  --disable-everything \
  --disable-ffmpeg \
  --disable-ffplay \
  --enable-ffprobe \
  --disable-static \
  --enable-shared \
  --enable-protocol=file \
  --enable-demuxer=mov
```

A realized build must capture `ffbuild/config.log`, `ffprobe -version`,
`ffprobe -buildconf`, exact enabled component lists, dynamic dependencies,
binary and library hashes, an SBOM, and a signed build receipt. The build must
be network-disabled and use a non-root OCI base pinned by manifest digest. Two
independent clean builds must be compared; if their bytes differ, the artifact
may still be content-addressed but the recipe must not be described as
reproducible.

Fixture closure remains intentionally unresolved:

| Fixture | Established requirement | Held requirement |
|---|---|---|
| Project-authored F0 | No final stream manifest exists | Container, stream set, codecs, timing tables, generator, and byte hash. A proposed MOV/rawvideo/PCM/mov-text shape is not yet a fixture contract |
| F1 Big Buck Bunny excerpt | The official candidate is an MP4-family input, so `mov` demuxing is required | Archive and inner-file hashes, actual stream codecs/profiles, timebases, edit lists, keyframes, exact excerpt, attribution receipt, and every decoder |

Every realized input manifest must classify every stream as `Decoded`,
`PacketIndexedOnly`, or `Unsupported`. Silently ignoring an unexpected stream
is forbidden. Packet-aligned stream copying and arbitrary-frame re-encoding
are distinct courts; use of libx264 would cross the nominated GPL boundary and
requires a separate admission decision.

## 5. Nominated record: PySceneDetect

The code nomination is exact. Executable admission remains held because the
runtime dependency and native-wheel closure is not yet exact.

| Field | Record |
|---|---|
| Key | `boundary.pyscenedetect.content` |
| Purpose | Produce raw receipted `ContentDetector` metrics plus typed boundary-existence and boundary-localization proposals over a declared, completely partitioned presentation extent |
| Forbidden authority | Cannot establish an accepted cut, transition morphology or extent, narrative scene, event, causality, story-world time, direct observation, or a negative boundary claim from empty output. Morphology and extent require distinct typed candidates and a separate court; a visual discontinuity proposal is not a narrative boundary |
| Exact source identity | `scenedetect-headless` 0.7.1; Git tag `v0.7.1`; commit [`6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2`](https://github.com/Breakthrough/PySceneDetect/tree/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2) |
| Published artifacts | Upstream identifies `scenedetect_headless-0.7.1-py3-none-any.whl` as SHA-256 `0594131cf2da278ac9f9442e1ce2a8fe6ccb25ab83a0899f349784e688d304c6` and the source distribution as `aa3201929f26359860a8dae3668eceadd1b9233e2731200c408728d57cb04d44`; both carry trusted-publishing provenance on the [PyPI 0.7.1 record](https://pypi.org/project/scenedetect-headless/0.7.1/). These are public metadata, not locally rehashed artifacts |
| Code licence | `PaperworkAdmitted`: [BSD-3-Clause at the exact commit](https://github.com/Breakthrough/PySceneDetect/blob/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2/LICENSE). Source and binary redistribution require preservation of its notice and conditions |
| Package choice | Nominate `scenedetect-headless`, not the discontinued/yanked `scenedetect-core` split; upstream records that reversal in [issue 558](https://github.com/Breakthrough/PySceneDetect/issues/558) |
| Algorithm | B0 nominates only [`ContentDetector` at the exact commit](https://github.com/Breakthrough/PySceneDetect/blob/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2/scenedetect/detectors/content_detector.py), a learned-checkpoint-free classical detector. “Weightless” is avoided because its algorithm still has numeric component weights. Detector type, threshold, minimum length, HSV/edge weights, luma flag, kernel, flash filter, units, resolved automatic values, and any wrapper post-processing enter identity |
| Other algorithms | `AdaptiveDetector`, `ThresholdDetector`, `HashDetector`, and `HistogramDetector` remain `CandidateOnly`; the [commit-pinned inventory](https://github.com/Breakthrough/PySceneDetect/blob/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2/scenedetect/detectors/__init__.py) identifies and imports those classes but does not define their constructors. Because they are not nominated, their implementation and constructor identities are deliberately not admitted here. `TransnetV2Detector` is excluded from this record because it requires a pretrained network, model path, and ONNX Runtime |
| Learned artifacts | None permitted in the first court |
| Training and benchmark provenance | Inapplicable to the nominated `ContentDetector`; it has no learned checkpoint or training corpus. Any future learned detector is a separate component record and may not inherit this status |
| Decode and time boundary | PySceneDetect consumes BGR24 frames already identified by the checked media atlas through the direct [commit-pinned detector interface](https://github.com/Breakthrough/PySceneDetect/blob/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2/scenedetect/detector.py). `detect()`, `open_video()`, PyAV, MoviePy, bundled FFmpeg, split-video, backend fallback, frame number, `FrameTimecode`, and nominal frame rate cannot own source evidence. Emitted boundary proposals refer to source sample IDs and obtain PTS only through the atlas mapping |
| Preprocessing identity | `HoldUnknown`: exact source pixel format, color primaries, transfer, matrix, range, rotation, crop, scale, interpolation, BGR24 conversion, frame inclusion interval, gaps, failures, and end-of-stream flushing must be declared in the realized attempt. No library default may stand in for a recorded value |
| Declared direct dependencies | The exact [commit-pinned headless package manifest](https://github.com/Breakthrough/PySceneDetect/blob/6ebb72392de8acfb6c539bf15d0aa912ce7ab6b2/packaging/variants/pyproject-scenedetect-headless.toml) requires Python 3.10 or newer, Click `~=8.0, !=8.3.0`, NumPy, `opencv-python-headless`, Platformdirs, and tqdm; all but Click are unbounded |
| Native and transitive closure | `HoldUnknown`: target Python patch/ABI/OS/architecture, exact wheel hashes, NumPy/OpenCV native libraries and embedded notices, complete transitive lock, SPDX or CycloneDX SBOM, vulnerability record, and runtime image digest are absent. The [OpenCV Python licensing record](https://github.com/opencv/opencv-python#licensing) warns that wheels include separately governed native components |
| Container posture | Upstream's image is excluded: it broadens the court with an unpinned base, apt FFmpeg/mkvmerge, and PyAV/MoviePy. A later project-owned non-root image must pin its base by digest, install hash-pinned wheels offline, include the SBOM and licence bundle, prohibit network access, and record its image digest |
| Execution | `LocalRecipe`; no remote media transfer |
| Network, telemetry, privacy, and credentials | The nominated recipe must be locally enforced offline: no service, endpoint, account, credential, outbound media, telemetry, provider logging, retention, training, or residency dependency |
| Distribution | Prefer the attested upstream wheel over vendoring, but do not obtain or vendor a package or container through this bead. A later realized artifact requires independently verified hashes, embedded notices, exact lock/SBOM, and platform-specific records |
| Caching and CI | No wheel, dependency, frame, metric cache, or container is acquired by this bead. A future cache key must include the complete decode ledger, preprocessing identity, detector recipe, runtime lock, platform, native closure, and artifact digests. Ordinary CI uses injected typed outcomes and project-authored fixture envelopes; optional integration courts require already admitted local artifacts and may not download them silently. Final cache and CI policy remains `HoldUnknown` until realization |
| Output retention | A future court may retain detector metrics and receipted `BoundaryProposal`s keyed by the full decode and runtime identity. It may not retain undeclared frame copies or promote proposals into narrative boundaries. Exact attempt paths, formats, and deletion/retention rules are `HoldUnknown` until realization |
| Realization | `RecipeOnly` |
| Evidence freshness | The full source commit, its commit-pinned files, and recorded artifact digests are the stable evidence coordinates. Revalidate the `v0.7.1` tag-to-commit mapping, PyPI provenance and yanking state, dependency metadata, mutable issue/project pages, embedded notices, and vulnerability state when a runtime lock is proposed, whenever any selected artifact changes, and immediately before implementation authorization. A tag name or mutable project page is never treated as immutable evidence |
| Rights/access | Code/package: `PaperworkAdmitted`. Runtime closure: `HoldUnknown` |
| Court authorization | `Authorized(bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7, local draft realization)` by §6.1 (2026-09-01); a hash-pinned local install of `scenedetect-headless` 0.7.1 is within scope, a project-owned container is not |

## 6. First-court disposition

The intended acquisition proof remains deliberately narrow:

1. project-authored F0 media tests exact stream identity, rational timebase,
   PTS/DTS refusal, gaps, variable frame rate, and replay (F0 v1 realizes
   holds, not presentation gaps; the gap court runs on an edited packet
   table, and no fixture yet produces a gap, a missing timestamp, or a
   discard or corrupt flag from real tool output);
2. one separately admitted 20–40 second F1 excerpt tests real container and
   codec behavior;
3. FFmpeg performs deterministic ingest only;
4. PySceneDetect produces raw detector metrics plus typed boundary-existence and
   boundary-localization proposals only;
5. the court claims nothing about ASR, speaker or character identity, visual
   semantics, narrative structure, or recall alignment.

The first court was `Held` from 2026-08-29 to 2026-09-01. Independent review
closed the documentation ledger leg but could not open package installation or
execution; that required a separately authorized implementation bead. The held
court was an intentional result, not an incomplete favorable inference.

### 6.1 Authorization record (2026-09-01, single-developer mode)

`Authorized(bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7, local draft realization)`.

Basis: the owner's instruction of 2026-09-01 to pick up the movie plan at this
court in single-developer mode (AGENTS.md SD5: the written record is the
requirement, and the owner switches modes). Nothing owner-reserved is touched:
no gated term is accepted and no weights are approved.

Exact permitted scope:

1. Media: project-authored F0 (`media/src/test/resources/f0/`, manifest
   `f0-v1.manifest.json`, SHA-256 `0cf05f22…`) and one separately admitted 20–40 s
   *Big Buck Bunny* excerpt cut by packet copy, never re-encoded.
2. FFmpeg: deterministic ingest only (`ffprobe` stream and packet listing;
   demux and packet copy for the excerpt; rawvideo or native decode to BGR24
   frames for the detector). The realized binary is the local Homebrew build
   recorded by path, `-version` line, and SHA-256 in the probe envelope; it is
   general-purpose and GPL-enabled, is not redistributed, and is **not** the
   nominated 9.0.1 LGPL-only recipe. That recipe stays `RecipeOnly`.
3. PySceneDetect: `scenedetect-headless` 0.7.1 installed locally from the
   hash-pinned wheel of §5, `ContentDetector` only, driven through the direct
   frame interface; raw metrics plus boundary-existence and boundary-localization
   proposals only.
4. Edit lists: the boundary court admits a picture stream's native clock as the
   edition axis under a recorded identity-edit-list assumption checked against
   the tool's reported start and discard flags only; the `elst` is not read and no
   track-composition receipt is issued (that is the E0 court's).
5. Authority: every runtime record produced under this authorization is
   `ObservationAuthority.Draft` (ADR 0007 C1, `CallerRuntimePacketRecord`). No
   E0 promotion path is opened. Ordinary CI replays recorded envelopes and never
   obtains a tool; the live courts run only against a binary already present.

Explicitly not authorized, and filed as follow-up work rather than skipped
silently: the reproducible OCI closure of §4 and §5 (two independent clean
builds, SBOM, signed build receipt, image digests), the corresponding-source
bundle, and any patent-posture or deployment-jurisdiction finding. Rejected
alternative: realizing that closure first. It buys nothing the type system can
use while every record stays `Draft`; it is the E0 bead's cost, not this one's.

| Local realization | Identity | Recorded in |
|---|---|---|
| `ffprobe` 7.1.1 (Homebrew `ffmpeg/7.1.1_3`) | binary SHA-256 `83f66b74c1f0fe3995f762adbaa90c1d32fbd8e1feceedc64fed4261e1a65ebb` | `media/src/test/resources/f0/f0-v1.ffprobe-envelope.json` |
| `ffmpeg` 7.1.1 (Homebrew `ffmpeg/7.1.1_3`; `--enable-gpl --enable-version3 --enable-libx264`) | binary SHA-256 `14c12cd5dcbc0d75c83085296b27142a4abc61a320a97a5408f7048fa7cbd630` | `media/src/test/resources/f0/f0-v1.frames-envelope.json` |
| `scenedetect-headless` 0.7.1 via `media/worker/uv.lock` (wheel SHA-256 `0594131c…`, the §5 pin) in a local venv on CPython 3.13.11 with numpy 2.5.2 and opencv-python-headless 5.0.0; worker script `media/worker/scenedetect_worker.py` | script SHA-256 `47424d093c5c2cc51a1764b53b97a1a1282c25561f77eb10e8ffc3d1e44e60f2` | `media/src/test/resources/f0/f0-v1.detector-envelope.json` |

Beads executed under this authorization: `bd-01M1FDNN3T4SKZ5ZJN1XGXYNY7`
(landing A: F0, `ffprobe` ingest, replay) and `bd-01M1FEN59CZCCR0SR3B6MZ1KYB`
(landing B: decode to identified frames, `ContentDetector` proposals) and
`bd-01M1FFZ7ETCP3WHNA5NHAESQ6Q` (F1: the *Big Buck Bunny* excerpt admitted with
archive and inner-file hashes, derivation script, CC BY 3.0 attribution, and
recorded probe, decode and detector run under `media/src/test/resources/f1/`).

### 6.3 Captioning lane authorization (2026-09-01, single-developer mode)

Owner instruction of 2026-09-01: "proceed with Qwen3 but we'll be flexible".
Scope, in the form of §6.1:

1. Components: `caption.qwen3vl.4b` first (local adapter court), then
   `caption.qwen3vl.8b` (calibration arm), both at the pinned revisions of
   `docs/design/vlm-captioning-admission-record.md`; a further model may join on
   the same terms as an additional comparison arm, never by substitution mid
   comparison.
2. Inputs: frames the `media` module has identified by PTS (F0, F1; Sherlock
   only under the §10.4 predeclared design), never a media file or URL.
3. Outputs: `Proposal` only, `Draft` authority, re-anchored to PTS by the adapter;
   no caption becomes a `TimedSegment` without a receipt-carrying adapter.
4. Realization gate: the weights-licence leaf of the record's §3 was an owner
   decision (`unknown is not admitted`). Decided 2026-09-02 ("this is research,
   let's not sweat the license for now"): the card's `apache-2.0` declaration is
   the licence of record for local research use; redistribution stays
   `HoldUnknown`. Weights may be fetched at the pinned revisions and verified
   against the repository's LFS digests. Nothing gated is touched; no remote
   service is used.

### 6.2 Local realizations: five-axis records

§1 says only a `Realized(digest)` record may make claims about executable
bytes. The §4 and §5 records describe the *nominated* recipes and keep
`RecipeOnly`; the bytes that actually ran are different components and get
their own rows. They are `CandidateOnly`: admitted for local Draft evidence on
the owner's workstation, not nominated for any distributed court.

| Key | Component | Selection | Rights/access | Role | Operational posture | Realization | Reason and limits |
|---|---|---|---|---|---|---|---|
| `decode.ffmpeg.local-homebrew-7.1.1` | Homebrew `ffmpeg/7.1.1_3` (`ffprobe` and `ffmpeg` front ends) | `CandidateOnly` | `PaperworkAdmitted` for local execution only; redistribution `Incompatible` with the §4 LGPL-only policy (built `--enable-gpl --enable-version3 --enable-libx264`, network, devices and hardware acceleration enabled: every entry of §4's forbidden-configuration row) | `DeterministicTransform` | `LocalRealized` | `Realized(83f66b74…)` for `ffprobe`, `Realized(14c12cd5…)` for `ffmpeg`; libraries below | Produces the recorded F0/F1 probes and decodes. Every output is `Draft`. Not the nominated 9.0.1 recipe; a probe replayed under 9.0.1 is a different derivation by construction (`ToolRealization` enters every receipt). Invocations pass `-protocol_whitelist file`; stderr is not receipted. |
| `boundary.pyscenedetect.content.local-venv` | `scenedetect-headless` 0.7.1 from the §5 wheel digest, in a local venv (CPython 3.13.11, numpy 2.5.2, opencv-python-headless 5.0.0), driven by `media/worker/scenedetect_worker.py` | `CandidateOnly` | `PaperworkAdmitted` (BSD-3-Clause wheel, hash-pinned in `media/worker/uv.lock`); transitive native notices `HoldUnknown` | `Measurement` + `Proposal` | `LocalRealized` | `Realized(47424d09…)` for the worker script; wheel `0594131c…` | Raw metrics and boundary-existence/localization proposals only, through `process_frame`. No container, no SBOM. `ToolRealization` for the worker covers the script bytes and runtime versions, not the wheel bytes. |
| `decode.ffmpeg` (the nominated component, realized) | FFmpeg 9.0.1 "Lei" built from `ffmpeg-9.0.1.tar.xz` (sha256 `cf38e0e28c7e5605942c4a77755349b0145804a397af37eb1fb4c77cb237f635`), signature verified in a temporary keyring against the release key fetched from `https://ffmpeg.org/ffmpeg-devel.asc` (primary fingerprint equal to the §4 record), by `media/tools/build_ffmpeg_9.0.1.sh` | `Nominated` | `PaperworkAdmitted` for local use; LGPL-only and free of every §4 forbidden flag, so eligible for the §4 redistribution policy once a corresponding-source bundle exists (`HoldUnknown` until then) | `DeterministicTransform` | `LocalRealized` | `Realized(13d17dfd…)` `ffprobe`, `Realized(85c119a5…)` `ffmpeg` | The recorded realization behind every committed envelope since 2026-09-01 evening. Static, so each digest covers its demuxing and decoding code; dynamic dependencies are Apple system frameworks only (libSystem, CoreFoundation, CoreVideo, CoreMedia). Two builds were made (the first without `--disable-videotoolbox --disable-audiotoolbox`); their bytes differ, so the recipe is not described as reproducible. No SBOM, no OCI image. |

Deviations of the realized 9.0.1 build from the §4 candidate configure line,
each forced by the F0/F1 manifests and recorded in the build script: the
`ffmpeg` program is enabled (decode is required, not probe only);
`--enable-static --disable-shared` replaces `--disable-static --enable-shared`
(single-binary identity; nothing is redistributed); `--enable-avfilter` and
`--enable-swscale` replace their disables (the declared colour conversion); and
the component selection is `protocol file; demuxer mov; decoders rawvideo,
h264; parser h264; encoder rawvideo; muxer rawvideo; filters scale, format,
null`. `--disable-gpl --disable-version3 --disable-nonfree
--disable-autodetect --disable-network --disable-avdevice --disable-hwaccels
--disable-asm` are unchanged.

Observed under the realized build on 2026-09-01: the F0 and F1 picture packet
tables and both decoded frame digests are byte-identical to the earlier
Homebrew 7.1.1 records; the F1 audio stream's last packet is reported with
duration 1024 (9.0.1) versus 1008 (7.1.1, trimmed under the edit list). That
difference is the tool's, lives in the tool identity, and is why the live
courts compare the picture table under any tool and the full table only under
the recorded binary. The Homebrew rows above remain as the record of the first
recordings; the committed envelopes now name the 9.0.1 binaries.

| `caption.qwen3vl.4b` (realized) | `Qwen/Qwen3-VL-4B-Instruct` @ `ebb281ec70b0`, two safetensors shards fetched 2026-09-02 and verified against the repository's LFS digests; `config.json` `edac7703…` | `Nominated` | `PaperworkAdmitted` for local research use (owner decision 2026-09-02); redistribution `HoldUnknown` | `Proposal` | `LocalRealized` | `Realized(30a01a05…, 046296a2…)` | Loaded only by the worker below after re-verifying the shards. Not committed (ignored `tmp/models/`). |
| `caption.worker.local-venv` | `media/worker-vlm/caption_worker.py` on `mlx-vlm` 0.6.17 / `mlx` 0.32.2 (MIT), CPython 3.13.11, hash-pinned in `media/worker-vlm/uv.lock` | `Nominated` | `PaperworkAdmitted` (MIT wheels); transitive native notices `HoldUnknown` | runtime for `Proposal` | `LocalRealized` | `Realized(86ef031a…)` for the script; wheel bytes unhashed | Offline; presents frames as an image sequence; reads back the applied preprocessing; two F0 runs byte-identical. No container, no SBOM. |

Shared libraries loaded by both FFmpeg front ends (`otool -L`, SHA-256 of the
resolved dylib), recorded because the front-end digest alone does not name
the code that demuxes and decodes:

| Library | SHA-256 |
|---|---|
| `libavformat.61.7.100.dylib` | `ff017d451e8f95392ccd403f168f1588e771327de1f11adbf17193f974fb9771` |
| `libavcodec.61.19.101.dylib` | `06e1666163b1fa9a0b512cde13c9252b7a0134482bf042ff26cb07e2e2c076cf` |
| `libavutil.59.39.100.dylib` | `855977ee5cad664532b676d9db64363bc336bb835e7a0dbcb42989cc398f0997` |
| `libswscale.8.3.100.dylib` | `af6229d7460fb99383e852922d478b2fb38679ab3614016f20978032fac1868b` |
| `libswresample.5.3.100.dylib` | `369cf4615251e4d137f17afa4f578906649e7d159c216cf6683ac5b44adc445f` |
| `libavfilter.10.4.100.dylib` | `38873055719b8fd5d909d6d72e5f4b280508bc450d55ddacd48f6d542fab1b51` |
| `libavdevice.61.3.100.dylib` | `c38bd1d9a029558718832def8c1b62382f489c976548c93ffca4589f0c29f4ab` |

The `ToolRealization` identity in the envelopes hashes the front-end binary
only. The library digests above are documentation of what those front ends
loaded on 2026-09-01; they are not bound into the receipts, and a receipt
therefore cannot distinguish two installs that share a front end and differ in
a library. That is a known gap of the local realization, not of the contract.

## 7. Completion gate

This ledger leg is complete only when:

1. every named candidate has an explicit selection state;
2. every nominated component has leaf-level code, learned-artifact,
   preprocessing, runtime, dependency, container, codec, privacy, retention,
   credential, and service-term records;
3. no umbrella licence is inherited by a child artifact;
4. every nominated role states what it cannot establish;
5. every unresolved selected leaf holds or removes its parent component;
6. the FFmpeg and PySceneDetect recipes are fully paperwork-admitted for the
   narrow F0/F1 court, or that court is explicitly held (satisfied on
   2026-08-29 by holding; superseded on 2026-09-01 by the §6.1 authorization,
   under which the recipes stay `RecipeOnly` and local realizations are
   recorded separately in §6.2);
7. owner-only actions link to the chief's bead and remain unperformed here;
8. no source media, participant text, weight, package, container, credential,
   gated term, or executable was obtained;
9. a second reviewer checks the primary evidence and all five status axes;
10. closure says only that the component-ledger leg is complete.

## 8. Review record

The frozen substantive ledger reviewed below is SHA-256
`fef499a6217de76d8bbbee3f60aee82bfcd067d2259de762336d74e03703e6f2`
(292 lines, whitespace check clean).

| Reviewer | Date | Verdict | Durable evidence |
|---|---|---|---|
| `codex-storymodel-new-engineer` | 2026-08-29 | `APPROVE` | Mote board post `post-01M18AHXKYSF3Q6VQ3MAKJWF50` |
| `codex-storymodel-collab` | 2026-08-29 | `APPROVE` | Mote board post `post-01M18AWA0JHW61J0K0T4XMSCQX` |

The reviews made no changes to this ledger or to tracked source, build, or test
artifacts. They did publish Mote board posts, which produced repository-local
coordination records and are the durable review evidence. They acquired no
package or media, used no account or gated term, and performed no runtime
execution. They approve only the substantive documentation ledger identified
above and close no source, participant, package, dependency, container, codec,
model, weight, service-term, runtime, P1, B0, or C1 gate.

This record closes only the independent-review condition for the
component-ledger documentation leg. The first court remained `Held` at that
review; §6.1 records its later authorization, which those reviewers did not
see and do not endorse. The final
artifact hash and candidate-level reviews are recorded outside this file to
avoid making the artifact identify a hash that changes when the record is
written.
