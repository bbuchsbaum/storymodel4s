# Recover an interrupted offline import

Every offline `import-*` command accepts `--recover`. The corresponding Python import
functions accept `recover=True`. Supply the same request, exported reply and recording root:

```sh
storymodel import-prepared-construction prepared.json reply.json recording --recover
storymodel import-details details-request.json details-reply.json details-recording --recover
storymodel import-mapping mapping-request.json mapping-reply.json mapping-recording --recover
storymodel import-fidelity fidelity-request.json fidelity-reply.json fidelity-recording --recover
```

The same option is available for source, rich, playback, extraction, semantic and time-study
imports. Without it, an existing destination still refuses. Recovery is local and never calls
a provider. Run the usual replay command after recovery; analysis plans and result schemas
do not change.

Before any profile or reply write, a new import saves `import-intent.json`. It retains the
exact recording profile, prompts, supplied replies and complete supplied manifest, including
origin. Recovery checks the request and export permission as usual, then requires that intent
and all existing artifacts to agree. It fills only missing writes, preserving completed reply
and import-manifest bytes. A different reply, origin, input, prompt or profile refuses; it is
not a new attempt. Completed assessments remain the replay of their original recorded input.

Legacy recordings remain replayable. An interrupted directory without a durable intent cannot
be adopted automatically: its original supplied input is unproven. Keep it for diagnosis and
import the original recording into a new directory. An unknown remote dispatch still refuses
automatic retry; offline import recovery does not override that rule.

Recording operations use a process-held POSIX advisory lock on a stable file. A terminated
process releases the lock automatically. The lock file remains present by design; its presence
does not identify a live writer. Do not remove it while an operation may be running. These
locks do not coordinate with the older PID-file protocol: finish old-version recording
operations before upgrading. The
operations currently require macOS/Linux POSIX locking; Windows recording support is not
qualified. Importing the Python library itself does not require POSIX locking.

Recovery checks consistency and input binding, not provenance authentication against a caller
who can rewrite every artifact. Keep independent request/import hashes when that boundary
matters. Atomic writes and process-interruption checks are not a power-loss durability claim.
