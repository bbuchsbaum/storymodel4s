package storymodel4s.codec

import io.circe.{Decoder, Json}
import io.circe.syntax.*
import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

import CanonicalPrimitives.given
import VoyageCodecs.given

/** Unchecked packaging input. Word observations are independent of WorkspaceTiming annotations. The
  * source timeline is a declared presentation, not a film/media correspondence certificate.
  */
final case class WorkspaceClockInput(
    words: RecallTiming,
    sourceTimeline: SourceTimeline,
    recallExtent: Seconds,
    declaration: Checksum
)

/** Optional clock-sidecar admission. Its complete fixed source dictionary never depends on which
  * rows the legacy Voyage adapter can display. Exact rational word values stay on the wire.
  */
object WorkspaceClocksCodec:
  val SchemaVersion = "workspace-presentation-clocks/v0.1"
  private type Result[A] = Either[WorkspaceRefusal, A]
  final class Checked private[WorkspaceClocksCodec] (
      val words: RecallTiming,
      val sourceTimeline: SourceTimeline,
      val recallExtent: Seconds,
      val declaration: Checksum,
      val json: Json
  )

  private def str(value: String): Json = Json.fromString(value)
  private def field[A: Decoder](json: Json, name: String): Result[A] =
    json.hcursor.get[A](name).left.map(_ => WorkspaceRefusal.UnsupportedContent)
  private def admitted[E, A](value: Either[E, A]): Result[A] =
    value.left.map(_ => WorkspaceRefusal.SemanticJoinMismatch)

  private[codec] def toJson(
      input: WorkspaceClockInput,
      model: Checksum,
      recall: Checksum,
      inventory: RecallInventory,
      source: SourceRepresentation
  ): Json = Json.obj(
    "schemaVersion" -> str(SchemaVersion),
    "model_artifact" -> model.asJson,
    "recall_artifact" -> recall.asJson,
    "inventory_digest" -> inventory.digest.asJson,
    "source_representation" -> source.digest.asJson,
    "source_semantics" -> str("declared-presentation/closed-seconds/v1"),
    "recording_correspondence" -> str("unestablished"),
    "declaration" -> input.declaration.asJson,
    "word_timing" -> RecallTimingCodecs.toJson(input.words),
    "source_timeline" -> input.sourceTimeline.asJson,
    "recall_extent" -> input.recallExtent.asJson
  )

  private[codec] def decode(
      text: String,
      model: Checksum,
      recall: Checksum,
      inventory: RecallInventory,
      source: SourceRepresentation,
      cut: DeclaredUniverse
  ): Result[Checked] =
    for
      json <- admitted(Canonical.parse(text))
      _ <- admitted(MappingJson.uniqueObjectKeys(text))
      version <- field[String](json, "schemaVersion")
      _ <- Either.cond(version == SchemaVersion, (), WorkspaceRefusal.UnsupportedVersion)
      wordJson <- field[Json](json, "word_timing")
      words <- admitted(RecallTimingCodecs.decode(MappingJson.print(wordJson), inventory))
      timeline <- field[SourceTimeline](json, "source_timeline")
      extent <- field[Seconds](json, "recall_extent")
      declaration <- field[Checksum](json, "declaration")
      _ <- Either.cond(
        timeline.groups.isEmpty && timeline.nodes.forall(n =>
          n.group.isEmpty &&
            source.target(n.ref).exists(_.level == n.level)
        ) &&
          timeline.nodes.map(_.ref).toSet == cut.targets.toSet,
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
      // Compare rational observations with the exact binary extent, never rounded word values.
      _ <- Either.cond(
        words.entries.forall(e =>
          e.observation match
            case RecallTiming.Observation.Missing(_)              => true
            case RecallTiming.Observation.OnsetOnly(at, _)        => within(at, extent.value)
            case RecallTiming.Observation.Interval(start, end, _) =>
              within(start, extent.value) && within(end, extent.value)
        ),
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
      expected = toJson(
        WorkspaceClockInput(words, timeline, extent, declaration),
        model,
        recall,
        inventory,
        source
      )
      _ <- Either.cond(
        MappingJson.print(json) == MappingJson.print(expected),
        (),
        WorkspaceRefusal.SemanticJoinMismatch
      )
    yield new Checked(words, timeline, extent, declaration, expected)

  /** Exact rational represented by a finite nonnegative binary double. */
  private def binary(value: Double): (BigInt, BigInt) =
    val bits = java.lang.Double.doubleToLongBits(value)
    val exponent = ((bits >>> 52) & 0x7ffL).toInt
    val fraction = BigInt(bits & 0xfffffffffffffL)
    val significand = if exponent == 0 then fraction else fraction + (BigInt(1) << 52)
    val shift = (if exponent == 0 then 1 else exponent) - 1023 - 52
    if shift >= 0 then (significand << shift, BigInt(1)) else (significand, BigInt(1) << -shift)
  private def within(value: ExactRational, maximum: Double): Boolean =
    val (n, d) = binary(maximum)
    BigInt(value.numerator) * d <= n * BigInt(value.denominator)

  /** The legacy view cannot honestly carry a rounded clock. Refuse that projection, retaining the
    * exact observation in the workspace. In particular 1/10 is not silently changed to binary 0.1.
    */
  private[codec] def legacySeconds(value: ExactRational): Option[Seconds] =
    Seconds.of(value.numerator.toDouble / value.denominator.toDouble).toOption.filter { candidate =>
      val (n, d) = binary(candidate.value)
      n * BigInt(value.denominator) == BigInt(value.numerator) * d
    }
