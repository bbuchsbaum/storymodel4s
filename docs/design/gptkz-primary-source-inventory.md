# OSF `gptkz` primary-source inventory

Status: primary inspection, access-limited; scientific source use authorized  
Checked: 2026-08-29  
Tracker: `bd-01M17JFDHKHPD6N6JWA7NCMM4A`

## Bottom line

The deposit cannot currently be counted as a usable transcript corpus.

1. The published OSF GUID exists, but anonymous access currently resolves to an authenticated
   node. The node API returns HTTP 401 and the rendered project redirects to OSF sign-in. No
   public file inventory or project licence is therefore available.
2. The paper establishes that participant reproductions were written by hand, typed for the next
   chain member, and scored. It does **not** establish whether the inaccessible deposit contains
   those typed reproductions or only derived word, detail, and theme scores.
3. The paper's Appendix 1 reproduces the full 328-word Bartlett stimulus, so its wording is
   recoverable even though the OSF project is inaccessible. The checked-in fixture uses the longer
   Boas/Cultee text and explicitly records that Bartlett's version differs. The existing narrative
   ontology may be a useful starting hypothesis, but its source identity, evidence spans,
   checksum, and event inventory do not describe the experimental stimulus exactly.
4. The owner has authorized scientific use of the Bartlett stimulus under fair use. This resolves
   the source-use decision, not the OSF authentication or dataset-licence questions. The selected
   low-exposure posture is to keep the stimulus words outside git and register provenance,
   checksum, and a local-only loader unless the owner later directs public vendoring.

No participant reproduction was opened, downloaded, copied, or added to this repository during
this inspection.

## Primary records

