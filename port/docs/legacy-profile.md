# Legacy StudyBundle profile

`storymodel.legacy.load_legacy(path, *, expected_sha256)` admits only
`storymodel4s.agentmapping.bundle/v2` categorical bundles. It supports the existing
`strict-chunked/v1` profile and `strict-chunked-time/v1` profile when its exact-fraction clip
clock is present. The caller supplies a SHA-256 pin for the exact source bytes; the loader hashes
bytes before UTF-8/JSON decoding and refuses a mismatch. A hash supplied by the same untrusted
source is integrity comparison, not authentication.

The adapter reuses the semantics of `tools/agentmapping/study_bundle.py` in-package, rather than
importing repository tools at runtime. It recomputes categorical judgements, timed decisions,
record outcomes, answer digests, processing status, and (where present) canonical UTF-16 spans.
`legacy_rows` returns original unit rows as written, and `legacy_summary` is an inspection-only
view: neither flattens or discards `extras` or other extension fields.

This is deliberately not complete Scala `StudyBundle.decode` reconstruction. In particular it
cannot bind a bundle to separately supplied source and recall objects, re-decode Scala mapping
records, verify raw transcript bytes, or establish a new generated bundle's provenance. Unsupported
schemas, non-categorical record decisions, malformed values, and unknown segmentation recipes are
typed `LegacyBundleError` refusals. Keep original bytes plus their independently pinned hash when
retaining an artifact.
