package storymodel4s.core

import cats.data.NonEmptyVector
import cats.syntax.all.*
import munit.FunSuite

class ClaimSuite extends FunSuite:
  private val fp = Fingerprint.unsafe("rule:test:1")
  private val stage = StageId.unsafe("stage-1")
  private val prov = Provenance.deterministic("0.1.0", Checksum.ofText("cfg"))

  private def evidence(id: String, spans: Option[SpanSet]) =
    Evidence(EvidenceId.unsafe(id), spans, Set.empty, fp, stage)

  private def meta(id: String, status: EpistemicStatus, ev: Evidence*) =
    ClaimMeta(
      ClaimId.unsafe(id),
      status,
      Credence.unsafeRaw(0.9),
      NonEmptyVector.fromVectorUnsafe(ev.toVector),
      prov
    )

  test("SurfaceExplicit claims require span evidence"):
    val noSpans = meta("c1", EpistemicStatus.SurfaceExplicit, evidence("e1", None))
    assert(ClaimMeta.validated(noSpans).isLeft)
    val withSpans =
      meta(
        "c2",
        EpistemicStatus.SurfaceExplicit,
        evidence("e2", Some(SpanSet.one(TextSpan.unsafe(0, 3))))
      )
    assert(ClaimMeta.validated(withSpans).isRight)

  test("inferred claims may cite only upstream claims"):
    val inferred = meta("c3", EpistemicStatus.WorldKnowledgeInferred, evidence("e3", None))
    assert(ClaimMeta.validated(inferred).isRight)

  test("ledger rejects duplicates and preserves insertion order"):
    val a = meta("a", EpistemicStatus.Hypothesized, evidence("e", None))
    val b = meta("b", EpistemicStatus.Hypothesized, evidence("e", None))
    val led = ClaimLedger.empty.add(a).flatMap(_.add(b))
    assert(led.isRight)
    assertEquals(led.toOption.get.all.map(_.id.value), Vector("a", "b"))
    assert(led.flatMap(_.add(a)).isLeft)
    assertEquals(led.toOption.get.byStatus(EpistemicStatus.Hypothesized).size, 2)
    assertEquals(led.toOption.get.byStatus(EpistemicStatus.SurfaceExplicit).size, 0)

  test("ledger applies the span law on add"):
    val bad = meta("x", EpistemicStatus.SurfaceExplicit, evidence("e", None))
    assert(ClaimLedger.empty.add(bad).isLeft)

  test("Resolved is a functor over value and alternatives"):
    val m = meta("r", EpistemicStatus.Hypothesized, evidence("e", None))
    val r = Resolved(1, m, Vector((2, Credence.unsafeRaw(0.2))))
    val mapped = r.map(_ * 10)
    assertEquals(mapped.value, 10)
    assertEquals(mapped.alternatives.map(_._1), Vector(20))
    assertEquals(r.fmap(_ + 1).value, 2)
    assert(r.hasAlternatives)

  test("ClaimMeta.spans merges evidence spans"):
    val m = meta(
      "s",
      EpistemicStatus.SurfaceExplicit,
      evidence("e1", Some(SpanSet.one(TextSpan.unsafe(0, 3)))),
      evidence("e2", Some(SpanSet.one(TextSpan.unsafe(10, 12))))
    )
    assertEquals(m.spans.map(_.size), Some(2))

  test("BuildReceipt content checksum ignores the timestamp"):
    val r1 = BuildReceipt(
      StoryId.unsafe("s"),
      Checksum.ofText("t"),
      "1",
      Vector(stage -> Checksum.ofText("x")),
      1L
    )
    val r2 = r1.copy(createdAtEpochMillis = 999L)
    assertEquals(r1.contentChecksum, r2.contentChecksum)
    assertNotEquals(r1.contentChecksum, r1.copy(schemaVersion = "2").contentChecksum)
