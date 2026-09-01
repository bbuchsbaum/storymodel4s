# docs-site/examples

Programs the documentation shows, and the exact output they produced.

These are **documentation examples, not library files**. Nothing in `storymodel4s`
depends on them and they are not published as API.

Each program here imports only public `storymodel4s` APIs. The manifest names the
smallest sbt project that supplies its classpath, its main class, and its expected
output. `npm run verify:examples` rejects an unmanifested Scala file, compiles every
entry, runs it, and compares stdout byte-for-byte with the named output file.

Pages read these files at build time, so displayed code and output use the same bytes
that the replay gate verifies.

The manifest currently covers thirteen workflows: source inspection, a reviewed story
model, PENMAN conversion, acquisition resolution, document composition, recall
construction, feature windows, portable embeddings, recall alignment and summary,
interview scoring, canonical JSON, and JVM structural distance.

The alignment example's lexical baseline recovers very little of the paraphrase. Its
output establishes API behavior and carries no accuracy claim. Its results are also
conditional on the heuristic segmentation produced by `RecallSegmenter`.
