package storymodel4s.codec

import io.circe.Json
import storymodel4s.align.*
import storymodel4s.core.*

/** A contextual, reproducible support query result, separate from the mapping record. */
object TemporalSupportCodecs:
  import MappingJson.*
  val SchemaVersion: String = "temporal-support/v0.1"

  private def selection(value: TemporalSupport.Selection): Json = value match
    case TemporalSupport.Selection.Part(bundle) => tagged("part", "bundle" -> str(bundle.hex))
    case TemporalSupport.Selection.Occurrence(mapping, occurrence) =>
      tagged("occurrence", "mapping" -> str(mapping.hex), "occurrence" -> str(occurrence.value))

  private def maybe[A](value: Option[A])(encode: A => Json): Json = value match
    case None    => tagged("absent")
    case Some(v) => tagged("present", "value" -> encode(v))

  private def geometry(value: PlaybackSupport): Json = obj(
    "axis" -> str(value.axis.value),
    "intervals" -> array(value.intervals.map(MappingSourceWire.interval)),
    "points" -> array(value.points.map(p => long(p.at))),
    "display_bounds" -> obj("minimum" -> long(value.bounds._1), "maximum" -> long(value.bounds._2))
  )

  def toJson(value: TemporalSupport): Json = obj(
    "schemaVersion" -> str(SchemaVersion),
    "derivation" -> str(TemporalSupport.Version),
    "source_digest" -> str(value.sourceDigest.hex),
    "target" -> str(value.target.key),
    "selection" -> selection(value.coordinate.selection),
    "coordinate" -> obj(
      "bundle" -> str(value.coordinate.bundle.hex),
      "axis" -> MappingSourceWire.axis(value.coordinate.axis),
      "occurrence" -> maybe(value.coordinate.occurrence)(s =>
        obj(
          "occurrence" -> str(s.occurrence.value),
          "source" -> MappingSourceWire.interval(s.source),
          "target" -> MappingSourceWire.interval(s.target)
        )
      )
    ),
    "nodes" -> array(value.nodes.map { n =>
      obj(
        "target" -> str(n.ref.key),
        "state" -> str(n.state.toString),
        "supplied_identity" -> maybe(n.suppliedIdentity)(h => str(h.hex)),
        "included" -> maybe(n.included)(geometry),
        "excluded" -> maybe(n.excluded)(geometry)
      )
    }),
    "unlocated_descendants" -> strings(value.unlocatedDescendants.map(_.key)),
    "unavailable_descendants" -> strings(value.unavailableDescendants.map(_.key))
  )
  def encode(value: TemporalSupport): String = print(toJson(value))

  /** Re-execute the query on the caller's checked source, then compare the entire record. */
  def decode(
      text: String,
      source: SourceRepresentation
  ): Either[MappingCodecError, TemporalSupport] =
    for
      json <- Canonical.parse(text).left.map(MappingCodecError.Wire(_))
      _ <- uniqueObjectKeys(text)
      target <- field[String](json, "target").flatMap(sourceRef)
      input <- field[Json](json, "selection")
      selected <- status(input).flatMap {
        case "part" =>
          field[String](input, "bundle")
            .flatMap(s => domain(Checksum.from(s)))
            .map(TemporalSupport.Selection.Part(_))
        case "occurrence" =>
          for
            mapping <- field[String](input, "mapping").flatMap(s => domain(Checksum.from(s)))
            occurrence <- field[String](input, "occurrence").flatMap(s =>
              domain(OccurrenceId.from(s))
            )
          yield TemporalSupport.Selection.Occurrence(mapping, occurrence)
        case _ => invalid("selection", "unknown coordinate selection")
      }
      result <- TemporalSupport
        .read(source, target, selected)
        .left
        .map(e => MappingCodecError.Wire(CodecError.Decode("temporal-support", e.toString)))
      _ <- exact(json, toJson(result), "canonical-record")
    yield result
