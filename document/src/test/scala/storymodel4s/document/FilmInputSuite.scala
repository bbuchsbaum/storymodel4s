package storymodel4s.document

import munit.FunSuite
import storymodel4s.core.*

/** Synthetic F1 boundary court: no participant or source-film text.
  *
  * Admission: `Alpha. Beta.` and `Gamma.` are original project-authored mechanical token fixtures,
  * Toronto, Canada, 2026; Apache-2.0 contribution; no external narrative, participant material,
  * cultural source, or modifications. Reviewed by /root on 2026-11-16 against edition
  * film-input-mechanical/v1. Fixture admission only, not implementation acceptance.
  */
class FilmInputSuite extends FunSuite:
  private val bundle = SourceBundle
    .filmEdition(
      EditionId.unsafe("film-input-synthetic"),
      Checksum.ofText("synthetic-picture"),
      0L,
      1000L,
      RationalTimebase.Millisecond
    )
    .toOption
    .get
  private val stream = bundle.streams.head.id
  private def support(start: Long, end: Long): EvidenceSupport =
    val interval = PlaybackInterval.on(bundle.primaryAxis, start, end).toOption.get
    val set = PlaybackIntervalSet.one(interval)
    EvidenceSupport
      .of(bundle, Vector(EvidenceAnchor.MediaTime(bundle.id, stream, bundle.primaryAxis.id, set)))
      .toOption
      .get
  private def record(id: String, text: String, start: Long, end: Long): FilmSourceRecord =
    FilmSourceRecord(
      FilmSourceRecordId.unsafe(id),
      text,
      support(start, end),
      Checksum.ofText(s"source-$id"),
      Checksum.ofText(s"proposal-$id"),
      SourceDerivationReceipt.of("caption/v1", id, Vector(Checksum.ofText(id))).toOption.get
    )
  private def expectation(
      rows: Vector[FilmSourceRecord],
      originals: Vector[NarrativeProposalUnit] = Vector.empty
  ) =
    FilmProposalSurface
      .expectedDerivation(
        bundle,
        rows,
        originals,
        Checksum.ofText(rows.map(_.description).mkString("\n\n"))
      )
      .toOption
      .get
  private def prepared(
      rows: Vector[FilmSourceRecord],
      originals: Vector[NarrativeProposalUnit] = Vector.empty
  ) =
    FilmProposalSurface.derive(bundle, rows, originals, expectation(rows, originals))
  private val rows = Vector(record("a", "Alpha. Beta.", 0, 100), record("b", "Gamma.", 100, 200))
  private val original =
    NarrativeProposalUnit.of(NarrativeProposalUnitId.unsafe("original-a"), support(0, 100), None)

  test("two records and three sentences retain exact sentence origins") {
    val value = prepared(rows, Vector(original)).toOption.get
    assertEquals(value.origins.size, 3)
    assertEquals(value.originalUnits, Vector(original))
    assertEquals(value.origins.map(_.record), Vector(rows(0).id, rows(0).id, rows(1).id))
    assertEquals(value.atlas.units.head, original)
    assertEquals(value.atlas.units.head.surface, None)
    assertEquals(
      value.sentenceUnits.map(_.support),
      Vector(rows(0).support, rows(0).support, rows(1).support)
    )
    assertEquals(value.sentenceUnits.map(_.id).distinct.size, 3)
    assertEquals(prepared(rows, Vector(original)), Right(value))
  }

  test("same bundle with changed source support changes the checked surface identity") {
    val first = prepared(Vector(record("a", "Alpha.", 0, 100))).toOption.get
    val changed = prepared(Vector(record("a", "Alpha.", 100, 200))).toOption.get
    assertNotEquals(first.identity, changed.identity)
  }

  test("expected source inventory refuses changed order and source or caption identities") {
    val expected = expectation(rows)
    val variants = Vector(
      rows.reverse,
      rows.updated(0, rows.head.copy(sourceIdentity = Checksum.ofText("foreign-source"))),
      rows.updated(0, rows.head.copy(proposalIdentity = Checksum.ofText("foreign-output"))),
      rows.updated(0, rows.head.copy(support = support(20, 100))),
      rows.updated(
        0,
        rows.head.copy(derivation =
          SourceDerivationReceipt.of("caption/v2", "a", Vector.empty).toOption.get
        )
      )
    )
    assert(FilmProposalSurface.derive(bundle, rows, Vector.empty, expected).isRight)
    variants.foreach(changed =>
      assert(FilmProposalSurface.derive(bundle, changed, Vector.empty, expected).isLeft)
    )
  }

  test("receipt recipe output and original inventory are independently checked") {
    val expected = expectation(rows, Vector(original))
    val variants = Vector(
      expected.copy(recipe = "other"),
      expected.copy(outputChecksum = Checksum.ofText("other")),
      expected.copy(receipt =
        SourceDerivationReceipt
          .of("other", expected.recipe, expected.receipt.inputChecksums)
          .toOption
          .get
      ),
      expected.copy(receipt =
        SourceDerivationReceipt
          .of("FilmProposalSurface/v1", expected.recipe, expected.receipt.inputChecksums.reverse)
          .toOption
          .get
      ),
      expected.copy(originalUnitIdentities = Vector.empty),
      expected.copy(bundleIdentity = Checksum.ofText("other-bundle"))
    )
    assert(FilmProposalSurface.derive(bundle, rows, Vector(original), expected).isRight)
    variants.foreach(changed =>
      assert(FilmProposalSurface.derive(bundle, rows, Vector(original), changed).isLeft)
    )
  }

  test("original support identity changes surface identity even with unchanged original id") {
    val changed = NarrativeProposalUnit.of(original.id, support(10, 90), None)
    assertNotEquals(
      prepared(rows, Vector(original)).toOption.get.identity,
      prepared(rows, Vector(changed)).toOption.get.identity
    )
  }

  test("duplicate records and original units are refused") {
    assert(prepared(Vector(rows.head, rows.head)).isLeft)
    assert(prepared(rows, Vector(original, original)).isLeft)
  }

  test("an original unit cannot masquerade as a generated sentence") {
    val sentence = prepared(rows).toOption.get.origins.head.sentence
    val changed = NarrativeProposalUnit.of(original.id, original.support, Some(sentence))
    assert(prepared(rows, Vector(changed)).isLeft)
  }

  test("blank and canonicalization-changing descriptions are refused without silently rewriting") {
    Vector("", " ", "Alpha.\r\nBeta.", "Alpha.\n\n\nBeta.").foreach { text =>
      assert(prepared(Vector(rows.head.copy(description = text))).isLeft)
    }
  }

  test("changed descriptions and derivations remain observable") {
    val base = prepared(rows).toOption.get.identity
    val variants = Vector(
      rows.updated(0, rows.head.copy(description = "Alpha.")),
      rows.reverse,
      rows.updated(0, rows.head.copy(sourceIdentity = Checksum.ofText("other"))),
      rows.updated(0, rows.head.copy(proposalIdentity = Checksum.ofText("other"))),
      rows.updated(
        0,
        rows.head.copy(derivation =
          SourceDerivationReceipt.of("caption/v2", "a", Vector.empty).toOption.get
        )
      )
    )
    variants.foreach(changed => assertNotEquals(prepared(changed).toOption.get.identity, base))
  }
