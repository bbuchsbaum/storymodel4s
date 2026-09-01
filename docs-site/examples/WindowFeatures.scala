package docsprobe

import storymodel4s.core.*
import storymodel4s.features.*

/** Align a scalar feature to tokens, then derive sliding-window means with explicit coverage and
  * missing-value policy.
  */
@main def windowFeatures(): Unit =
  val source = StorySource
    .fromText("Red boats crossed quietly. Abstract ideas remained.")
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val atlas = SurfaceAnalyzer.analyze(source)
  val sequence = SurfaceSequence(atlas)
  val lexicon = Map(
    "red" -> 5.5,
    "boats" -> 6.7,
    "crossed" -> 4.0,
    "quietly" -> 3.0,
    "abstract" -> 1.2,
    "ideas" -> 2.0
  )
  val space = FeatureSpace[Double](
    FeatureSpaceId.unsafe("imageability.demo"),
    "synthetic imageability rating",
    FeatureValueSchema.Scalar(Some("rating")),
    Some("rating"),
    Fingerprint.unsafe("docs:imageability:v1"),
    normalized = false
  )
  val provenance = TrackProvenance(
    Provenance.deterministic("docs", Checksum.ofText("imageability-demo")),
    Some(source.canonicalChecksum)
  )
  val raw = FeatureTrack.raw(
    space,
    sequence.tokens.zipWithIndex.map { (token, index) =>
      val estimate: Estimate[Double] =
        if !token.isLexical then Estimate.Missing(MissingReason.Excluded)
        else
          token.normalized.flatMap(lexicon.get) match
            case Some(value) => Estimate.observed(value)
            case None        => Estimate.Missing(MissingReason.NotInLexicon)
      FeatureObservation[FeatureTarget.Token, Double](
        FeatureTarget.Token(TokenIndex.unsafe(index)),
        estimate,
        Some(SpanSet.one(token.span)),
        None
      )
    },
    provenance
  )

  val plan = WindowPlan.words(width = 4, step = 2)
  val permissive = Windowed(
    raw,
    sequence,
    plan,
    ScalarReducer.Mean,
    MissingValuePolicy.IgnoreMissing
  ).fold(error => throw new IllegalArgumentException(error.message), identity)
  val strict = Windowed(
    raw,
    sequence,
    plan,
    ScalarReducer.Mean,
    MissingValuePolicy.RequireMinCoverage(0.75)
  ).fold(error => throw new IllegalArgumentException(error.message), identity)

  def render(estimate: Estimate[Double]): String = estimate match
    case Estimate.Observed(value, _) => f"Observed($value%.2f)"
    case Estimate.Missing(reason)    => s"Missing($reason)"

  println(s"surface tokens: ${sequence.size}; lexical tokens: ${sequence.lexicalSize}")
  println(s"raw track coverage: ${raw.coverage.observed}/${raw.coverage.eligible}")
  println(s"window recipe: ${plan.canonicalString}")
  permissive.observations.zip(strict.observations).foreach { (loose, gated) =>
    val range = loose.target.range
    val words = sequence
      .slice(range)
      .filter(_.isLexical)
      .map(token => atlas.text(token.unit))
      .mkString(" ")
    val coverage = loose.coverage.getOrElse(throw new IllegalStateException("missing coverage"))
    println(
      s"${range.toString}: [$words]; coverage=${coverage.observed}/${coverage.eligible}; " +
        s"ignore=${render(loose.estimate)}; require75=${render(gated.estimate)}"
    )
  }
  println(s"different policies, different derived spaces: ${permissive.space.id != strict.space.id}")
  println(s"derived recipe recorded: ${permissive.derivation.exists(_.window.contains(plan))}")
