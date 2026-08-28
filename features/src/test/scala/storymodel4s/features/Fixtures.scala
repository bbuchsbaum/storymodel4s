package storymodel4s.features

import org.scalacheck.{Arbitrary, Gen}
import storymodel4s.core.*

/** Shared test fixtures: a neutral synthetic paragraph and a fake imageability lexicon. */
object Fixtures:
  val paragraph: String =
    "The kitchen smelled of bread and oranges. A copper kettle hissed on the stove while the cat " +
      "slept on a folded blanket. Later the idea of fairness came up, and nobody could agree on a " +
      "definition. Outside, a red bicycle leaned against the fence under a heavy grey sky. " +
      "The conversation drifted toward theory and probability, and the room grew quiet."

  val source: StorySource = StorySource.fromText(paragraph).toOption.get
  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)
  val sequence: SurfaceSequence = SurfaceSequence(atlas)

  /** Fake imageability (1–7 scale) for ~30 words; everything else is missing. */
  val lexicon: Map[String, Double] = Map(
    "kitchen" -> 6.2,
    "bread" -> 6.5,
    "oranges" -> 6.8,
    "copper" -> 5.9,
    "kettle" -> 6.4,
    "hissed" -> 4.8,
    "stove" -> 6.3,
    "cat" -> 6.9,
    "slept" -> 4.5,
    "folded" -> 4.9,
    "blanket" -> 6.1,
    "idea" -> 2.1,
    "fairness" -> 1.8,
    "agree" -> 2.4,
    "definition" -> 2.0,
    "outside" -> 4.0,
    "red" -> 5.5,
    "bicycle" -> 6.7,
    "leaned" -> 4.6,
    "fence" -> 6.0,
    "heavy" -> 4.2,
    "grey" -> 5.0,
    "sky" -> 6.2,
    "conversation" -> 3.1,
    "drifted" -> 3.5,
    "theory" -> 1.6,
    "probability" -> 1.5,
    "room" -> 5.8,
    "grew" -> 3.4,
    "quiet" -> 3.0
  )

  val imageabilitySpace: FeatureSpace[Double] = FeatureSpace(
    FeatureSpaceId.unsafe("imageability.demo"),
    "fake imageability ratings 1-7",
    FeatureValueSchema.Scalar(Some("rating")),
    Some("rating"),
    Fingerprint.unsafe("demo:imageability:0.1"),
    normalized = false
  )

  val provenance: TrackProvenance = TrackProvenance(
    Provenance.deterministic("test", Checksum.ofText("demo")),
    Some(source.canonicalChecksum)
  )

  /** Raw token track: lexical tokens looked up; punctuation excluded; unknown words missing. */
  def imageabilityTrack(
      seq: SurfaceSequence = sequence
  ): FeatureTrack[FeatureTarget.Token, Double] =
    val obs = seq.tokens.zipWithIndex.map { (t, i) =>
      val est: Estimate[Double] =
        if !t.isLexical then Estimate.Missing(MissingReason.Excluded)
        else
          t.normalized.flatMap(lexicon.get) match
            case Some(v) => Estimate.observed(v)
            case None    => Estimate.Missing(MissingReason.NotInLexicon)
      FeatureObservation[FeatureTarget.Token, Double](
        FeatureTarget.Token(TokenIndex.unsafe(i)),
        est,
        Some(SpanSet.one(t.span)),
        None
      )
    }
    FeatureTrack.raw(imageabilitySpace, obs, provenance)

  val spaceId: Gen[FeatureSpaceId] =
    Gen.nonEmptyListOf(Gen.alphaNumChar).map(cs => FeatureSpaceId.unsafe("s-" + cs.mkString))

  val scoreEstimate: Gen[ScoreEstimate] = Gen.frequency(
    3 -> Gen.chooseNum(-10.0, 10.0).map(Estimate.observed),
    1 -> Gen
      .oneOf(
        MissingReason.NotInLexicon,
        MissingReason.OutOfVocabulary,
        MissingReason.ProviderAbstained,
        MissingReason.Excluded,
        MissingReason.AllMissing,
        MissingReason.Undefined(UndefinedReason.NotFinite),
        MissingReason.Unknown
      )
      .map(Estimate.Missing(_))
  )

  val sample: Gen[Sample[Double]] = for
    p <- Gen.chooseNum(0, 50)
    e <- scoreEstimate
    w <- Gen.chooseNum(0.1, 3.0)
  yield Sample(p, e, w)

  val samples: Gen[Vector[Sample[Double]]] =
    Gen
      .nonEmptyListOf(sample)
      .map(_.toVector.groupBy(_.position).values.map(_.head).toVector.sortBy(_.position))

  given Arbitrary[ScoreEstimate] = Arbitrary(scoreEstimate)

  /** One situation per sentence, in discourse order; two segments splitting the paragraph. */
  val situationOrder: Vector[SituationId] =
    atlas.sentences.indices.map(i => SituationId.unsafe(s"sit-$i")).toVector

  val situationSupports: Map[SituationId, SpanSet] =
    situationOrder.zip(atlas.sentences).map((id, s) => id -> SpanSet.one(s.span)).toMap

  val segmentOrder: Vector[SegmentId] = Vector(SegmentId.unsafe("seg-a"), SegmentId.unsafe("seg-b"))

  val segmentSupports: Map[SegmentId, SpanSet] =
    val (a, b) = atlas.sentences.splitAt(2)
    Map(
      segmentOrder(0) -> SpanSet.of(a.map(s => SpanRef(s.span))).get,
      segmentOrder(1) -> SpanSet.of(b.map(s => SpanRef(s.span))).get
    )

  val resolver: SupportResolver =
    SupportResolver(sequence, situation = situationSupports.get, segment = segmentSupports.get)

  private val validId: Gen[String] =
    Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(':', '-', '_'))).map(_.mkString)

  /** Every `FeatureTarget` case, with ids that pass the lexical rules. */
  val featureTarget: Gen[FeatureTarget] = Gen.oneOf(
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

  given Arbitrary[FeatureTarget] = Arbitrary(featureTarget)
