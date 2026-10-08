package storymodel4s.bench.video

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import io.circe.Json
import io.circe.parser.parse
import storymodel4s.align.*
import storymodel4s.bench.{BenchChannels, Channel}
import storymodel4s.codec.HsmmResultCodec
import storymodel4s.core.*
import storymodel4s.embed.onnx.{HistoricalParityFixture, OnnxSentenceEmbedder}
import storymodel4s.features.Estimate
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** An identical test-only capture runs on the untouched base and candidate, with explicit inputs.
  */
object HistoricalEmbeddingParityCapture:
  final case class Preset(
      alpha: Double,
      fields: LexicalBlend.LexicalFields,
      perLevel: Int,
      lexicalOverlap: Boolean,
      orderingScale: Double
  )
  val frozen: Preset = Preset(0.8, LexicalBlend.LexicalFields.WithLemmas, 8, false, 1.5)

  def checked[A, E](v: Either[E, A]): A =
    v.fold(e => throw new AssertionError(e.toString), identity)
  private def str(v: String): Json = Json.fromString(v)
  private def number(v: Double): Json =
    require(v.isFinite)
    Json.fromDoubleOrNull(v)
  private def estimate(v: Estimate[Double]): Json = v match
    case Estimate.Observed(x, _) => Json.obj("kind" -> str("observed"), "value" -> number(x))
    case Estimate.Missing(r)     => Json.obj("kind" -> str("missing"), "reason" -> str(r.toString))
    case Estimate.Ineligible     => Json.obj("kind" -> str("ineligible"))

  def fixture: (RecallGraph[Checked], TimedSourceView.Built) =
    val transcript = checked(StorySource.fromText("hello hello world ghost"))
    val atlas = SurfaceAnalyzer.analyze(transcript)
    val units = Vector(
      RecallUnit(
        RecallUnitId.unsafe("parity-query"),
        0,
        SpanSet.one(TextSpan.unsafe(0, 5)),
        "hello",
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      ),
      RecallUnit(
        RecallUnitId.unsafe("parity-too-long"),
        1,
        SpanSet.one(TextSpan.unsafe(6, 23)),
        "hello world ghost",
        DiscourseFunction.EpisodicAssertion,
        ExpressedUncertainty.Unmarked,
        PropositionSketch.empty,
        None
      )
    )
    val recall = RecallGraph
      .validated(transcript, atlas, units, RecallRelations.empty)
      .fold(e => throw new AssertionError(e.toString), identity)
    val first = TimedSegment.Group(1, "group-alpha", Some("world"), Some("hello"))
    val second = TimedSegment.Group(2, "ghost")
    val texts =
      Vector("alpha", "beta", "world", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa")
    val segments = texts.zipWithIndex.map { (text, i) =>
      val embedded = i match
        case 0 => Some("hello ghost")
        case 1 => Some("warrior")
        case 2 => None
        case 8 => Some("")
        case 9 => Some("hello world ghost")
        case _ => Some("world")
      TimedSegment(
        i + 1,
        text,
        None,
        Some(if i < 2 then first else second),
        embedText = embedded,
        lexicalText = if i == 1 then Some("hello hello hello") else None
      )
    }
    recall -> checked(
      TimedSourceView.build(segments, WorldOrderInput.Unknown(WorldOrderAbsence.NotSupplied))
    )

  def channel(
      encoder: OnnxSentenceEmbedder,
      recall: RecallGraph[Checked],
      built: TimedSourceView.Built
  ): Channel =
    checked(BenchChannels.neural(encoder, recall.ordered, built.nodeTexts))

  def capture(root: Path, preset: Preset): Json =
    val (recall, built) = fixture
    val encoder = HistoricalParityFixture.open(root)
    try
      val base = channel(encoder, recall, built)
      val blended = LexicalBlend.blended(
        base.semantic,
        recall.ordered,
        built.view,
        built.lexicalTexts,
        preset.alpha,
        preset.fields
      )
      val candidates = CandidateGenerator(blended, preset.perLevel, preset.lexicalOverlap)
        .generate(recall.ordered, built.view)
      val costModel = DefaultLocalCostModel(semantic = blended)
      val config = RecallOrderControl.scaledConfig(preset.orderingScale)
      val run = checked(StageTrace.historical(recall, built, candidates, costModel, config))
      val evidence = run.evidence
      val result = run.result
      val historical = checked(GraphHsmm.infer(recall, built.view, candidates, costModel, config))
      require(result == historical, "precomputed and historical inference differ")
      val resultJson = checked(parse(checked(HsmmResultCodec.encode(result))))
      val pairs = recall.ordered.flatMap { unit =>
        built.view.nodes.map { node =>
          Json.obj(
            "unit" -> str(unit.id.value),
            "target" -> str(node.ref.key),
            "raw" -> estimate(base.semantic(unit, node)),
            "blended" -> estimate(blended(unit, node))
          )
        }
      }
      val render = (values: Vector[(SourceNodeRef, String)]) =>
        Json.fromValues(values.map { (ref, text) =>
          Json.obj("target" -> str(ref.key), "text" -> str(text))
        })
      Json.obj(
        "schema" -> str("historical-video-embedding-compatibility/v1"),
        "fixtureKind" -> str("fixed-lookup-onnx; no learned efficacy"),
        "renderPolicy" -> str("historical-unit-text-leaf-override-group-override-or-label/v1"),
        "metric" -> str(EmbeddingMetric.CosineDistance.tag),
        "preset" -> Json.obj(
          "alpha" -> number(preset.alpha),
          "fields" -> str(preset.fields.toString),
          "perLevel" -> Json.fromInt(preset.perLevel),
          "lexicalOverlap" -> Json.fromBoolean(preset.lexicalOverlap),
          "orderingScale" -> number(preset.orderingScale),
          "ladder" -> str("full")
        ),
        "queries" -> Json.fromValues(
          recall.ordered.map(u => Json.obj("unit" -> str(u.id.value), "text" -> str(u.text)))
        ),
        "embedded" -> render(built.nodeTexts),
        "lexical" -> render(built.lexicalTexts),
        "physicalDocument" -> str(built.document),
        "pairs" -> Json.fromValues(pairs),
        "channelIdentity" -> str(base.identityChecksum.hex),
        "provider" -> str(base.semanticIdentity.provider.render),
        "providerCalls" -> Json.fromInt(base.semanticIdentity.providerCalls),
        "configFingerprint" -> str(config.fingerprint.hex),
        "evidenceIdentity" -> str(evidence.identity.toString),
        "candidateProvenance" -> str(evidence.provenance.toString),
        "nominations" -> Json.fromValues(recall.ordered.map { u =>
          Json.obj(
            "unit" -> str(u.id.value),
            "abstained" -> Json.fromBoolean(candidates.abstained(u.id)),
            "values" -> Json.fromValues(candidates.set(u.id).nominations.map { n =>
              Json.obj(
                "ref" -> str(n.ref.key),
                "channel" -> str(n.channel),
                "rank" -> Json.fromInt(n.rank),
                "score" -> n.rawScore.fold(Json.Null)(number),
                "levelReceipt" -> n.receipt.fold(Json.Null)(str)
              )
            })
          )
        }),
        "admittedStates" -> Json.fromValues(
          evidence.states.map(s => Json.fromValues(s.map(x => str(x.key))))
        ),
        "result" -> resultJson
      )
    finally encoder.close()

  def main(args: Array[String]): Unit =
    require(args.length == 2, "repository-root output-json")
    val body = capture(Paths.get(args(0)), frozen).noSpaces
    val _ = Files.write(Paths.get(args(1)), (body + "\n").getBytes(StandardCharsets.UTF_8))
