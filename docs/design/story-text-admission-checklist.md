# Story-text admission checklist

**Status:** binding. Owner-requested sign-off gate.
**Applies to:** any narrative text, recall transcript, or excerpt thereof that
enters this repository in any form — fixture, doc, test resource, or comment.

A story text is not an ordinary asset. Once committed it is redistributed by
every clone, indexed by GitHub, and carried into any downstream publication that
cites this library. There is no quiet deletion afterwards. This checklist is
therefore a gate before the first commit, not a cleanup afterwards.

## The gate

No story text is committed until **every** line below is answered in writing, in
the text file's own header, and the answers are checked by someone other than
whoever proposes the commit.

### 1. Copyright status — the legal question

- [ ] **Publication year and jurisdiction recorded.** Not "old" or "classic" —
      the year, the place, and the edition actually transcribed.
- [ ] **The specific basis for public domain is named**, not assumed. Acceptable
      bases: published before the applicable cutoff; US Government work; an
      explicit licence granting redistribution. "It's on the internet", "it's
      widely reproduced", and "everyone in the field uses it" are not bases.
- [ ] **The basis is checked against the edition in hand.** A public-domain
      story can carry a copyrighted *translation*, *arrangement*, or *editorial
      apparatus*. The 1901 text being free says nothing about a 1978 retelling.
- [ ] **Recall transcripts produced by participants are never admitted under
      this section.** They are governed by §3.

### 2. Attribution — the scholarly question

- [ ] **The narrator is named where the narrator is known**, not only the
      collector or editor. Ethnographic texts are routinely cited to the
      person who wrote them down rather than the person who told them; that is
      an error this project does not repeat. *War of the Ghosts* is narrated by
      **Charles Cultee** and recorded by Franz Boas; both names belong in the
      header, and Cultee's first.
- [ ] **Every modification to the source text is recorded in the header** —
      normalizations, OCR corrections, spelling regularizations, omissions.
      A modified text presented as the source is a fabricated record.
- [ ] **The reason for each modification is given**, so a later reader can undo
      it. "Normalized to match Bartlett 1932" is a reason; "cleaned up" is not.

### 3. Human-subject provenance — the ethical question

- [ ] **No participant recall text enters the repository, in any form, until an
      REB/IRB basis for redistribution is recorded here by the owner.**
      Pseudonymization is not consent to publish, and this library's own
      pseudonymization machinery is a technical control, not an ethical one.
- [ ] **Synthetic and researcher-authored recalls are labelled as such** in the
      file and in the fixture that loads them, so they can never be mistaken
      for participant data in a paper.
- [ ] Where a text is participant-derived but admitted under a recorded basis,
      **the basis is cited in the header**, not in a commit message.

### 4. Cultural provenance — the question this corpus specifically raises

*War of the Ghosts* is the founding stimulus of the serial-reproduction
literature, and Bartlett used it precisely *because* he judged it unfamiliar
and strange to his English participants. That framing has been criticized at
length and the criticism is now part of the scholarship, not a footnote to it.

- [ ] **Where a text comes from an Indigenous or minoritized oral tradition,
      the header says so and names the tradition** — here, Kathlamet.
- [ ] **The header does not reproduce a framing of the text as "strange",
      "primitive", or "incoherent".** Describe what the text is; do not inherit
      a century-old judgement about its narrator's mind. Where the project
      needs to discuss Bartlett's framing, it cites it as a claim under study,
      never as a description of the material.
- [ ] **Community licensing or protocol restrictions are checked** where the
      tradition has them. Public-domain status under copyright law and
      permission to redistribute under a community's own protocol are
      different questions with different answers.

## Sign-off record

Each admitted text carries these lines in its own header, filled in:

```
Source:        <author/narrator>, <collector/editor>, <work>, <year>, <pages>
Basis:         <the specific public-domain or licence basis>
Modifications: <each change, with its reason>
Tradition:     <where applicable>
Admitted by:   <who checked>, <date>, checked against <edition>
```

## Texts currently admitted

| Text | Basis | Status |
|---|---|---|
| `war-of-the-ghosts-boas1901.txt` | Boas, *Kathlamet Texts*, BAE Bulletin 26 (1901) — US Government publication | Admitted. §1 and §2 satisfied in the file header. **§2 attribution amended 2026-08-29** to name Charles Cultee as narrator. §4 tradition (Kathlamet) recorded. |

Nothing else. Any addition to this table is a reviewed change, not an
incidental part of a feature commit.
