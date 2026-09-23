package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.MappingResult
import storymodel4s.core.Checksum

/** Lossless analysis files derived from one checked mapping. No inference runs here. */
object MappingExchange:
  val SchemaVersion: String = "mapping-exchange/v0.1"

  enum Error:
    case DuplicateFile(name: String)
    case MissingFile(name: String)
    case InvalidManifest
    case InvalidUtf16(file: String)
    case ContentMismatch(file: String)
    case Mapping(error: MappingCodecError)

  /** Names are fixed by this schema; contents encode as UTF-8 without a BOM. */
  final class Bundle private[MappingExchange] (val files: Vector[(String, String)]):
    def manifest: String = files.head._2
    def digest: Checksum = Checksum.ofText(manifest)

  private enum CellType(val wire: String):
    case Text extends CellType("utf8-string")
    case Integer extends CellType("decimal-integer")
    case Bits extends CellType("ieee754-binary64-hex")
    case Structured extends CellType("canonical-json")

  private final case class Column(name: String, kind: CellType)
  private final case class Table(
      name: String,
      columns: Vector[Column],
      rows: Vector[Vector[String]]
  ):
    def text: String =
      (columns.map(_.name) +: rows).map(_.map(quote).mkString("\t")).mkString("", "\n", "\n")
    def schema: Json = Json.fromValues(
      columns.map(c =>
        Json.obj(
          "name" -> Json.fromString(c.name),
          "type" -> Json.fromString(c.kind.wire)
        )
      )
    )

  private def quote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
  private def field(value: Json, name: String): Json = value.asObject.get(name).get
  private def rows(value: Json, name: String): Vector[Json] = field(value, name).asArray.get
  private def cell(value: Json, kind: CellType): String = kind match
    case CellType.Text | CellType.Bits => value.asString.get
    case CellType.Integer              => value.asNumber.get.toString
    case CellType.Structured           => MappingJson.print(value)

  private def validUtf16(text: String): Boolean =
    var index = 0
    var valid = true
    while index < text.length && valid do
      val c = text.charAt(index)
      if c >= '\ud800' && c <= '\udbff' then
        valid = index + 1 < text.length && text.charAt(index + 1) >= '\udc00' && text.charAt(
          index + 1
        ) <= '\udfff'
        index += 2
      else
        valid = !(c >= '\udc00' && c <= '\udfff')
        index += 1
    valid

  /** The canonical record remains authoritative; tables are checked projections of its fields. */
  def encode(record: MappingResult): Either[Error, Bundle] =
    import CellType.*
    val json = MappingCodecs.toJson(record)
    val inventory = field(json, "inventory")
    val source = field(json, "source")
    val outcomes = rows(json, "outcomes")
    val digest = record.digest.hex
    def table(name: String, entries: Vector[Json], columns: (String, CellType)*): Table =
      val cs = columns.toVector.map(Column.apply.tupled)
      Table(
        name + ".tsv",
        Column("mapping_digest", Text) +: cs,
        entries.map(row => digest +: cs.map(c => cell(field(row, c.name), c.kind)))
      )
    def nested(name: String): Vector[Json] = outcomes.flatMap { outcome =>
      rows(outcome, name).zipWithIndex.map { (row, index) =>
        row.mapObject(_.add("unit", field(outcome, "unit")).add("row_index", Json.fromInt(index)))
      }
    }
    val tables = Vector(
      table(
        "words",
        rows(inventory, "words"),
        "id" -> Text,
        "index" -> Integer,
        "start" -> Integer,
        "end_exclusive" -> Integer,
        "membership" -> Structured
      ),
      table(
        "units",
        rows(inventory, "units"),
        "id" -> Text,
        "ordinal" -> Integer,
        "spans" -> Structured,
        "words" -> Structured,
        "decomposition" -> Structured,
        "semantics" -> Text
      ),
      table(
        "targets",
        rows(source, "targets"),
        "target_id" -> Text,
        "level" -> Integer,
        "parent" -> Structured,
        "axis_membership" -> Structured,
        "support_coverage" -> Structured,
        "propositional_scope" -> Structured
      ),
      table(
        "target-support",
        rows(source, "targets"),
        "target_id" -> Text,
        "support_status" -> Structured
      ),
      table(
        "alternatives",
        nested("mapping_links"),
        "unit" -> Text,
        "row_index" -> Integer,
        "measure_kind" -> Text,
        "channel" -> Structured,
        "destination" -> Text,
        "raw_value" -> Bits,
        "normalization_scope" -> Text,
        "state" -> Structured
      ),
      table("measure-metadata", outcomes, "unit" -> Text, "measures" -> Structured),
      table(
        "decisions",
        outcomes,
        "unit" -> Text,
        "processing_status" -> Structured,
        "localization_status" -> Text,
        "fidelity_assessment_status" -> Structured,
        "decision" -> Structured,
        "stages" -> Structured
      ),
      table(
        "links",
        nested("links"),
        "unit" -> Text,
        "row_index" -> Integer,
        "destination" -> Text,
        "inference_stage_id" -> Text,
        "candidate_set_id" -> Text,
        "derivation" -> Structured,
        "gate_outcome" -> Structured,
        "fidelity_status" -> Structured,
        "fidelity_facets" -> Structured,
        "term_support" -> Structured
      ),
      table(
        "stages",
        rows(json, "stage_assumption_receipts"),
        "id" -> Text,
        "stage" -> Text,
        "provenance" -> Structured
      )
    )
    val contents =
      Vector("mapping.json" -> MappingCodecs.encode(record)) ++ tables.map(t => t.name -> t.text)
    contents.find((_, text) => !validUtf16(text)) match
      case Some((name, _)) => Left(Error.InvalidUtf16(name))
      case None            =>
        def unavailable(reason: String): Json =
          MappingJson.tagged("unavailable", "reason" -> Json.fromString(reason))
        val files = contents.map { (name, text) =>
          Json.obj(
            "name" -> Json.fromString(name),
            "sha256" -> Json.fromString(Checksum.ofText(text).hex),
            "bytes" -> Json.fromString(text.getBytes("UTF-8").length.toString),
            "format" -> Json.fromString(
              if name == "mapping.json" then "mapping-record/v0.1" else "quoted-tsv/v1"
            ),
            "table" -> tables.find(_.name == name).fold(MappingJson.tagged("not-applicable")) { t =>
              MappingJson.tagged(
                "present",
                "columns" -> t.schema,
                "rows" -> Json.fromString(t.rows.size.toString)
              )
            }
          )
        }
        val manifest = MappingJson.print(
          Json.obj(
            "schemaVersion" -> Json.fromString(SchemaVersion),
            "mapping_digest" -> Json.fromString(digest),
            "inventory_digest" -> field(inventory, "digest"),
            "source_digest" -> field(source, "digest"),
            "transcript_checksum" -> field(inventory, "transcript_checksum"),
            "segmentation_id" -> field(inventory, "segmentation_id"),
            "policies" -> field(json, "policies"),
            "roles" -> field(json, "roles"),
            "capabilities" -> Json.obj(
              "mapping" -> MappingJson.tagged("present"),
              "recall_timing" -> unavailable("not-supplied-to-base-exchange"),
              "temporal_projection" -> unavailable("not-supplied-to-base-exchange"),
              "scanner_alignment" -> unavailable("not-supplied-to-base-exchange"),
              "organization" -> unavailable("not-supplied-to-base-exchange"),
              "calibration" -> unavailable("not-admitted-by-mapping-record/v0.1"),
              "paired_profiles" -> unavailable("single-record-component")
            ),
            "files" -> Json.fromValues(files)
          )
        )
        Right(new Bundle(("manifest.json" -> manifest) +: contents))

  /** Admission needs the same checked context as mapping-record; matching hashes alone is weaker.
    */
  def decode(
      files: Vector[(String, String)],
      expected: ExpectedMappingContext
  ): Either[Error, MappingResult] =
    for
      _ <- files
        .groupBy(_._1)
        .collectFirst { case (name, values) if values.size != 1 => name }
        .toLeft(())
        .left
        .map(Error.DuplicateFile(_))
      supplied = files.toMap
      manifest <- supplied.get("manifest.json").toRight(Error.MissingFile("manifest.json"))
      parsed <- Canonical.parse(manifest).left.map(_ => Error.InvalidManifest)
      _ <- MappingJson.uniqueObjectKeys(manifest).left.map(_ => Error.InvalidManifest)
      _ <- Either.cond(
        parsed.hcursor.get[String]("schemaVersion").contains(SchemaVersion),
        (),
        Error.InvalidManifest
      )
      mapping <- supplied.get("mapping.json").toRight(Error.MissingFile("mapping.json"))
      record <- MappingCodecs.decode(mapping, expected).left.map(Error.Mapping(_))
      rebuilt <- encode(record)
      _ <- Either.cond(
        supplied.keySet == rebuilt.files.map(_._1).toSet,
        (),
        Error.ContentMismatch("file-set")
      )
      _ <- rebuilt.files.traverse_ { (name, text) =>
        Either.cond(supplied(name) == text, (), Error.ContentMismatch(name))
      }
    yield record
