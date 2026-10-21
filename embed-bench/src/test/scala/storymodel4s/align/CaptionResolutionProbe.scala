package storymodel4s.align

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import io.circe.Json
import io.circe.parser.parse
import storymodel4s.codec.BoundCaptionSourceCodec
import storymodel4s.codec.RecallCodecs.given
import storymodel4s.core.Checksum
import storymodel4s.features.Estimate
import storymodel4s.recall.{RecallGraph, RecallGraphStatus}

/** Price a declared partial caption intervention with the production closed scorer, without
  * manufacturing a homogeneous caption package or claiming nomination/normalization execution.
  */
object CaptionResolutionProbe:
  private def read(path: Path): String = Files.readString(path, UTF_8)
  private def json(path: Path): Json = parse(read(path)).fold(throw _, identity)

  private[align] def replacements(
      input: Json,
      allowedRows: Set[Int]
  ): Map[Int, (String, String)] =
    val rows = input.hcursor.get[Vector[Json]]("rows").fold(throw _, identity).map { value =>
      val c = value.hcursor
      val row = c.get[Int]("row").fold(throw _, identity)
      val low = c.get[String]("low").fold(throw _, identity)
      val high = c.get[String]("high").fold(throw _, identity)
      require(allowedRows(row), "replacement has no source target")
      require(low.trim.nonEmpty && high.trim.nonEmpty, "empty replacement caption")
      row -> (low, high)
    }
    require(rows.map(_._1).distinct.size == rows.size, "duplicate replacement target")
    rows.toMap

  private def observed(value: Estimate[Double]): Option[Double] =
    val result = value.toOption
    require(result.forall(x => x.isFinite && x >= 0.0 && x <= 1.0), "invalid score")
    result

  def main(args: Array[String]): Unit =
    require(args.length == 4, "joined-source.json recall-export.json replacements.json output-dir")
    val sourcePath = Paths.get(args(0))
    val recallPath = Paths.get(args(1))
    val interventionPath = Paths.get(args(2))
    val output = Paths.get(args(3))
    require(!Files.exists(output), "output already exists")
    val source = BoundCaptionSourceCodec
      .decode(read(sourcePath))
      .fold(
        e => throw new IllegalArgumentException(e),
        identity
      )
    val recall = json(recallPath).hcursor
      .get[RecallGraph[RecallGraphStatus.Checked]]("graph")
      .fold(throw _, identity)
    val nodes = source.view.nodes.filter(_.level == 0).sortBy(_.ref.key)
    val rowPattern = ".*:row:([0-9]+)".r
    def rowOf(node: NodeSummary): Int = node.ref.key match
      case rowPattern(number) => number.toInt
      case _                  => throw new IllegalArgumentException("target has no row coordinate")
    val changed = replacements(json(interventionPath), nodes.map(rowOf).toSet)
    require(changed.size == 32, "expected frozen 32-target intervention")
    require(nodes.size == 1000 && recall.units.size == 173, "unexpected frozen population")
    val scoring = CaptionScoring
      .multipart(1.0, source.media.evidence, source.identity)
      .flatMap(_.session(recall, source.view, source.view.representation))
      .fold(e => throw new IllegalArgumentException(e.message), identity)
    val targets = nodes.map { node =>
      node -> ContentProjection
        .canonicalTarget(node, source.view)
        .fold(
          e => throw new IllegalArgumentException(e.toString),
          identity
        )
    }
    Files.createDirectory(output)
    val writer = Files.newBufferedWriter(
      output.resolve("scores.tsv"),
      UTF_8,
      StandardOpenOption.CREATE_NEW
    )
    var measured = 0L
    var baselineWitnesses = 0L
    try
      writer.write("unit\trow\ttarget\tlow_v\thigh_v\tlow_av\thigh_av\n")
      recall.ordered.foreach { unit =>
        val u = ContentProjection
          .canonicalUnit(unit)
          .fold(
            e => throw new IllegalArgumentException(e.toString),
            identity
          )
        targets.foreach { (node, t) =>
          val low = scoring
            .evaluate(unit, node, u, t)
            .fold(
              e => throw new IllegalArgumentException(e.toString),
              observed
            )
          val annotation = observed(ContentLexical.score(u, t)).get
          val row = rowOf(node)
          val high = changed.get(row) match
            case None                        => low
            case Some((freshLow, freshHigh)) =>
              val witness = observed(CaptionScoring.score(u, t, freshLow, 1.0))
              require(witness == low, "fresh low caption differs from the bound baseline price")
              baselineWitnesses += 1
              observed(CaptionScoring.score(u, t, freshHigh, 1.0))
          def show(value: Option[Double]): String = value.fold("missing")(_.toString)
          val lowAV = low.map(v => 0.5 * annotation + 0.5 * v)
          val highAV = changed.get(row) match
            case None            => lowAV
            case Some((_, text)) => observed(CaptionScoring.score(u, t, text, 0.5))
          measured += low.size
          writer.write(
            s"${unit.ordinal}\t$row\t${node.ref.key}\t${show(low)}\t${show(high)}\t${show(lowAV)}\t${show(highAV)}\n"
          )
        }
      }
    finally writer.close()
    require(baselineWitnesses == 32L * 173L, "incomplete baseline comparison")
    val receipt = Json.obj(
      "scope" -> Json.fromString(
        "Full-cut closed-score diagnostic; partial32 intervention; not a new admitted package, nomination run or normalized mapping publication."
      ),
      "units" -> Json.fromInt(recall.units.size),
      "targets" -> Json.fromInt(nodes.size),
      "replacedTargets" -> Json.fromInt(changed.size),
      "measuredBaselinePairs" -> Json.fromLong(measured),
      "baselineWitnesses" -> Json.fromLong(baselineWitnesses),
      "inputs" -> Json.obj(Vector(sourcePath, recallPath, interventionPath).map { path =>
        path.toString -> Json.fromString(Checksum.ofBytes(Files.readAllBytes(path)).hex)
      }*)
    )
    val _ = Files.writeString(output.resolve("receipt.json"), receipt.spaces2 + "\n", UTF_8)
    println(s"Measured $measured baseline pairs; verified $baselineWitnesses fresh-baseline prices")

class CaptionResolutionProbeSuite extends munit.FunSuite:
  private def row(id: Int, low: String = "token-a", high: String = "token-b"): Json = Json.obj(
    "row" -> Json.fromInt(id),
    "low" -> Json.fromString(low),
    "high" -> Json.fromString(high)
  )
  private def input(rows: Json*): Json = Json.obj("rows" -> Json.fromValues(rows))

  test("partial intervention retains exactly its declared existing target") {
    assertEquals(
      CaptionResolutionProbe.replacements(input(row(2)), Set(1, 2)),
      Map(2 -> ("token-a", "token-b"))
    )
  }
  test("duplicate target cannot silently overwrite an earlier intervention") {
    intercept[IllegalArgumentException] {
      CaptionResolutionProbe.replacements(input(row(2), row(2, high = "token-c")), Set(2))
    }
  }
  test("foreign target refuses instead of expanding the comparison gallery") {
    intercept[IllegalArgumentException] {
      CaptionResolutionProbe.replacements(input(row(3)), Set(1, 2))
    }
  }
  test("empty caption is not a priced replacement") {
    intercept[IllegalArgumentException] {
      CaptionResolutionProbe.replacements(input(row(2, high = " ")), Set(2))
    }
  }
