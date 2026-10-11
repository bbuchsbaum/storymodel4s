# Actual worker execution on synthetic F0

Producer: `13bdc9aad68bba8493577f79f212fa07f3b57f44`, clean standalone clone.
This receipt supersedes the earlier missing-runtime observation for this fixture run.

Both Python environments were restored from their frozen lockfiles. The 12 model files for
Qwen3-VL-4B-Instruct revision `ebb281ec70b0` were explicitly downloaded during setup and verified
against the existing F0 request's SHA-256 pins. The application itself still performs no download;
the caption worker forces offline operation. Dependency inventories and restoration receipts
are retained. Local model weights are not included in this archive.

The public `VideoSourceCli build` command ran real ffmpeg, ffprobe, PySceneDetect and MLX on the
existing project-authored arithmetic F0 video. It exited 0 and published three caption proposals
over nine selected ordinals: `[0,6,13,14,21,28,29,36,44]`, from 45 presented frames. All 15 package
file pins were independently checked. The complete generated package is retained here, including
admitted synthetic media bytes, selected frames, worker outcomes and execution receipts.

The public replay command exited 0 and emitted byte-identical `source.json`. A subsequent public
mapping exchange using the fixed admitted synthetic external recall `Anna arrived.` completed
one reference/reconstruction pair, accepted by the independent Python exchange reader. This
checks the video-only source path with actual worker output, not meaningful recall performance.

The generated captions remain Draft proposals. Neither caption accuracy nor full visual coverage
is claimed. This run does not supply an annotation-to-film correspondence or qualify Sherlock
A+V, scanner transforms, raw-BIDS export or the complete parent bead.

`manifest-evidence.json` binds the retained outputs, commands, logs, runtime inventories and
readback receipts. The model restoration and execution were local; no hosted CI or push occurred.