| Record | Checked object | Result |
|---|---|---|
| [Ost et al. (2022)](https://doi.org/10.1080/09658211.2022.2059514) | Peer-reviewed study, method, Appendix 1 stimuli, Appendix 2 instructions, data-availability statement | Available; article is CC BY 4.0. |
| [University of Portsmouth article record](https://researchportal.port.ac.uk/en/publications/the-serial-reproduction-of-an-urban-myth-revisiting-bartletts-sch/) | Author manuscript and licence record | Available; linked PDF inspected locally. |
| [OSF `gptkz`](https://osf.io/gptkz/) | Project landing page | Redirected an anonymous browser to OSF sign-in on 2026-08-29. |
| [OSF node API](https://api.osf.io/v2/nodes/gptkz/) | Node metadata and public-access status | HTTP 401 with `Authentication credentials were not provided` on 2026-08-29. |
| [OSF GUID API](https://api.osf.io/v2/guids/gptkz/) | GUID resolution | HTTP 302 to the inaccessible node endpoint, so the GUID still resolves as a node. |
| [NFRD OSF node](https://api.osf.io/v2/nodes/h2pkv/) | Public-node control for the same API | HTTP 200 and `public: true`; the `gptkz` 401 is not a general anonymous-API requirement. |
| [`WarOfTheGhostsText.scala`](../../fixtures/src/main/scala/storymodel4s/fixtures/wog/WarOfTheGhostsText.scala) | Exact fixture text and provenance declaration | Boas 1901/Cultee source; the header explicitly distinguishes Bartlett's edited stimulus. |

The inspected Portsmouth PDF was 1,286,660 bytes with SHA-256
`099fcd30d3dec3204984aaace945af6a04706477e1d3b1f834ce9e7cb1eb06e5`.
It remained in temporary storage and was not added to git.

## The chief's five questions

### 1. Are raw reproductions present per participant and generation?

**Unknown because the deposit is not publicly inspectable.**

The paper establishes the existence of 80 reproductions for each story: 16 chains, five
participants per chain, and both stories reproduced by every participant. It says the first
participant's handwritten reproduction was typed for the second participant, and the procedure
continued through all five positions. It also says word counts were obtained from the typed
reproductions.

Those facts show that chain text existed. They do not show what was deposited. The phrase
"data set underlying our analyses" is compatible with either participant-level text or a table of
derived proportions. Until an authorized file listing is inspected, raw-text presence remains
Unknown rather than assumed from the paper.

### 2. Is the study stimulus available, and does it match our fixture?

**The full wording is published in the paper, but it does not match our exact source.**

The article's Appendix 1 prints a full 328-word version taken from Bartlett (1932, p. 65), and the
rendered page was inspected rather than inferred from the story title. Whether the inaccessible
OSF project also contains the original participant-facing lab copy is Unknown. The repository
fixture is the free English translation recorded by Franz Boas from Charles Cultee, with a
whitespace-token count of 425 in the current source file. More importantly than the count, the
texts differ structurally. The checked-in version contains material absent from the experimental
stimulus, including:

- the initial statement about people living at Egulac;
- the non-participating young man's midnight return and report;
- additional battle and injured-warrior actions;
- additional bodily details at the death;
- different clause order and wording throughout.

Consequences:

- the existing `StorySource` checksum cannot identify the experimental stimulus;
- existing `TextSpan` and `SpanSet` evidence cannot be reused;
- situations supported only by the longer source cannot be treated as stimulus events;
- direct alignment against the current fixture would silently align recalls to words the
  participants never saw.

If the reproduction texts become available, the experiment needs a separately identified source
version and a newly compiled or adjudicated story artifact. The appendix supplies recoverable
wording, but an original lab-copy checksum would be stronger evidence for exact typography and
normalization. The existing ontology can nominate correspondences, but correspondence is not
source identity.

### 3. What are the licence and redistribution terms?

**Scientific source use is owner-authorized; the dataset licence is Unknown.**

The article and its author manuscript are distributed under CC BY 4.0. That licence does not by
itself license a separately hosted OSF dataset or participant reproductions. Nor does it
unambiguously establish redistribution rights for the stimulus credited to Bartlett (1932), which
may be third-party material reproduced within the article. Because node metadata is inaccessible,
the OSF project licence, component licences, consent terms, and redistribution status could not be
inspected.

The owner ruled on the board that using the Bartlett stimulus for this scientific work is fair use.
That authorization permits the second source version; it does not turn the stimulus into a public
redistribution asset and does not establish rights over the OSF deposit. To preserve both the
science and a low-exposure library distribution, this inventory chooses an external source file
with a recorded checksum and local-only loader rather than committing the 328-word text. Under the
chief's data-class ruling, participant reproductions still cannot be classified as freely
fetchable until authenticated node metadata establishes the dataset terms.

### 4. Are chain and generation identifiers intact?

**The experimental design is reconstructable; the deposited identifiers are Unknown.**

The paper supplies the intended structure:

- chains 1 through 16;
- positions 1 through 5;
- odd-numbered chains used lenient-audience instructions;
- even-numbered chains used strict-audience instructions;
- every participant reproduced both stories;
- story order alternated within chains and starting order was counterbalanced.

This is enough to define an expected validation schema. It is not evidence that the deposit keeps
participant, chain, position, story, condition, and presentation-order fields joined without
loss. A score table with only condition summaries would not support trajectory reconstruction.

### 5. Are the reproductions verbatim participant text or edited?

**They are typed derivations of handwritten productions, with a partly stated edit policy.**

The paper states that each reproduction was typed for the next participant and that spelling and
grammatical errors were retained. It does not report an audit trail, double-entry check,
illegibility policy, or whether punctuation, layout, crossings-out, insertions, and other graphic
features were normalized.

The typed text therefore has two roles that must not be collapsed:

| Field or artifact | Intake class | Reason |
|---|---|---|
| Handwritten participant sheet | **Observation** if retained and released | Direct participant production. |
| Typed reproduction as a transcript of the sheet | **Derivation** | A human transcription step intervenes, even though spelling and grammar were reportedly retained. |
| The same typed reproduction shown to the next chain member | **Experimental observation/stimulus** for that next position | It is the exact material the later participant reportedly read. Its upstream derivation remains part of provenance. |
| Word, detail, and theme scores or proportions | **Derivation** | Token counting, a coding scheme, partial credit, adjudication, and normalization were applied. |

Whether the deposit contains handwritten scans, typed reproductions, scores, or some combination
remains Unknown.

## Scientific disposition

The inspection changes the attractive initial story in two ways.

First, `gptkz` remains unusually valuable because serial chains test transformation trajectories,
not only independent recall. But it is not currently an open corpus: the project named by the
paper is inaccessible without authentication, and neither raw-text presence nor data licence can
be verified.

Second, the source side is not already complete in the identity-preserving sense used by
storymodel4s. What is complete is a closely related Boas/Cultee narrative fixture and ontology.
The experimental Bartlett source must have its own checksum, surface atlas, evidence spans, and
validated narrative artifact before an alignment can carry acceptance weight. The owner has now
authorized that scientific source use; the remaining corpus gate is authenticated access to the
deposit and confirmation that participant-level text is actually present.

## Next action

1. Use an existing owner-authorized OSF session or token to inspect the file and licence inventory.
   Do not create an account, generate credentials, or request access on the project's behalf
   autonomously. The first authenticated answer remains only “raw reproductions or coded tables?”
2. If authenticated access reveals participant-level reproductions and acceptable terms, keep them
   outside git, record per-file checksums, and validate the full 16 by 5 by 2 key structure before
   reading content.
3. Register the Appendix 1 Bartlett stimulus as a distinct external source version with the
   owner-authorized fair-use basis. Do not overwrite or alias the current fixture, and do not place
   the words in git under the selected low-exposure posture. Seek the original lab copy for
   exact-version assurance; store the article reference, local content checksum, and local-only
   loader.
4. Only then run one Diagnostic trajectory question, with chains kept intact during any split and
   with typed-transcription provenance visible in every result.
5. If the deposit contains only coded scores, close the corpus route for text alignment. It may
   still serve as a published-score reproduction target, but it is not a source-to-recall text
   corpus.
