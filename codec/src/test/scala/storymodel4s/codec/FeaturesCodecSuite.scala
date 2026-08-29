package storymodel4s.codec

import cats.data.NonEmptyVector
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import storymodel4s.core.*
import storymodel4s.features.*
import FeatureCodecs.given

/** Wire form of `features` targets and recipes: every `FeatureTarget` case (including the
  * surface-unit case of ADR 0002 §9 checkpoint 2) and the optional narrative-window slot of a
  * derivation round-trip, and a recipe keeps its `derivationId` across the round trip.
  */
class FeaturesCodecSuite extends ScalaCheckSuite:

  private val validId: Gen[String] =
    Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(':', '-', '_'))).map(_.mkString)

  private val featureTarget: Gen[FeatureTarget] = Gen.oneOf(
    Gen.chooseNum(0, 5000).map(i => FeatureTarget.Token(TokenIndex.unsafe(i))),
    validId.map(s => FeatureTarget.Sentence(SurfaceUnitId.unsafe(s))),
    validId.map(s => FeatureTarget.Situation(SituationId.unsafe(s))),
    validId.map(s => FeatureTarget.Segment(SegmentId.unsafe(s))),
    validId.map(s => FeatureTarget.Boundary(SurfaceUnitId.unsafe(s))),
    validId.map(s => FeatureTarget.Turn(TurnId.unsafe(s))),
    for
      a <- Gen.chooseNum(0, 5000)
      n <- Gen.chooseNum(0, 100)
    yield FeatureTarget.Window(TokenRange.unsafe(a, a + n)),
    validId.map(s => FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe(s)))
  )

  private val narrativePlan: Gen[NarrativeWindowPlan] =
    Gen.chooseNum(0, 12).flatMap(h => NarrativeWindowPlan.of(h).fold(_ => Gen.fail, Gen.const))

  private val reducer: Gen[ScalarReducer] = Gen.oneOf(
    ScalarReducer.Mean,
    ScalarReducer.Sum,
    ScalarReducer.WeightedMean,
    ScalarReducer.Kernel(KernelShape.Gaussian(1.5)),
    ScalarReducer.Kernel(KernelShape.Rectangular(0.0))
  )

  private val derivation: Gen[FeatureDerivation] = for
    in <- validId.map(FeatureSpaceId.unsafe)
    // a recipe slides over at most one axis
    (w, nw) <- Gen.oneOf(
      Gen.const((None, None)),
      Gen
        .oneOf(WindowPlan.words(20, 5), WindowPlan.sentences(1, 1), WindowPlan.tokens(3, 1))
        .map(p => (Some(p), None)),
      narrativePlan.map(p => (None, Some(p)))
    )
    red <- reducer
    miss <- Gen.oneOf(
      MissingValuePolicy.IgnoreMissing,
      MissingValuePolicy.Fail,
      MissingValuePolicy.RequireMinCoverage(0.25)
    )
    norm <- Gen.option(Gen.oneOf(NormalizationPolicy.UnitLength, NormalizationPolicy.ZScore("pop")))
    el <- Gen.oneOf(Eligibility.values.toSeq)
    tf <- Gen.option(Gen.oneOf(TargetFamily.values.toSeq))
  yield FeatureDerivation(
    NonEmptyVector.one(in),
    w,
    red.id,
    red.weighting,
    miss,
    norm,
    "codec-test",
    el,
    tf,
    nw
  )

  property("FeatureTarget: decode(encode(t)) == t for every case") {
    forAll(featureTarget) { t =>
      assertEquals(Canonical.decode[FeatureTarget](Canonical.encode(t)), Right(t))
    }
  }

  property("FeatureTarget: canonical form is a fixed point") {
    forAll(featureTarget)(t => Canonical.isFixedPoint(t))
  }

  test("SurfaceUnit carries the wire tag \"SurfaceUnit\"; unknown tags are rejected") {
    val t = FeatureTarget.SurfaceUnit(SurfaceUnitId.unsafe("s:p0"))
    val text = """{"type":"SurfaceUnit","unit":"s:p0"}"""
    assertEquals(Canonical.encode(t), text)
    assertEquals(Canonical.decode[FeatureTarget](text), Right(t))
    assert(Canonical.decode[FeatureTarget]("""{"type":"Unit","unit":"s:p0"}""").isLeft)
    assert(Canonical.decode[FeatureTarget]("""{"type":"SurfaceUnit","unit":"a b"}""").isLeft)
    // the sentence case is unchanged and distinct on the wire
    assertEquals(
      Canonical.encode(FeatureTarget.Sentence(SurfaceUnitId.unsafe("s:p0"))),
      """{"type":"Sentence","unit":"s:p0"}"""
    )
    assertEquals(
      Canonical.decode[TargetFamily]("\"SurfaceUnit\""),
      Right(TargetFamily.SurfaceUnit)
    )
  }

  test("BasisId and basis-bearing provenance round-trip; an absent legacy field is None") {
    val basis = BasisId
      .of(
        TargetFamily.Situation,
        Vector(
          FeatureTarget.Situation(SituationId.unsafe("alpha")),
          FeatureTarget.Situation(SituationId.unsafe("beta"))
        )
      )
      .toOption
      .get
    assertEquals(Canonical.decode[BasisId](Canonical.encode(basis)), Right(basis))
    assert(Canonical.isFixedPoint(basis))

    val provenance = TrackProvenance(
      Provenance.deterministic("basis-codec", Checksum.ofText("receipt")),
      None,
      Some(basis)
    )
    assertEquals(
      Canonical.decode[TrackProvenance](Canonical.encode(provenance)),
      Right(provenance)
    )
    val legacy = provenance.copy(basisId = None)
    val legacyJson = Canonical.encode(legacy)
    assert(!legacyJson.contains("basisId"))
    assertEquals(Canonical.decode[TrackProvenance](legacyJson), Right(legacy))
  }

  test("FeatureTrack decoding rejects a basis that does not match its output space") {
    val derivation = FeatureDerivation(
      NonEmptyVector.one(FeatureSpaceId.unsafe("raw.demo")),
      None,
      ScalarReducer.Mean.id,
      WeightingPolicy.Uniform,
      MissingValuePolicy.IgnoreMissing,
      None,
      "basis-codec",
      targetFamily = Some(TargetFamily.Situation)
    )
    val basis = BasisId
      .of(
        TargetFamily.Situation,
        Vector(FeatureTarget.Situation(SituationId.unsafe("alpha")))
      )
      .toOption
      .get
    val space: FeatureSpace[Double] = FeatureSpace(
      derivation.outputSpaceId,
      "deliberately mismatched basis output",
      FeatureValueSchema.Scalar(None),
      None,
      Fingerprint.unsafe("codec:basis:1"),
      normalized = false
    )
    val invalid: FeatureTrack[FeatureTarget, Double] = FeatureTrack(
      space,
      Vector.empty,
      Some(derivation),
      TrackProvenance(
        Provenance.deterministic("basis-codec", Checksum.ofText("receipt")),
        None,
        Some(basis)
      )
    )
    assert(Canonical.decode[FeatureTrack[FeatureTarget, Double]](Canonical.encode(invalid)).isLeft)
  }

  property("NarrativeWindowPlan round-trips; a negative half-width is refused") {
    forAll(narrativePlan) { p =>
      assertEquals(Canonical.decode[NarrativeWindowPlan](Canonical.encode(p)), Right(p))
      assert(Canonical.isFixedPoint(p))
    }
  }

  test("NarrativeWindowPlan decodes through the checked constructor") {
    assertEquals(Canonical.encode(NarrativeWindowPlan.perUnit), """{"halfWidth":0}""")
    assert(Canonical.decode[NarrativeWindowPlan]("""{"halfWidth":-1}""").isLeft)
    assert(Canonical.decode[NarrativeWindowPlan]("""{}""").isLeft)
  }

  property("FeatureDerivation round-trips with and without a narrative window, id preserved") {
    forAll(derivation) { d =>
      val back = Canonical.decode[FeatureDerivation](Canonical.encode(d))
      assertEquals(back, Right(d))
      assertEquals(back.map(_.derivationId), Right(d.derivationId))
      assertEquals(back.map(_.canonicalString), Right(d.canonicalString))
      assert(Canonical.isFixedPoint(d))
    }
  }

  test("a recipe without a narrative window has no such key; an absent key decodes to None") {
    val d = FeatureDerivation(
      NonEmptyVector.one(FeatureSpaceId.unsafe("imageability.demo")),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Mean.id,
      ScalarReducer.Mean.weighting,
      MissingValuePolicy.IgnoreMissing,
      None,
      "codec-test"
    )
    val text = Canonical.encode(d)
    assert(!text.contains("narrativeWindow"))
    assertEquals(Canonical.decode[FeatureDerivation](text).map(_.narrativeWindow), Right(None))
    val withPlan = d.copy(window = None, narrativeWindow = NarrativeWindowPlan.of(2).toOption)
    val planText = Canonical.encode(withPlan)
    assert(planText.contains(""""narrativeWindow":{"halfWidth":2}"""))
    assertEquals(Canonical.decode[FeatureDerivation](planText), Right(withPlan))
  }

  test("a document carrying both a surface and a narrative window is a typed decode error") {
    val both = FeatureDerivation(
      NonEmptyVector.one(FeatureSpaceId.unsafe("imageability.demo")),
      Some(WindowPlan.words(20, 5)),
      ScalarReducer.Mean.id,
      ScalarReducer.Mean.weighting,
      MissingValuePolicy.IgnoreMissing,
      None,
      "codec-test",
      narrativeWindow = NarrativeWindowPlan.of(1).toOption
    )
    Canonical.decode[FeatureDerivation](Canonical.encode(both)) match
      case Left(e)  => assert(e.toString.contains("narrative window"), e.toString)
      case Right(d) => fail(s"decoded an invalid recipe: $d")
  }

  test("accepted canonical text is a fixed point: encode(decode(json)) == json") {
    val json =
      """{"eligibility":"LexicalTokens","implementationVersion":"narrative-windowed-1",""" +
        """"inputs":["imageability.demo"],"missing":"IgnoreMissing",""" +
        """"narrativeWindow":{"halfWidth":1},"reducer":"mean","targetFamily":"Situation",""" +
        """"weighting":"Uniform"}"""
    Canonical.decode[FeatureDerivation](json) match
      case Right(d) => assertEquals(Canonical.encode(d), json)
      case Left(e)  => fail(s"accepted json did not decode: $e")
  }
