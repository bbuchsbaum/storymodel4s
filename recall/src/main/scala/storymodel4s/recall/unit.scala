package storymodel4s.recall

import storymodel4s.core.{Probability, SpanSet, TextSpan}

/** What a recall unit is doing in the discourse. Kept separate from source anchoring: an
  * association is not an intrusion, and an evaluation is not an omission.
  */
enum DiscourseFunction:
  case EpisodicAssertion, Summary, Inference, Association, Evaluation, SourceMonitoring,
    TaskCommentary, Uninterpretable

/** Uncertainty the rememberer expressed in words ("I think", "some kind of"). Stored as a feature,
  * never folded into alignment posteriors.
  */
enum ExpressedUncertainty:
  case Unmarked
  case Hedged(cues: SpanSet)
  case Explicit(cues: SpanSet)

  def isMarked: Boolean = this != Unmarked

enum PolarityTag:
  case Positive, Negative, Unknown

enum ModalityTag:
  case Asserted, Possible, Intended, Desired, Reported, Counterfactual, Unknown

/** Compact role vocabulary shared by recall sketches and source node summaries. `Beneficiary`
  * covers recipients/addressees, the patient-like counterpart of speech and transfer acts.
  */
enum SketchRole:
  case Agent, Patient, Theme, Experiencer, Location, Destination, Source, Instrument, Time,
    Beneficiary
  case Other(label: String)

/** Determiner class of a nominal mention, as the rememberer said it. */
enum Determiner:
  case Definite, Indefinite, Demonstrative, Possessive, Bare

/** Grammatical number of a nominal mention, from the determiner, a numeral, or plural morphology.
  * Named `MentionNumber` to avoid shadowing `java.lang.Number`.
  */
enum MentionNumber:
  case Singular, Plural

/** A participant as the recall unit names it. `specified = false` marks indefinite reference
  * ("somebody", "they") so fidelity can report "unspecified" instead of "wrong".
  *
  * Nominal structure (W5): `head` is the normalized head lemma, `modifiers` the normalized pre-head
  * content words (including numerals and quantifiers), so "the young man" and "the five men" are
  * distinct participants although both heads normalize to `man`. `distinctiveKey` renders that
  * identity; `aliases` carry the token-safe identity keys the aligner compares (see
  * [[NominalMention.keys]]).
  *
  * Identity rule for `names`: when `aliases` is nonempty the participant is identified by its
  * aliases only — the display `label` is not an identity token, because a phrase label such as
  * "young man" would tokenize to the bare head and defeat the very distinction the keys encode. A
  * participant built with no aliases (hand-written sketches, pronouns) is identified by its label,
  * as before.
  */
final case class SketchParticipant(
    role: SketchRole,
    entity: Option[RecallEntityId],
    label: String,
    specified: Boolean = true,
    aliases: Set[String] = Set.empty,
    head: String = "",
    modifiers: Vector[String] = Vector.empty,
    determiner: Option[Determiner] = None,
    number: Option[MentionNumber] = None
):
  def names: Set[String] =
    if aliases.nonEmpty then aliases.map(Lexical.lower) else Set(Lexical.lower(label))

  /** Head plus sorted modifiers, e.g. `young+man`; empty when no head is known. */
  def distinctiveKey: String =
    if head.isEmpty then "" else (modifiers.sorted :+ head).mkString("+")

/** Shallow propositional content of a recall unit: enough for structural adjudication (role
  * direction, polarity, modality, location, outcome) without a full semantic graph.
  */
final case class PropositionSketch(
    predicate: Option[String],
    participants: Vector[SketchParticipant],
    polarity: PolarityTag,
    modality: ModalityTag,
    locations: Vector[String],
    times: Vector[String],
    sensoryTerms: Vector[String],
    lemmas: Set[String],
    outcome: Option[String] = None,
    cause: Option[String] = None
):
  def byRole(role: SketchRole): Option[SketchParticipant] = participants.find(_.role == role)
  def agent: Option[SketchParticipant] = byRole(SketchRole.Agent)

  /** The patient-like participant: patient, else theme, else recipient/addressee. */
  def patient: Option[SketchParticipant] =
    byRole(SketchRole.Patient)
      .orElse(byRole(SketchRole.Theme))
      .orElse(byRole(SketchRole.Beneficiary))

object PropositionSketch:
  val empty: PropositionSketch =
    PropositionSketch(
      None,
      Vector.empty,
      PolarityTag.Unknown,
      ModalityTag.Unknown,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Set.empty
    )

/** One idea unit of a recall: `(z, q, ψ, κ)` in the design record, with `z` (source anchoring) left
  * to the aligner and `grounding` the optional partial-grounding prior α.
  */
final case class RecallUnit(
    id: RecallUnitId,
    ordinal: Int,
    span: SpanSet,
    text: String,
    function: DiscourseFunction,
    expressedUncertainty: ExpressedUncertainty,
    proposition: PropositionSketch,
    grounding: Option[Probability]
):
  def minSpan: TextSpan = span.minSpan
