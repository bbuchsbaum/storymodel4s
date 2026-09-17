package storymodel4s.corpus.intake

import io.circe.{Json, JsonObject}
import storymodel4s.corpus.*

/** The corpus descriptor: what a run emits so that downstream tools stop carrying corpus constants.
  *
  * This is the consumer that earns [[Capability]] its place. A derived capability set with nothing
  * reading it would be the same defect as a source manifest no code reads -- a record that asserts
  * authority and is wired to nothing -- which is the whole subject of ADR 0018.
  *
  * The in-tree precedent is `tools/recall-study/agreement.py:31-45`, which already reads
  * `parts.json` from disk and falls back to a literal only when it is absent. That is the only
  * data-driven seam in the scoring tree, and it belongs to the judge of record. This generalizes it
  * rather than inventing a mechanism.
  *
  * It is CONTENT-FREE by construction: counts, ranges, encodings and checksums. No column value is
  * ever written, because a descriptor travels outside the data root.
  */
object Descriptor:
  val Schema: String = "storymodel4s.corpus.descriptor"
  val SchemaVersion: Int = 1

  /** Everything derivable from a reading. Nothing semantic, because a reading cannot know which
    * column is a thread label or which is gold; those need a declaration this layer lacks, and are
    * absent rather than guessed.
    */
  def capabilities(
      profile: CorpusProfile,
      opened: CorpusReader.OpenCorpus
  ): Vector[Capability] =
    val clocks = profile.sheets.values
      .flatMap(_.columns.values.map(_.encoding))
      .filter(Capability.clockEncodings.contains)
      .toVector
      .distinct
      .map(Capability.StimulusClock.apply)
    val rows = opened.sheets.toVector
      .sortBy((k, _) => (k._1.value, k._2))
      .map((k, s) => Capability.StimulusRows(k._2, s.rows.size))
    val ordinals = opened.sheets.toVector
      .sortBy((k, _) => (k._1.value, k._2))
      .flatMap { case ((artifact, sheet), open) =>
        profile
          .binding(artifact, sheet)
          .toVector
          .flatMap(_.columns.collect {
            case (name, b) if b.encoding == CellEncoding.IntegerOrWholeDecimalText =>
              val distinct = open.rows
                .flatMap(_.context.valueOf(name) match
                  case ColumnValue.Value(v) => Some(v)
                  case _                    => None)
                .distinct
                .size
              Capability.OrdinalColumn(sheet, name, distinct)
          })
      }
    (clocks ++ rows ++ ordinals).sortBy(_.render)

  def json(
      verified: Verified,
      profile: CorpusProfile,
      opened: CorpusReader.OpenCorpus
  ): Json =
    Json.fromJsonObject(
      JsonObject(
        "schema" -> Json.fromString(Schema),
        "schemaVersion" -> Json.fromInt(SchemaVersion),
        "corpus" -> Json.fromString(verified.manifest.corpus.value),
        "admission" -> Json.fromString(verified.manifest.admission.state.render),
        "profile" -> Json.fromJsonObject(
          JsonObject(
            "id" -> Json.fromString(profile.id.value),
            "version" -> Json.fromInt(profile.version),
            "identity" -> Json.fromString(profile.identity.hex)
          )
        ),
        "artifacts" -> Json.fromValues(
          verified.artifacts.sortBy(_.id.value).map { a =>
            Json.fromJsonObject(
              JsonObject(
                "id" -> Json.fromString(a.id.value),
                "byteLength" -> Json.fromInt(a.byteLength),
                "sha256" -> Json.fromString(a.checksum.hex)
              )
            )
          }
        ),
        "capabilities" -> Json.fromValues(
          capabilities(profile, opened).map(c => Json.fromString(c.render))
        )
      )
    )
