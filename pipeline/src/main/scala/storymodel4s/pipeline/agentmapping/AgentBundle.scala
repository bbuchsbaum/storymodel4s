package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.align.MappingResult
import storymodel4s.codec.{ExpectedMappingContext, MappingCodecs}
import storymodel4s.recall.RecallInventory

/** One participant's saved agent mapping: both interpretations as checked records, every unit's
  * full answer, provenance, and the source dictionary needed to resolve a cited row.
  *
  * Why a bundle: a mapping record alone cannot be read without regenerating its source and
  * inventory, and it has no place for self-reported alternatives, confidence or row citations.
  * The bundle keeps those as plain per-unit observations beside the records, so a reader with no
  * Scala process can inspect any unit, and a Scala reader can still re-check the records. It holds
  * film annotation text and unit ids, so it is written under the ignored data root.
  */
object AgentBundle:
  val Schema: String = "storymodel4s.agentmapping.bundle/v1"
  val Parser: String = "agent-answers/v1"

  import AgentAnswers.{Alternative, Field, Outcome}

  /** One unit as saved: its processing status and, when answered, each field as established. */
  final case class UnitRow(
      index: Int,
      unit: String,
      status: String,
      chunk: Int,
      attempts: Int,
      narrative: Field[Int],
      referent: Field[Int],
      alternatives: Field[Vector[Alternative]],
      rows: Field[Vector[Int]],
      confidence: Field[Double],
      responseKey: Option[String]
  )

  /** One source row a citation can resolve to. */
  final case class SourceRow(
      row: Int,
      target: String,
      scene: Option[Int],
      part: String,
      start: String,
      end: String,
      text: String
  )

  /** One scene: its number, target key, label and row range. */
  final case class SourceScene(scene: Int, target: String, label: String, first: Int, last: Int)

  final case class Provenance(
      participant: String,
      provider: String,
      model: String,
      systemSha256: String,
      outlineSha256: String,
      annotationSha256: String,
      recallSha256: String,
      chunk: Int
  )

  /** A unit row from a replay outcome. `key` gives the recording key of an accepted prompt. */
  def unitRow(index: Int, unit: String, outcome: Outcome, key: String => String): UnitRow =
    outcome match
      case Outcome.Answered(a) =>
        UnitRow(
          index,
          unit,
          "answered",
          a.chunk,
          a.attempt + 1,
          a.narrative,
          a.referent,
          a.alternatives,
          a.rows,
          a.confidence,
          Some(key(a.prompt))
        )
      case Outcome.Omitted(chunk, attempts) => blank(index, unit, "omitted", chunk, attempts)
      case Outcome.Unparsable(chunk, attempts) =>
        blank(index, unit, "unparsable", chunk, attempts)

  private def blank(index: Int, unit: String, status: String, chunk: Int, attempts: Int) =
    UnitRow(
      index,
      unit,
      status,
      chunk,
      attempts,
      Field.Absent,
      Field.Absent,
      Field.Absent,
      Field.Absent,
      Field.Absent,
      None
    )

  private def str(s: String) = Json.fromString(s)

  private def field[A](f: Field[A])(value: A => Json): Json = f match
    case Field.Absent     => Json.obj("status" -> str("absent"))
    case Field.Null       => Json.obj("status" -> str("null"))
    case Field.Value(v)   => Json.obj("status" -> str("value"), "value" -> value(v))
    case Field.Invalid(r) => Json.obj("status" -> str("invalid"), "raw" -> r)

  private def readField[A](j: Json)(value: Json => Option[A]): Option[Field[A]] =
    j.hcursor.get[String]("status").toOption.flatMap {
      case "absent"  => Some(Field.Absent)
      case "null"    => Some(Field.Null)
      case "value"   => j.hcursor.downField("value").focus.flatMap(value).map(Field.Value(_))
      case "invalid" => j.hcursor.downField("raw").focus.map(Field.Invalid(_))
      case _         => None
    }

  private def altJson(a: Alternative) = Json.arr(Json.fromInt(a.scene), a.p)
  private def altRead(j: Json): Option[Alternative] = j.asArray.collect {
    case Vector(s, p) if p.isNumber => s.asNumber.flatMap(_.toInt).map(Alternative(_, p))
  }.flatten
  private def all[A](xs: Vector[Option[A]]): Option[Vector[A]] =
    Option.when(xs.forall(_.isDefined))(xs.flatten)

  private def unitJson(u: UnitRow): Json = Json.obj(
    "index" -> Json.fromInt(u.index),
    "unit" -> str(u.unit),
    "processing" -> Json.obj(
      "status" -> str(u.status),
      "chunk" -> Json.fromInt(u.chunk),
      "attempts" -> Json.fromInt(u.attempts)
    ),
    "narrative" -> field(u.narrative)(Json.fromInt),
    "referent" -> field(u.referent)(Json.fromInt),
    "alternatives" -> field(u.alternatives)(xs => Json.fromValues(xs.map(altJson))),
    "rows" -> field(u.rows)(xs => Json.fromValues(xs.map(Json.fromInt))),
    "confidence" -> field(u.confidence)(Json.fromDoubleOrNull),
    "responseKey" -> u.responseKey.fold(Json.Null)(str)
  )

  private def unitRead(j: Json): Option[UnitRow] =
    val c = j.hcursor
    def f(key: String) = c.downField(key).focus
    for
      index <- c.get[Int]("index").toOption
      unit <- c.get[String]("unit").toOption
      status <- c.downField("processing").get[String]("status").toOption
      if Set("answered", "omitted", "unparsable")(status)
      chunk <- c.downField("processing").get[Int]("chunk").toOption
      attempts <- c.downField("processing").get[Int]("attempts").toOption
      narrative <- f("narrative").flatMap(readField(_)(_.asNumber.flatMap(_.toInt)))
      referent <- f("referent").flatMap(readField(_)(_.asNumber.flatMap(_.toInt)))
      alternatives <- f("alternatives").flatMap(
        readField(_)(_.asArray.flatMap(xs => all(xs.map(altRead))))
      )
      rows <- f("rows").flatMap(
        readField(_)(_.asArray.flatMap(xs => all(xs.map(_.asNumber.flatMap(_.toInt)))))
      )
      confidence <- f("confidence").flatMap(readField(_)(_.asNumber.map(_.toDouble)))
      key <- f("responseKey").map(_.asString)
    yield UnitRow(
      index,
      unit,
      status,
      chunk,
      attempts,
      narrative,
      referent,
      alternatives,
      rows,
      confidence,
      key
    )

  /** The bundle document. The records are embedded as their codec JSON, unchanged. */
  def encode(
      provenance: Provenance,
      narrative: MappingResult,
      referent: MappingResult,
      units: Vector[UnitRow],
      scenes: Vector[SourceScene],
      rows: Vector[SourceRow]
  ): Json =
    def record(interpretation: AgentMappingRecord.Interpretation, value: MappingResult) =
      Json.obj(
        "policy" -> str(interpretation.policy.value),
        "recordDigest" -> str(value.digest.hex),
        "record" -> MappingCodecs.toJson(value)
      )
    Json.obj(
      "schema" -> str(Schema),
      "provenance" -> Json.obj(
        "participant" -> str(provenance.participant),
        "provider" -> str(provenance.provider),
        "model" -> str(provenance.model),
        "systemSha256" -> str(provenance.systemSha256),
        "outlineSha256" -> str(provenance.outlineSha256),
        "annotationSha256" -> str(provenance.annotationSha256),
        "recallSha256" -> str(provenance.recallSha256),
        "chunk" -> Json.fromInt(provenance.chunk),
        "parser" -> str(Parser),
        "modelCalls" -> Json.fromInt(0)
      ),
      "meaning" -> Json.obj(
        "narrative" -> str("the scene the retelling is narrating at this unit"),
        "referent" -> str("the scene containing the specifically mentioned content"),
        "alternatives" -> str(
          "self-reported narrative alternatives [scene, p]; not a distribution, not calibrated"
        ),
        "confidence" -> str("self-reported; absent means unavailable, never 0"),
        "rows" -> str(
          "rows cited for the unit; the reply does not say which interpretation they support, " +
            "and a resolvable citation is not evidence that the row supports the claim"
        )
      ),
      "interpretations" -> Json.obj(
        "narrative" -> record(AgentMappingRecord.Interpretation.Narrative, narrative),
        "referent" -> record(AgentMappingRecord.Interpretation.Referent, referent)
      ),
      "units" -> Json.fromValues(units.map(unitJson)),
      "source" -> Json.obj(
        "scenes" -> Json.fromValues(scenes.map { s =>
          Json.obj(
            "scene" -> Json.fromInt(s.scene),
            "target" -> str(s.target),
            "label" -> str(s.label),
            "firstRow" -> Json.fromInt(s.first),
            "lastRow" -> Json.fromInt(s.last)
          )
        }),
        "rows" -> Json.fromValues(rows.map { r =>
          Json.obj(
            "row" -> Json.fromInt(r.row),
            "target" -> str(r.target),
            "scene" -> r.scene.fold(Json.Null)(Json.fromInt),
            "part" -> str(r.part),
            "start" -> str(r.start),
            "end" -> str(r.end),
            "text" -> str(r.text)
          )
        })
      )
    )

  /** What a Scala reader recovers: both records, re-checked, and the unit rows. */
  final case class Loaded(narrative: MappingResult, referent: MappingResult, units: Vector[UnitRow])

  /** Read a bundle against a regenerated inventory and source. The embedded records are decoded
    * through the mapping codec, which re-derives and compares them; nothing is trusted as written.
    */
  def decode(
      bundle: Json,
      inventory: RecallInventory,
      film: AgentMappingRecord.Film
  ): Either[String, Loaded] =
    val c = bundle.hcursor
    val context = ExpectedMappingContext(inventory, film.view.representation)
    def record(name: String): Either[String, MappingResult] =
      c.downField("interpretations")
        .downField(name)
        .downField("record")
        .focus
        .toRight(s"no $name record")
        .flatMap(j => MappingCodecs.decode(j.noSpaces, context).left.map(e => s"$name: ${e.message}"))
    for
      _ <- Either.cond(c.get[String]("schema").contains(Schema), (), "not an agent mapping bundle")
      narrative <- record("narrative")
      referent <- record("referent")
      raw <- c.get[Vector[Json]]("units").left.map(_ => "no units")
      units <- all(raw.map(unitRead)).toRight("a unit row is malformed")
      _ <- Either.cond(
        units.map(_.unit) == inventory.units.map(_.id.value),
        (),
        "unit rows do not match the inventory"
      )
    yield Loaded(narrative, referent, units)
