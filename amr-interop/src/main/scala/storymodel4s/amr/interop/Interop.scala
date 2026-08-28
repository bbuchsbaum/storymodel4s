package storymodel4s.amr.interop

import cats.data.NonEmptyVector
import storymodel4s.amr.graph.*
import storymodel4s.amr.schema.FunctionalTag
import storymodel4s.proposition as p

/** Why a conversion between an AMR graph and a [[p.PropositionChart]] failed or would lose meaning.
  *
  * Conversions never throw: malformed PENMAN, structural law failures on either side, identifier
  * mismatches, and lossy mappings all surface here as data.
  */
enum InteropError:
  /** PENMAN text could not be parsed or decoded. */
  case Malformed(detail: String)

  /** The AMR side failed its structural laws. */
  case GraphInvalid(violations: Vector[AmrViolation])

  /** The chart side failed its structural laws. */
  case ChartInvalid(violations: Vector[p.ChartViolation])

  /** A node/concept/lemma identifier is not valid on the other side. */
  case InvalidIdentifier(kind: String, raw: String, reason: String)

  /** The chart carries meaning AMR cannot express; strict policy refuses to drop it. */
  case Lossy(reasons: NonEmptyVector[String])

  def message: String = this match
    case Malformed(d)                 => d
    case GraphInvalid(vs)             => vs.map(_.message).mkString("; ")
    case ChartInvalid(vs)             => vs.map(v => s"${v.path}: ${v.message}").mkString("; ")
    case InvalidIdentifier(k, r, why) => s"invalid $k '$r': $why"
    case Lossy(rs)                    => rs.toVector.mkString("; ")

/** The fixed mapping tables of the adapter (design record §99.2, §102).
  *
  * These are the only places where AMR vocabulary is interpreted. Everything is conservative:
  * numbered arguments get a normalized role only when a frame lexicon entry licenses it, and an
  * embedded proposition is recognised only for listed container frames and roles.
  */
object InteropTables:
  /** Frame inventory namespace recorded on every converted [[p.FrameRef]]. */
  val FrameNamespace = "propbank"

  /** Standard (non-core) AMR roles whose semantics are stable across frames. */
  val standardRoles: Map[String, p.ParticipantRole] = Map(
    "location" -> p.ParticipantRole.Location,
    "time" -> p.ParticipantRole.Time,
    "manner" -> p.ParticipantRole.Manner,
    "cause" -> p.ParticipantRole.Cause,
    "purpose" -> p.ParticipantRole.Custom("amr", "purpose"),
    "instrument" -> p.ParticipantRole.Instrument,
    "beneficiary" -> p.ParticipantRole.Beneficiary,
    "source" -> p.ParticipantRole.Source,
    "destination" -> p.ParticipantRole.Destination
  )

  /** Uncalibrated raw score attached to a standard-role normalization. It is *not* a probability:
    * it records that standard-role semantics are stable across frames, nothing more.
    */
  val StandardRoleRawScore = 0.9

  /** Uncalibrated raw score attached to a lexicon-licensed numbered-argument normalization: weak
    * evidence, never a probability.
    */
  val LexiconRawScore = 0.5

  /** PropBank functional tags → normalized roles. Tags without a stable participant reading (and
    * `Custom` tags) become `Custom("propbank", tag)`.
    */
  val functionalTags: Map[FunctionalTag, p.ParticipantRole] = Map(
    FunctionalTag.PAG -> p.ParticipantRole.Agent,
    FunctionalTag.PPT -> p.ParticipantRole.Patient,
    FunctionalTag.GOL -> p.ParticipantRole.Beneficiary,
    FunctionalTag.LOC -> p.ParticipantRole.Location,
    FunctionalTag.MNR -> p.ParticipantRole.Manner,
    FunctionalTag.TMP -> p.ParticipantRole.Time,
    FunctionalTag.CAU -> p.ParticipantRole.Cause,
    FunctionalTag.PRD -> p.ParticipantRole.Result,
    FunctionalTag.DIR -> p.ParticipantRole.Destination
  )

  def tagRole(tag: FunctionalTag): p.ParticipantRole =
    functionalTags.getOrElse(tag, p.ParticipantRole.Custom("propbank", tag.render))

  /** Embedding table: `(container frame, role)` whose node-valued, predicate filler is *held* by
    * the container rather than asserted. Unknown containers embed nothing.
    */
  val embeddings: Map[(String, String), p.EmbeddingKind] =
    import p.EmbeddingKind.*
    Map(
      ("say-01", "ARG1") -> Speech,
      ("tell-01", "ARG1") -> Speech,
      ("think-01", "ARG1") -> Belief,
      ("believe-01", "ARG1") -> Belief,
      ("know-01", "ARG1") -> Belief,
      ("want-01", "ARG1") -> Desire,
      ("wish-01", "ARG1") -> Desire,
      ("hope-01", "ARG1") -> Desire,
      ("intend-01", "ARG1") -> Intention,
      ("plan-01", "ARG1") -> Intention,
      ("try-01", "ARG1") -> Intention,
      ("possible-01", "ARG1") -> Hypothetical,
      ("likely-01", "ARG1") -> Hypothetical,
      ("remember-01", "ARG1") -> Memory,
      ("recall-02", "ARG1") -> Memory,
      ("imagine-01", "ARG1") -> Imagination,
      ("dream-01", "ARG1") -> Imagination
    )

  /** Roles that embed their (predicate) filler regardless of container frame. */
  val embeddingRoles: Map[String, p.EmbeddingKind] = Map(
    "condition" -> p.EmbeddingKind.Hypothetical
  )

  def embeddingOf(container: Concept, role: Role): Option[p.EmbeddingKind] =
    val byFrame = container match
      case Concept.Frame(f) => embeddings.get((f.value, role.render))
      case _                => None
    byFrame.orElse(embeddingRoles.get(role.render))

  /** Rolesets that name abstract AMR relations (`-91`) rather than lexical events. */
  def isSpecialFrame(f: FrameId): Boolean = f.value.endsWith("-91")
