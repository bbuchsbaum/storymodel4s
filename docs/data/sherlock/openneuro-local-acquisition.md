# Local OpenNeuro acquisition

The owner requested the raw/BIDS release on the external Passport. On 22 September
2026 the drive contained no ds001132 checkout. The pinned release was downloaded to:

```text
/Volumes/My Passport for Mac/data/openneuro/ds001132-1.0.0
```

Source: [OpenNeuroDatasets/ds001132](https://github.com/OpenNeuroDatasets/ds001132),
tag `1.0.0`, commit `fc91edbbe1b8117f96b40bb4d1f5df43aa1afdce`.
DataLad cloned directly onto the Passport; `git annex get --json -J4 -- .` fetched
all 65 payloads (6,606,773,568 bytes). The annex objects, download log and local
verification receipt remain on that drive. No image payload was staged on the
main disk. The dataset working tree was clean after retrieval.

An independent streaming pass verified each pinned MD5E digest and size, confirmed
every resolved annex object remained within the external checkout, and computed
SHA-256 hashes. See [verification.json](openneuro-acquisition-20260922/verification.json)
and the [exact verification script](openneuro-acquisition-20260922/verify-download.py).
Receipt SHA-256: `f562e860e23c67f182ad466372d10a2ceb7673e31cbe5bf64aa702bc590f9f35`.
This establishes retrieved bytes, not scientific alignment.

Development participant `sub-03` has the following header and task-event observations:

| Task | Raw volumes | Header/sidecar TR | Event onset | Event duration |
|---|---:|---:|---:|---:|
| freerecall | 716 | 1.5 s | 9 s | 1060.5 s |
| sherlockPart1 | 972 | 1.5 s | 0 s | 1419 s |
| sherlockPart2 | 1057 | 1.5 s | 0 s | 1545 s |

The [observation receipt](openneuro-acquisition-20260922/development-headers.json)
binds image, 348-byte NIfTI header, task sidecar and events bytes. The
[reader](openneuro-acquisition-20260922/read-development-headers.py) reads headers
and task rows only. Header offsets follow the
[official NIfTI-1 definition](https://github.com/NIFTI-Imaging/nifti_clib/blob/master/niftilib/nifti1.h).

Event-file zero is not yet admitted as full-video zero. Raw array lengths and the
release's crop instructions need a checked transformation receipt. The recall CSV
alias map establishes byte aliases; it does not by itself establish a BIDS
recording identity. Neither a guessed cartoon subtraction nor a reused Princeton
recall offset is authorized by these observations. No voxel analysis or held-out
scoring was performed.

Keep this BIDS root separate from the existing recall/media input root. The next
dataset slice must bind recording identities, full-media origins and actual array
history before producing an admitted scanner-to-recall/media transform.
