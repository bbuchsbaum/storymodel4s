# docs-site/examples

Programs the documentation shows, and the exact output they produced.

These are **documentation examples, not library files**. Nothing in `storymodel4s`
depends on them and they are not published as API.

Each program here must import only `storymodel4s` public API — no test sources, no
copied internal helpers, no demonstration facade. That constraint is the point: it
is what proves the library can be driven by an ordinary consumer, and it was
established by measurement rather than assumed. See
`docs-site/src/content/docs/method/align-and-interpret.mdx`.

Pages render these files by READING them at build time and hashing them there, so a
snippet on the page cannot drift from the file it claims to be, and the SHA shown to
a reader is derived rather than typed in.

| File | Produced by | Source SHA-256 |
| --- | --- | --- |
| `PublicAlignAndInterpret.scala` | codex-storymodel-collab, against exported main `daefa36` | `a32a5474…` |
| `align-and-interpret.output.txt` | that program, same run | captured verbatim |
| `ModelAStory.scala` | codex-storymodel4s-scout, against `1cb64bd` | `fb72cb6e…` |
| `model-a-story.output.txt` | that program, same run | `c7e474e4…` |
| `InspectSource.scala` | codex-storymodel4s-scout, against `1cb64bd` | `b33cdecb…` |
| `inspect-source.output.txt` | that program, same run | `96976428…` |
| `RepresentRecall.scala` | codex-storymodel4s-scout, against `1cb64bd` | `2f13608e…` |
| `represent-recall.output.txt` | that program, same run | `ec2fbcb…` |
| `SummarizeRecall.scala` | codex-storymodel4s-scout, against `1cb64bd` | `c3845957…` |
| `summarize-recall.output.txt` | that program, same run | `5665e473…` |
| `ScoreInterview.scala` | codex-storymodel4s-scout, against `1cb64bd` | `334f2f47…` |
| `score-interview.output.txt` | that program, same run | `4399b050…` |
| `SaveModel.scala` | codex-storymodel4s-scout, against `1cb64bd` | `7526d459…` |
| `save-model.output.txt` | that program, same run | `cc1f48b0…` |
| `UseEmbeddings.scala` | codex-storymodel4s-scout, against `1cb64bd` | `e0c3d9c1…` |
| `use-embeddings.output.txt` | that program, same run | `a0609f21…` |

The alignment example's lexical baseline recovers very little of the paraphrase.
Keep that output with the program: it establishes API behavior without making an
accuracy claim.
