package storymodel4s.features

import munit.FunSuite
import storymodel4s.core.*
import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/** Emits the token-measure witness consumed by the Python lexical-ledger regression. */
class FeatureMeasureParityProbe extends FunSuite:
  private def fixture(path: String): (String, Vector[(String, Double)]) =
    val rows = Files.readAllLines(Path.of(path)).asScala.toVector.map(_.split("\\t", -1).toVector)
    val source = rows.collectFirst { case Vector("source", text) => text }.get
    val norms = rows.collect { case Vector("lexicon", form, value) => form -> value.toDouble }
    (source, norms)

  private val (sourceText, normRows) = fixture(sys.props("feature.measure.parity.fixture"))
  private val source = StorySource.fromText(sourceText).toOption.get
  private val atlas = SurfaceAnalyzer.analyze(source)
  private val sequence = SurfaceSequence(atlas)

  // The TSV fixture uses printable Unicode without JSON escape characters.
  private def quoted(value: String): String =
    require(value.forall(c => c >= ' ' && c != '"' && c != 92.toChar))
    "\"" + value + "\""

  private def render(track: FeatureTrack[FeatureTarget.Token, Double]): String =
    track.observations
      .map { observation =>
        quoted(observation.estimate match
          case Estimate.Observed(value, _) => s"observed:$value"
          case Estimate.Missing(reason)    => s"missing:$reason"
          case Estimate.Ineligible         => "ineligible")
      }
      .mkString("[", ",", "]")

  test("TokenTracks witness") {
    val lexicon = LexiconTable
      .of("probe", "synthetic zero and missing lexicon", Some("u"), normRows)
      .toOption
      .get
    val length = TokenTracks.measure(sequence, TokenLength).toOption.get
    val frequency = TokenTracks.measure(sequence, TypeFrequency).toOption.get
    val norms = TokenTracks.measure(sequence, new LexiconMeasure(lexicon)).toOption.get

    val entries = normRows.map((form, value) => s"${quoted(form)}:$value").mkString("{", ",", "}")
    val forms = sequence.tokens.map(t => quoted(atlas.text(t.unit))).mkString("[", ",", "]")
    val fields = Vector(
      "source" -> quoted(sourceText),
      "lexicon_input" -> entries,
      "tokens" -> forms,
      "length" -> render(length),
      "frequency" -> render(frequency),
      "lexicon" -> render(norms)
    )
    println(
      "FEATURE_MEASURE_PARITY=" + fields.map((k, v) => s"${quoted(k)}:$v").mkString("{", ",", "}")
    )

    assertEquals(length.coverage.eligible, 4)
    assertEquals(
      frequency.observations.take(4).flatMap(_.estimate.toOption),
      Vector(2.0, 2.0, 1.0, 1.0)
    )
    assertEquals(norms.observations.take(4).flatMap(_.estimate.toOption), Vector(0.0, 0.0, 2.0))
  }
