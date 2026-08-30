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

The output is unflattering — a lexical baseline recovers almost nothing on this
paraphrase. Preserve it. It is the explanation, not noise, and a page that quietly
swapped in a better-looking run would be the exact failure this project exists to
make visible.
