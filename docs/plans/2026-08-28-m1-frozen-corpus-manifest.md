# M1 W4(9) — Frozen corpus manifest (candidate list, rev 2)

**Bead:** bd-01M14YJ8FG15S0R1ART7DTM1XZ. **Status:** candidate list for **set 1**, under
the adjudication protocol rev 1 (decisions of the chief, 2026-08-28,
post-01M15AM6WTR8N4A38BN3WHB4WS — see the protocol's §10). No text is reproduced here
and none is fetched yet. Texts enter the repository only as data files under
`fixtures/src/main/resources/frozen/<set-id>/stories/<story-id>/text.txt`, fetched from
the provenance below, checksummed in the manifest, **never agent-generated or
agent-regenerated** (per AGENTS.md and the WOG precedent), and **only after the story's
PD-basis line in the sign-off checklist below is signed**. *The War of the Ghosts* stays
a regression fixture and is excluded from every partition.

**Set 1 parameters (decided).** Recall condition: immediate free recall only. Panel
sizes: 3 (development) / 3 (calibration) / 5 (untouched test) recalls per story.
Ceiling margin: 0.02. Assistance: deterministic atlas and segmenter proposals only; no
generative provider. Annotation and adjudication by humans; agents audit only.
**Planned expansions (set 2, recorded in `plannedExpansions`):** untouched-test panel
→ 8 per story; a delayed-recall (≥ 24 h) panel; non-tested-LLM candidate proposals
with a written independence argument.

Families: **F1 folktale/oral** (ethnographic transcriptions, US-government
publications), **F2 fairy tale, literary** (pre-1929 English editions/translations),
**F3 short fiction, literary** (pre-1929 English originals/translations). Each family
appears in every partition so leave-family-out selection is possible.

Length is approximate (words of story text only). Phenomena use the design record §32.3
vocabulary. **"verify before freeze"** marks any provenance or public-domain status
that must be confirmed against the actual edition before the text is fetched.

## F1 — folktale / oral (US government publications; public domain)

| id | Title | Source edition, year | PD basis | Provenance (fetch as data) | ~words | Phenomena | Partition |
|---|---|---|---|---|---|---|---|
| f1-kathlamet-cultee-ghosts | Cultee's Grandfather Visits the Ghosts (told 1891) | Boas, *Kathlamet Texts*, BAE Bulletin 26, 1901 | US Gov. Printing Office publication | archive.org `kathlamettexts00boas` (djvu.txt; English free translation, p. ~247) | 600–900 (verify) | unfamiliar folktale; belief/report scoping; return-from-other-world temporal ellipsis | development |
| f1-kathlamet-2 | one further Kathlamet myth (candidate: a Coyote or Salmon myth from the same volume — title **verify before freeze**) | Boas, *Kathlamet Texts*, 1901 | US GPO | archive.org `kathlamettexts00boas` | 800–1500 | repeated descriptions; parallel episodes; unfamiliar folktale | calibration |
| f1-chinook-1 | one Chinook myth with a reported-speech core (title **verify before freeze**) | Boas, *Chinook Texts*, BAE Bulletin 20, 1894 | US GPO | archive.org `chinooktexts00boas` | 700–1200 | nested narration; unreliable report; similar characters | untouched test |
| f1-tlingit-1 | one Tlingit narrative (title **verify before freeze**; e.g. a bear-wife or Raven tale) | Swanton, *Tlingit Myths and Texts*, BAE Bulletin 39, 1909 | US GPO | archive.org `tlingitmythstext00swan` | 800–1500 | transformation states; goal structure; temporal ellipsis | untouched test |
| f1-pawnee-1 | one Skidi Pawnee tradition (title **verify before freeze**) | G. A. Dorsey, *Traditions of the Skidi Pawnee*, Memoirs of the American Folk-Lore Society VIII, 1904 | published 1904 (pre-1929) | archive.org (search "Traditions of the Skidi Pawnee 1904"; identifier **verify**) | 600–1200 | first-person embedded telling; causal chains | development |

Notes: BAE bulletins are works of the United States Government and public domain
worldwide in practice; the free English translations are the story texts (interlinear
Kathlamet/Chinook glosses are excluded from `text.txt`). OCR must be corrected against
the scanned page and the correction recorded in the story's `provenance.json` (as was
done for WOG).

## F2 — fairy tale, literary (pre-1929 English editions)

