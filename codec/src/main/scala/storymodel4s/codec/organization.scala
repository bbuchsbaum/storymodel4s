package storymodel4s.codec

import io.circe.Json
import storymodel4s.align.*
import storymodel4s.features.CanonicalDouble

/** Conditional organization data remains distinct from a producer's execution certificate. */
object OrganizationCodecs:
  val SchemaVersion: String = "organization-readout/v1"
  private def number(value: Double): Json = Json.fromString(CanonicalDouble.render(value))
  private def counts(values: Map[OrganizationReadout.Category, Double]): Json = Json.obj(
    OrganizationReadout.Category.values.toVector.map(c => c.toString -> number(values(c)))*
  )
  def toJson(result: OrganizationReadout.Result): Json =
    import OrganizationReadout.*
    Json.obj(
      "schemaVersion" -> Json.fromString(SchemaVersion),
      "interpretation" -> Json.fromString("conditional mass counts; uncalibrated"),
      "dependence" -> Json.fromString(result.dependence match
        case Dependence.DeclaredIndependentRows => "DeclaredIndependentRows"
        case _                                  => "AdjacentJoints"),
      "order" -> Json.fromValues(result.order.positions.toVector.sortBy(_._1.key).map { (ref, p) =>
        Json.obj("target" -> Json.fromString(ref.key), "position" -> number(p))
      }),
      "orderDigest" -> Json.fromString(result.order.digest.hex),
      "axis" -> Json.fromString(result.order.axis.toString),
      "orderKind" -> Json.fromString(result.order.source match
        case None => "DeclaredPositions"
        case Some(_) if result.order.axis == MeasurementCompatibility.Axis.StoryWorld =>
          "StoryWorldRanks"
        case Some(s) if s.bundles.toVector.forall(_.isInstanceOf[BundleEntry.TextSource]) =>
          "TextDiscourseRanks"
        case _ => "MediaPartDiscourse"),
      "source" -> result.order.source.fold(Json.Null)(s => Json.fromString(s.digest.hex)),
      "rows" -> Json.fromValues(result.rows.map { row =>
        Json.obj(
          "unit" -> Json.fromString(row.unit.value),
          "status" -> Json.fromString(row.status.toString),
          "mass" -> Json.fromValues(row.mass.toVector.sortBy(_._1.key).map { (d, p) =>
            Json.obj("destination" -> Json.fromString(d.key), "mass" -> number(p))
          }),
          "admissible" -> Json.fromValues(row.admissible.map(d => Json.fromString(d.key)))
        )
      }),
      "transitions" -> Json.fromValues(
        result.transitions.map(t =>
          Json.obj(
            "left" -> Json.fromString(t.left.value),
            "right" -> Json.fromString(t.right.value),
            "counts" -> counts(t.counts)
          )
        )
      ),
      "counts" -> counts(result.counts),
      "comparable" -> number(result.comparable),
      "backwardRate" -> result.backwardRate.fold(Json.Null)(number),
      "denominator" -> Json.fromString("expected comparable count; ratio of expected counts"),
      "units" -> Json.fromInt(result.rows.size),
      "measuredUnits" -> Json.fromInt(result.measuredUnits),
      "adjacencies" -> Json.fromInt(result.transitions.size),
      "measuredTransitions" -> Json.fromInt(result.measuredTransitions),
      "information" -> Json.fromString(result.information.toString),
      "bounds" -> result.bounds.fold(Json.Null)(bs =>
        Json.obj(Category.values.toVector.map { c =>
          c.toString -> Json
            .obj("minimum" -> Json.fromInt(bs(c).minimum), "maximum" -> Json.fromInt(bs(c).maximum))
        }*)
      ),
      "boundsInterpretation" -> Json.fromString(
        "conditional on explicit admissible sets; not confidence intervals"
      )
    )
  def encode(result: OrganizationReadout.Result): String = Canonical.print(toJson(result))

  /** Contextual readback checks every retained value; serialized labels grant no authority. */
  def decode(
      text: String,
      expected: OrganizationReadout.Result
  ): Either[String, OrganizationReadout.Result] =
    val parser = new io.circe.jawn.JawnParser(maxValueSize = None, allowDuplicateKeys = false)
    parser.parse(text).left.map(_ => "invalid organization JSON").flatMap { json =>
      Either.cond(json == toJson(expected), expected, "organization context or contents differ")
    }

  /** Quoted TSV exposes original adjacencies and all mutually exclusive accounting categories. */
  def transitions(result: OrganizationReadout.Result): String =
    def quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
    val categories = OrganizationReadout.Category.values.toVector
    val header = Vector("left_unit", "right_unit") ++ categories.map(_.toString)
    val rows = result.transitions.map(t =>
      Vector(t.left.value, t.right.value) ++ categories.map(c =>
        CanonicalDouble.render(t.counts(c))
      )
    )
    (header +: rows).map(_.map(quote).mkString("\t")).mkString("", "\n", "\n")
