package storymodel4s.coreprobe

import cats.data.NonEmptyVector
import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite
import storymodel4s.core.*

/** Probes from OUTSIDE `storymodel4s.core`. Live holes were demonstrated first:
  *
  *   - `CredenceForgeSuite` (2/2) minted `fromProduct((0.9, Some(p), None))` while `Credence.from`
  *     refused the same pair.
  *   - `RemainingForgeSuite` (6/6, 2026-08-29, unpiped `coreJVM/testOnly`) minted negative
  *     `TextSpan` / `AudioSpan`, inverted `TokenRange`, unsorted duplicate `SpanSet` refs, empty
  *     `StorySource` text, and `ClaimMeta` SurfaceExplicit with no spans. Those suites are gone
  *     because the doors they called no longer exist.
  */
class ConstructionProbeSuite extends FunSuite:

  /** Sweep 3 slice: the Mirror.ProductOf door across core's private-constructor case classes.
    *
    * `case class X private (...)` still derives `Mirror.ProductOf` in Scala 3, so the private
    * constructor is defeated from outside the package. Pins the measured state; closing any of
    * these makes this FAIL and asks for the name to be removed. Tracked on
    * bd-01M183VBPNEAPT5JNQMBMYMKQ9.
    */
  test("sweep 3: Mirror.ProductOf door across core private-constructor case classes") {
    assertEquals(
      typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.SurfaceUnit]]"),
      Nil,
      "the summon control must compile or every result below is meaningless"
    )
    val results: List[(String, List[scala.compiletime.testing.Error])] = List(
      (
        "ClaimLedger",
        typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.ClaimLedger]]")
      )
    )
    val forgeable = results.collect { case (n, errs) if errs.isEmpty => n }.toSet
    assertEquals(
      forgeable,
      results.map(_._1).toSet,
      "sweep 3 expects the Mirror door OPEN here; a difference means one was closed (update this " +
        "list) or a new private-constructor case class was added unmeasured"
    )
  }

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.core")

  test("positive control: a remaining case class still has fromProduct") {
    val errors = typeCheckErrors(
      """storymodel4s.core.SpanRef.fromProduct((None, storymodel4s.core.TextSpan.unsafe(0, 1)))"""
    )
    assert(
      errors.isEmpty,
      s"if SpanRef.fromProduct fails to typecheck, every refusal beside it is meaningless:\n${errors.mkString("\n")}"
    )
  }

  test("Credence has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.core.Credence.fromProduct((storymodel4s.core.Score.Unmeasured, storymodel4s.core.CredenceBasis.Uncalibrated))"""
      ),
      "Credence.fromProduct"
    )
  }

  test("Credence has no copy door") {
    refused(
      typeCheckErrors(
        """(c: storymodel4s.core.Credence) => c.copy(basis = storymodel4s.core.CredenceBasis.Uncalibrated)"""
      ),
      "Credence.copy"
    )
  }

  test("Credence retains public read access") {
    assert(
      typeCheckErrors(
        """(c: storymodel4s.core.Credence) => (c.score, c.basis, c.rawScore, c.calibrated, c.calibrationModel)"""
      ).isEmpty
    )
  }

  test("the checked factories remain the public path") {
    assert(
      typeCheckErrors(
        """storymodel4s.core.Credence.raw(0.2, storymodel4s.core.ScorerId.unsafe("probe"))"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.core.Credence.calibrated(0.2, storymodel4s.core.ScorerId.unsafe("probe"), storymodel4s.core.Probability.unsafe(0.5), storymodel4s.core.CalibrationModelId.unsafe("isotonic-v1"))"""
      ).isEmpty
    )
    val p = Probability.unsafe(0.5)
    val model = CalibrationModelId.unsafe("m")
    val scorer = ScorerId.unsafe("probe")
    assert(Credence.of(Score.Unmeasured, CredenceBasis.Calibrated(p, model)).isLeft)
    assert(Credence.of(Score.Raw(0.1, scorer), CredenceBasis.Calibrated(p, model)).isRight)
    assert(Credence.of(Score.Raw(Double.NaN, scorer), CredenceBasis.Uncalibrated).isLeft)
    assert(
      Credence.raw(0.1, scorer).exists(c => c.calibrated.isEmpty && c.calibrationModel.isEmpty)
    )
  }

  test("ProviderCall apply cannot admit a raw prompt-template version") {
    refused(
      typeCheckErrors(
        """(p: storymodel4s.core.ProviderCall) =>
             storymodel4s.core.ProviderCall(
               p.provider, p.model, p.version, Some(" "), p.inputChecksum,
               p.outputChecksum, p.params, p.seed, p.cached
             )"""
      ),
      "ProviderCall.apply with a raw blank prompt-template version"
    )
  }

  test("ProviderCall copy cannot admit a raw prompt-template version") {
    refused(
      typeCheckErrors(
        """(p: storymodel4s.core.ProviderCall) =>
             p.copy(promptTemplateVersion = Some(" "))"""
      ),
      "ProviderCall.copy with a raw blank prompt-template version"
    )
  }

  test("ProviderCall has no fromProduct door for a raw prompt-template version") {
    refused(
      typeCheckErrors(
        """(p: storymodel4s.core.ProviderCall) =>
             storymodel4s.core.ProviderCall.fromProduct((
               p.provider, p.model, p.version, Some(" "), p.inputChecksum,
               p.outputChecksum, p.params, p.seed, p.cached
             ))"""
      ),
      "ProviderCall.fromProduct with a raw blank prompt-template version"
    )
  }

  test("PromptTemplateVersion rejects blanks and preserves admitted bytes") {
    Vector("", " ", "\t\n", "\u2003").foreach(raw => assert(PromptTemplateVersion.from(raw).isLeft))
    val admitted = "  provider-v1  "
    assertEquals(PromptTemplateVersion.from(admitted).map(_.value), Right(admitted))
    intercept[IllegalArgumentException](PromptTemplateVersion.unsafe(" "))
  }

  test("TextSpan has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.core.TextSpan.fromProduct((-1, 4))"""),
      "TextSpan.fromProduct"
    )
  }

  test("TextSpan has no copy door") {
    refused(
      typeCheckErrors("""(s: storymodel4s.core.TextSpan) => s.copy(start = -1)"""),
      "TextSpan.copy"
    )
  }

  test("TextSpan factories still reject a negative start") {
    assert(typeCheckErrors("""storymodel4s.core.TextSpan.of(0, 1)""").isEmpty)
    assert(TextSpan.of(-1, 4).isLeft)
    assert(TextSpan.of(0, 1).isRight)
  }

  test("TokenRange has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.core.TokenRange.fromProduct((storymodel4s.core.TokenIndex.unsafe(5), storymodel4s.core.TokenIndex.unsafe(1)))"""
      ),
      "TokenRange.fromProduct"
    )
  }

  test("TokenRange has no copy door") {
    refused(
      typeCheckErrors(
        """(r: storymodel4s.core.TokenRange) => r.copy(start = storymodel4s.core.TokenIndex.unsafe(9))"""
      ),
      "TokenRange.copy"
    )
  }

  test("TokenRange factories still reject end before start") {
    assert(typeCheckErrors("""storymodel4s.core.TokenRange.of(0, 2)""").isEmpty)
    assert(TokenRange.of(5, 1).isLeft)
    assert(TokenRange.of(0, 2).isRight)
  }

  test("AudioSpan has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.core.AudioSpan.fromProduct((-10L, 20L))"""),
      "AudioSpan.fromProduct"
    )
  }

  test("AudioSpan has no copy door") {
    refused(
      typeCheckErrors("""(a: storymodel4s.core.AudioSpan) => a.copy(startMillis = -1L)"""),
      "AudioSpan.copy"
    )
  }

  test("AudioSpan factories still reject a negative start") {
    assert(typeCheckErrors("""storymodel4s.core.AudioSpan.of(0L, 10L)""").isEmpty)
    assert(AudioSpan.of(-10L, 20L).isLeft)
    assert(AudioSpan.of(0L, 10L).isRight)
  }

  test("SpanSet has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.core.SpanSet.fromProduct(Tuple1(cats.data.NonEmptyVector.one(storymodel4s.core.SpanRef(storymodel4s.core.TextSpan.unsafe(0, 1)))))"""
      ),
      "SpanSet.fromProduct"
    )
  }

  test("SpanSet has no copy door") {
    refused(
      typeCheckErrors("""(s: storymodel4s.core.SpanSet) => s.copy(refs = s.refs)"""),
      "SpanSet.copy"
    )
  }

  test("SpanSet.of still sorts and deduplicates") {
    assert(
      typeCheckErrors(
        """storymodel4s.core.SpanSet.one(storymodel4s.core.TextSpan.unsafe(0, 1))"""
      ).isEmpty
    )
    val a = SpanRef(TextSpan.unsafe(5, 8))
    val b = SpanRef(TextSpan.unsafe(0, 2))
    assertEquals(SpanSet.of(Vector(a, a, b)).map(_.refs.toVector), Some(Vector(b, a)))
  }

  test("StorySource has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.core.StorySource.fromProduct(EmptyTuple)"""),
      "StorySource.fromProduct"
    )
  }

  test("StorySource has no copy door") {
    refused(
      typeCheckErrors("""(s: storymodel4s.core.StorySource) => s.copy(rawText = "")"""),
      "StorySource.copy"
    )
  }

  test("StorySource.fromText still rejects empty text") {
    assert(typeCheckErrors("""storymodel4s.core.StorySource.fromText("Once.")""").isEmpty)
    assert(StorySource.fromText("").isLeft)
    assert(StorySource.fromText("Once.").isRight)
  }

  test("ClaimMeta has no derived fromProduct bypass") {
    refused(
      typeCheckErrors("""storymodel4s.core.ClaimMeta.fromProduct(EmptyTuple)"""),
      "ClaimMeta.fromProduct"
    )
  }

  test("ClaimMeta has no copy door") {
    refused(
      typeCheckErrors(
        """(m: storymodel4s.core.ClaimMeta) => m.copy(status = storymodel4s.core.EpistemicStatus.Hypothesized)"""
      ),
      "ClaimMeta.copy"
    )
  }

  test("ClaimMeta.of still rejects SurfaceExplicit without spans") {
    val ev = Evidence(
      EvidenceId.unsafe("e-probe"),
      None,
      Set.empty,
      Fingerprint.unsafe("rule:probe:1"),
      StageId.unsafe("stage-probe")
    )
    val left = ClaimMeta.of(
      ClaimId.unsafe("c-probe"),
      EpistemicStatus.SurfaceExplicit,
      Credence.unsafeRaw(0.9, ScorerId.unsafe("probe")),
      NonEmptyVector.one(ev),
      Provenance.deterministic("0.1.0", Checksum.ofText("cfg"))
    )
    assert(left.isLeft)
  }