| id | Title | Source edition, year | PD basis | Provenance | ~words | Phenomena | Partition |
|---|---|---|---|---|---|---|---|
| f2-grimm-fisherman | The Fisherman and His Wife | Grimm, *Household Tales*, tr. Margaret Hunt, 1884 | published 1884 | Project Gutenberg #5314 (Household Tales, Hunt) — tale number **verify** | 2,000–2,500 | repeated descriptions (escalating refrains); explicit causal structure; low-action stretches | development |
| f2-grimm-robber-bridegroom | The Robber Bridegroom | Grimm, tr. Hunt, 1884 | published 1884 | PG #5314 | 1,200–1,600 | nested narration (the bride retells events as a dream); reported vs realized; unreliable framing | untouched test |
| f2-jacobs-mr-fox | Mr. Fox | Joseph Jacobs, *English Fairy Tales*, 1890 | published 1890 | Project Gutenberg #7439 | 900–1,200 | nested narration ("it is not so, nor it was not so"); repeated motif; retrospective telling contradicted by evidence | calibration |
| f2-jacobs-molly-whuppie | Molly Whuppie | Jacobs, *English Fairy Tales*, 1890 | published 1890 | PG #7439 | 1,000–1,400 | repeated episodes with variation; similar characters (three sisters); goal/attempt/success structure | development |
| f2-perrault-bluebeard | Blue Beard | Perrault, in Lang (ed.), *The Blue Fairy Book*, 1889 | published 1889 | Project Gutenberg #503 | 1,800–2,200 | suspense with unresolved cause (the wives); dialogue-heavy; explicit summary ending (moral) | calibration |
| f2-aesop-set | Aesop micro-set: 4 fables (The Fox and the Grapes; The Dog and the Shadow; The Lion and the Mouse; The Boy Who Cried Wolf — titles per edition **verify**) | Aesop, tr. George Fyler Townsend, 1867 | published 1867 | Project Gutenberg #21 | 4 × 80–200 | explicit summary (morals); minimal event graphs — diagnostic-first, promote to a partition only if the bench needs very short stories | diagnostic root (`fixtures/src/main/resources/diagnostic/aesop/`), not a partition |

## F3 — short fiction, literary (pre-1929 originals/translations)

| id | Title | Source edition, year | PD basis | Provenance | ~words | Phenomena | Partition |
|---|---|---|---|---|---|---|---|
| f3-chopin-story-of-an-hour | The Story of an Hour | Kate Chopin, 1894 (first pub. *Vogue*) | published 1894 | Project Gutenberg #160 (*The Awakening and Selected Short Stories*) — confirm the story is in that edition (**verify**) | ~1,000 | low-action psychological; belief vs fact (the reported death); explicit reversal; unresolved/misattributed cause of death | development |
| f3-ohenry-gift-of-the-magi | The Gift of the Magi | O. Henry, *The Four Million*, 1906 (story 1905) | published 1905/1906 | Project Gutenberg #7256 (*The Four Million*) | ~2,100 | parallel plots converging; explicit summary (narrator's coda); figurative language | calibration |
| f3-saki-open-window | The Open Window | Saki (H. H. Munro), *Beasts and Super-Beasts*, 1914 | published 1914 | Project Gutenberg #1477 (*Beasts and Super-Beasts*) | ~1,200 | unreliable report (Vera's invented tragedy) — reported content must not become narrated-world fact; nested narration; explicit summary line | untouched test |
| f3-bierce-owl-creek | An Occurrence at Owl Creek Bridge | Ambrose Bierce, *Tales of Soldiers and Civilians*, 1891 | published 1890/1891 | Project Gutenberg #4366 (*In the Midst of Life*) — edition **verify** | ~3,700 | flashback (part II); imagined vs actual (part III is imagination); temporal ellipsis; the three-part discourse order ≠ story-world order | untouched test |
| f3-chekhov-the-bet | The Bet | Chekhov, English translation — candidate: S. S. Koteliansky & J. M. Murry, *The Bet and Other Stories*, 1915 | translation published 1915 (**verify translator/edition before freeze**; Garnett's Chekhov volumes 1916–1922 are also pre-1929) | Project Gutenberg (search "The Bet and Other Stories" Chekhov) — identifier **verify** | ~2,800 | fifteen-year temporal ellipsis; nested letter (embedded document); reversal of goal; retrospective reasoning | calibration |
| f3-london-to-build-a-fire | To Build a Fire (1908 version) | Jack London, *Lost Face*, 1910 | published 1908/1910 | Project Gutenberg #2429 (*Lost Face*) | ~7,200 | long, low-dialogue, slowly rising tension (sensory/affective trajectory); causal chain; gradual state change — the length target for LongStory-style scaling | development (long-story slot) |

## Partition summary (WOG excluded)

| Partition | Stories |
|---|---|
| development | f1-kathlamet-cultee-ghosts, f1-pawnee-1, f2-grimm-fisherman, f2-jacobs-molly-whuppie, f3-chopin-story-of-an-hour, f3-london-to-build-a-fire |
| calibration | f1-kathlamet-2, f2-jacobs-mr-fox, f2-perrault-bluebeard, f3-ohenry-gift-of-the-magi, f3-chekhov-the-bet |
| untouched test | f1-chinook-1, f1-tlingit-1, f2-grimm-robber-bridegroom, f3-saki-open-window, f3-bierce-owl-creek |
| diagnostic only (separate root `fixtures/src/main/resources/diagnostic/`, never inside `frozen/<set-id>/`) | f2-aesop-set, all metamorphic material, agent-annotated material, WOG |

Sixteen stories across three families (≥ 8 stories / ≥ 3 families satisfied with margin);
every family appears in every partition.

Recall panel for set 1 (immediate free recall only): 6 development stories × 3 +
5 calibration stories × 3 + 5 untouched-test stories × 5 = **58 recalls**; set 2 adds
15 test recalls (5 × 3) for the test → 8 expansion, plus the delayed-recall panel.

## Training-data contamination (added rev 2; raised by the Araby proposal,
`general/post-01M15PYE2TDDBB2GN0T2911B1Q`, and it indicts this manifest as first written)

