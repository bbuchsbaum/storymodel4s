package storymodel4s.coreprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite
import storymodel4s.core.*

/** External construction probes for the two validated atlas values.
  *
  * Before the doors were closed, `AtlasForgeEvidenceSuite` ran against the public case classes and
  * minted a duplicate-unit `SurfaceAtlas` and duplicate-turn `TranscriptAtlas` through `copy`; both
  * values were then rejected by their validators. That disposable suite passed 1/1 on 2026-08-29.
  */
class AtlasConstructionBoundarySuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.core")

  test("positive controls: same-module case classes retain fromProduct") {
    val surfaceErrors = typeCheckErrors(
      """storymodel4s.core.SurfaceUnit.fromProduct((storymodel4s.core.SurfaceUnitId.unsafe("u"), storymodel4s.core.SurfaceUnitKind.Token, storymodel4s.core.TextSpan.unsafe(0, 1), 0, None))"""
    )
    val transcriptErrors = typeCheckErrors(
      """storymodel4s.core.TranscriptTurn.fromProduct((storymodel4s.core.TurnId.unsafe("t"), storymodel4s.core.SpeakerId.unsafe("s"), storymodel4s.core.SpanSet.one(storymodel4s.core.TextSpan.unsafe(0, 1)), None, None, None))"""
    )
    assert(
      surfaceErrors.isEmpty && transcriptErrors.isEmpty,
      s"if the same-shape controls fail, every refusal beside them is meaningless:\n${(surfaceErrors ++ transcriptErrors).mkString("\n")}"
    )
  }

  /** Scala 3 has TWO product-reconstruction doors: the companion's own `fromProduct`, and
    * `summon[Mirror.ProductOf[T]].fromProduct`. The suite probed only the first. For a
    * `final class ... private` no `Mirror.ProductOf` is derived at all, so this asserts the SECOND
    * door is absent too — an independent witness, and the one that would catch a regression to
    * `case class` even if the companion probe were edited away.
    *
    * The positive control above is what makes these refusals mean anything: it forges a real
    * same-module case class through the companion door and asserts that compiles.
    */
  test("SurfaceAtlas and TranscriptAtlas have no Mirror.ProductOf summon door") {
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceAtlas]]"
      ),
      "Mirror.ProductOf[SurfaceAtlas]"
    )
    refused(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.TranscriptAtlas]]"
      ),
      "Mirror.ProductOf[TranscriptAtlas]"
    )
    // Same-shape positive control for THIS door specifically: a case class in the same module
    // must still summon, or both refusals above are vacuous.
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"
      ),
      Nil,
      "the summon control must compile or the two refusals beside it prove nothing"
    )
  }

  test("SurfaceAtlas has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.core.SurfaceAtlas.fromProduct((null, Vector.empty))"""
      ),
      "SurfaceAtlas.fromProduct"
    )
  }

  test("SurfaceAtlas has no copy door") {
    refused(
      typeCheckErrors(
        """(a: storymodel4s.core.SurfaceAtlas) => a.copy(units = a.units :+ a.units.head)"""
      ),
      "SurfaceAtlas.copy"
    )
  }

  test("SurfaceAtlas retains public reads and a checked construction path") {
    assert(
      typeCheckErrors(
        """(a: storymodel4s.core.SurfaceAtlas) => (a.source, a.units, a.tokens, a.byId)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.core.SurfaceAtlas.of(null, Vector.empty)"""
      ).isEmpty
    )

    val source = StorySource.fromText("Alpha beta.").toOption.get
    val atlas = SurfaceAnalyzer.analyze(source)
    assert(SurfaceAtlas.of(source, atlas.units :+ atlas.units.head).isLeft)
  }

  test("TranscriptAtlas has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.core.TranscriptAtlas.fromProduct((null, Vector.empty, Map.empty))"""
      ),
      "TranscriptAtlas.fromProduct"
    )
  }

  test("TranscriptAtlas has no copy door") {
    refused(
      typeCheckErrors(
        """(t: storymodel4s.core.TranscriptAtlas) => t.copy(turns = t.turns :+ t.turns.head)"""
      ),
      "TranscriptAtlas.copy"
    )
  }

  test("TranscriptAtlas retains public reads and a checked construction path") {
    assert(
      typeCheckErrors(
        """(t: storymodel4s.core.TranscriptAtlas) => (t.atlas, t.turns, t.speakers, t.byId)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.core.TranscriptAtlas.of(null, Vector.empty, Map.empty)"""
      ).isEmpty
    )

    val source = StorySource.fromText("Alpha beta.").toOption.get
    val atlas = SurfaceAnalyzer.analyze(source)
    val speaker = SpeakerId.unsafe("participant")
    val turn = TranscriptTurn(
      TurnId.unsafe("turn-0"),
      speaker,
      SpanSet.one(TextSpan.unsafe(0, source.canonicalText.length)),
      None,
      Some(InterviewPhase.FreeRecall),
      None
    )
    assert(
      TranscriptAtlas
        .of(atlas, Vector(turn, turn), Map(speaker -> SpeakerRole.Participant))
        .isLeft
    )
  }
