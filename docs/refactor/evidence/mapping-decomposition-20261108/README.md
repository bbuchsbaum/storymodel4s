# Scene-error decomposition: result (2026-11-08)

Registered in [protocol.md](protocol.md) at `052da119a` before the read. On
`claude/consolidate-20261108` the same commit appears as `c9b1c49e0`, with an identical protocol
file. Full output:
[result.json](result.json). Population: 11 development participants, of whom 10 have gold, and
1,499 gold-eligible units from the frozen 2026-09-23 annotations-only run (A).

The final scene-exact figure, 60.95% participant-averaged, reproduces the registered 2026-09-23
value exactly.

| Participant-average share | % |
|---|---:|
| Retrieval miss: gold scene never nominated | 25.3 |
| Ranking miss: nominated, wrong locally and finally | 16.4 |
| Decode rescue: wrong locally, right after decoding | 24.6 |
| Decode loss: right locally, wrong after decoding | 5.3 |
| Hit: right locally and finally | 28.3 |
| Local top-1 scene-exact | 33.7 |
| Final scene-exact | 61.0 |
| Non-source state outweighs every source locally | 85.7 |

Reading, diagnostic only:

- Local content evidence picks the right scene for about one unit in three.
- Sequence decoding supplies roughly 27 points, so the headline accuracy is mostly an ordering
  result.
- A quarter of units never have the gold scene among their 16 nominations.
- A non-source state dominates the local evidence for 86% of units.

The u92 failure is therefore typical of the local channel, not an outlier. The next engineering
target is the local channel, both retrieval and scoring, measured by local metrics (candidate
recall and local top-1 or top-k), before any decoder change.
