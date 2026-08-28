# M1 W4(9) — Frozen corpus manifest (candidate list, rev 0)

**Bead:** bd-01M14YJ8FG15S0R1ART7DTM1XZ. **Status:** candidate list for critique; no
text is reproduced here and none is fetched yet. Texts enter the repository only as
data files under `fixtures/src/main/resources/frozen/<set-id>/stories/<story-id>/text.txt`,
fetched from the provenance below, checksummed in the manifest, never agent-generated
(per AGENTS.md and the WOG precedent). *The War of the Ghosts* stays a regression
fixture and is excluded from every partition.

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
| f2-aesop-set | Aesop micro-set: 4 fables (The Fox and the Grapes; The Dog and the Shadow; The Lion and the Mouse; The Boy Who Cried Wolf — titles per edition **verify**) | Aesop, tr. George Fyler Townsend, 1867 | published 1867 | Project Gutenberg #21 | 4 × 80–200 | explicit summary (morals); minimal event graphs — diagnostic-first, promote to a partition only if the bench needs very short stories | diagnostic (not a partition) |

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
| diagnostic only | f2-aesop-set, all metamorphic material, WOG |

Sixteen stories across three families (≥ 8 stories / ≥ 3 families satisfied with margin);
every family appears in every partition.

## Fetch and provenance rules

1. Fetch from the listed edition only; record `provenance.json` per story with URL or
   catalog id, edition, page range, retrieval date, raw checksum, and every OCR/typo
   correction as a `(before, after, page)` triple.
2. Strip front matter, notes, glosses, and editorial apparatus; keep paragraphing.
3. Never paraphrase, modernize, or "clean up" wording — the text is the coordinate
   system; a differing edition is a different story-id.
4. If a PD basis marked **verify** cannot be confirmed, drop the item (do not
   substitute a modern translation).

## PD-status uncertainties to resolve before freeze

- Exact titles/pages of the second Kathlamet, the Chinook, the Tlingit, and the Pawnee
  tales (the volumes are PD; the specific tale choice is open).
- Archive.org identifier for Dorsey 1904.
- Gutenberg edition containing Chopin's *The Story of an Hour* (PG #160 is believed to
  include it; confirm).
- Chekhov *The Bet*: which pre-1929 translation to use and its Gutenberg identifier.
- Bierce: confirm PG #4366 is *In the Midst of Life* containing *Owl Creek*.
- Hunt 1884 Grimm: confirm PG #5314 contains both selected tales under those titles.