A frozen set exists to **select defaults** (ADR 0001 §D7). Every tested LLM channel has
plausibly memorized famous stories *and their published summaries*; the free baselines
(hashed n-gram, TF-IDF) and the structural channel have memorized nothing. Scoring both
on a canonical text is therefore not a fair comparison — it can bias default selection
toward the provider for a reason that has nothing to do with alignment quality. *The War
of the Ghosts* was obscure, and that obscurity was doing quiet work we never wrote down.

As first written this manifest ignored the problem and then selected some of the
most-summarized short stories in English (`f3-ohenry-gift-of-the-magi`,
`f3-saki-open-window`, `f3-bierce-owl-creek`, `f3-chopin-story-of-an-hour`,
`f3-london-to-build-a-fire`). Rules, in force from rev 2:

1. **Fame is a selection criterion.** Each story carries a `contaminationRisk` of
   `low | medium | high`, judged by whether the text and student-facing summaries of it
   are widely reproduced online. It is recorded in the manifest and in the freeze
   receipt.
2. **The untouched-test partition decides defaults, so it takes the strictest rule**:
   `high` is disqualifying there, `medium` needs a written justification. Development and
   calibration partitions may hold `medium`; `high` anywhere requires the report to name
   it.
3. **Contamination is reported, never assumed away.** A calibrated report states each
   story's risk. A default selected on a set containing any `high` story is not
   publishable as calibrated.
4. **Fame×channel leakage control.** Because per-story macro means already exist, the
   bench can test the leakage directly: within a channel, compare scores on `low` vs
   `high` stories *relative to the free baselines on the same stories*. A provider that
   gains on famous stories where the baselines do not is showing prior knowledge, not
   alignment skill. This control runs before any default is selected.
5. Published summaries, study guides, and student précis are **never** recall data.
   Recall enters only as collected recalls under the protocol, or as authored paraphrases
   explicitly labelled as such in an acceptance fixture.

Consequence for the current list: the five stories named above are re-scored under rule
1 before set 1 is frozen, and the `untouched test` slots (`f3-saki-open-window`,
`f3-bierce-owl-creek`) are the ones that must move or be justified. This is a selection
change, not a protocol change, so it does not disturb `protocolChecksum`; if the chief
would rather make it a protocol law, that costs a protocol revision plus the one-line
`ProtocolDocument.pinned` update in `embed-bench`, and both should land together.

## Fetch and provenance rules

1. Fetch from the listed edition only; record `provenance.json` per story with URL or
   catalog id, edition, page range, retrieval date, raw checksum, and every OCR/typo
   correction as a `(before, after, page)` triple.
2. Strip front matter, notes, glosses, and editorial apparatus; keep paragraphing.
3. Never paraphrase, modernize, or "clean up" wording — the text is the coordinate
   system; a differing edition is a different story-id.
4. If a PD basis marked **verify** cannot be confirmed, drop the item (do not
   substitute a modern translation).
5. **Sign-off before fetch.** A story's `text.txt` is committed only after its line in
   the sign-off checklist below is signed; the signed line is copied into the set's
   `receipts/pd-signoff.json` (story-id, PD basis, edition checked, signer, date). No
   agent types, paraphrases, or regenerates story text — data files fetched from the
   listed provenance only.
6. **Freeze receipt.** The manifest names the adjudication protocol's content checksum
   (`protocolChecksum`, protocol rev 1) so that any later edit of the protocol is
   detectable by the bench; it also records `panelSizes`, `recallConditions`,
   `ceilingMargin`, `assistance`, `plannedExpansions`, per-story `contaminationRisk`,
   and pseudonymized `staffing` (protocol §6).

## Sign-off checklist (verify before freeze)

Each line is signed by a human (actor id, ISO date) after checking the actual edition;
an agent may prepare the evidence (catalog page, scan page number) but may not sign.
**No story text is fetched into the repository until its PD-basis line is signed.** An
unsigned line at freeze time means the story is dropped from the set (rule 4 above).

PD basis / edition (one line per story):

- [ ] f1-kathlamet-cultee-ghosts — Boas 1901, BAE Bull. 26, US GPO; page range confirmed. Signed: ________ (date ________)
- [ ] f1-kathlamet-2 — tale title chosen; Boas 1901; page range confirmed. Signed: ________ (date ________)
- [ ] f1-chinook-1 — tale title chosen; Boas 1894, BAE Bull. 20; page range confirmed. Signed: ________ (date ________)
- [ ] f1-tlingit-1 — tale title chosen; Swanton 1909, BAE Bull. 39; page range confirmed. Signed: ________ (date ________)
- [ ] f1-pawnee-1 — tale title chosen; Dorsey 1904 archive.org identifier confirmed; published 1904. Signed: ________ (date ________)
- [ ] f2-grimm-fisherman — Hunt 1884 in PG #5314 under that title confirmed. Signed: ________ (date ________)
- [ ] f2-grimm-robber-bridegroom — Hunt 1884 in PG #5314 under that title confirmed. Signed: ________ (date ________)
- [ ] f2-jacobs-mr-fox — Jacobs 1890, PG #7439 confirmed. Signed: ________ (date ________)
- [ ] f2-jacobs-molly-whuppie — Jacobs 1890, PG #7439 confirmed. Signed: ________ (date ________)
- [ ] f2-perrault-bluebeard — Lang 1889, PG #503 confirmed. Signed: ________ (date ________)
- [ ] f2-aesop-set (diagnostic root only) — Townsend 1867, PG #21; four titles confirmed per edition. Signed: ________ (date ________)
- [ ] f3-chopin-story-of-an-hour — story present in PG #160 confirmed; published 1894. Signed: ________ (date ________)
- [ ] f3-ohenry-gift-of-the-magi — PG #7256 confirmed; published 1905/1906. Signed: ________ (date ________)
- [ ] f3-saki-open-window — PG #1477 confirmed; published 1914. Signed: ________ (date ________)
- [ ] f3-bierce-owl-creek — PG #4366 is *In the Midst of Life* containing *Owl Creek*, confirmed. Signed: ________ (date ________)
- [ ] f3-chekhov-the-bet — pre-1929 translation and its Gutenberg identifier chosen and confirmed. Signed: ________ (date ________)
- [ ] f3-london-to-build-a-fire — PG #2429 (*Lost Face*), 1908 version confirmed. Signed: ________ (date ________)

Set-level items:

- [ ] Partition map final (every family in every partition; WOG in none). Signed: ________ (date ________)
- [ ] Panel sizes 3/3/5 and immediate-recall-only condition recorded in the manifest. Signed: ________ (date ________)
- [ ] `plannedExpansions` (test → 8; delayed recall; non-tested-LLM proposals with independence argument) recorded. Signed: ________ (date ________)
- [ ] `assistance` lists only deterministic proposers (atlas, segmenter) with build fingerprints; no generative provider. Signed: ________ (date ________)
- [ ] `staffing` has no agent in a non-auditor role (Law I4). Signed: ________ (date ________)
- [ ] `ceilingMargin = 0.02` recorded. Signed: ________ (date ________)
- [ ] No diagnostic file under `frozen/<set-id>/`; diagnostic material under `fixtures/src/main/resources/diagnostic/`. Signed: ________ (date ________)
- [ ] `protocolChecksum` equals the protocol document's content checksum at the freeze commit. Signed (auditor): ________ (date ________)
- [ ] Independence audit (I1–I4 + leakage checklist) written to `receipts/audit.json`. Signed (auditor): ________ (date ________)
