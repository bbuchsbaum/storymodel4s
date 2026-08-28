package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.features.{Estimate, ScoreEstimate}
import storymodel4s.proposition.{Checked, EmbeddingKind, ParticipantRole, PropositionChart}
import storymodel4s.recall.*
import storymodel4s.story.NarrativeNodeId

/** Portable text helpers: locale-independent lowercasing and Unicode-aware word splitting, so that
  * cue matching never depends on the default locale or on ASCII-only character classes.
  */
private[interview] object Text:
  /** Per-code-point simple lowercase mapping: locale independent and available on every platform
    * (`java.util.Locale` is not part of the Scala.js standard library).
    */
  def lower(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      sb.appendAll(Character.toChars(Character.toLowerCase(cp)))
      i += Character.charCount(cp)
    sb.toString

  /** Maximal runs of letters, digits and apostrophes, by code point. */
  def words(s: String): Vector[String] =
    val out = Vector.newBuilder[String]
    val sb = new StringBuilder
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      val n = Character.charCount(cp)
      if Character.isLetterOrDigit(cp) || cp == '\'' then sb.appendAll(Character.toChars(cp))
      else if sb.nonEmpty then
        out += sb.toString
        sb.clear()
      i += n
    if sb.nonEmpty then out += sb.toString
    out.result()

/** Perceptual modality of a detail. Local to `interview` so that detail projection never depends on
  * the story trajectory types.
  */
enum Modality:
  case Visual, Auditory, Tactile, Motor, Spatial, Olfactory, Gustatory, Interoceptive

/** A named place as the participant referred to it ("restaurant", "Queen Street"). */
final case class PlaceName(value: String)

/** A temporal expression as uttered ("last year", "at noon"). */
final case class TimeExpression(value: String)

/** Closed set of attribute keys; imported vocabularies enter through `Custom`, never bare strings.
  */
enum AttributeKey:
  /** A descriptive modifier ("small", "French"). */
  case Description

  /** A whole unit kept countable because it carried no propositional content. */
  case Statement

  case Custom(namespace: String, label: String)

/** A key/value attribute asserted of a node. */
final case class Attribute(key: AttributeKey, value: String)

/** Either a narrative node or an entity, as the target of an attribute. */
enum AtomTarget:
  case Node(id: NarrativeNodeId)
  case Entity(id: EntityId)

/** A temporal claim between a situation and either another situation or a named anchor. */
enum TemporalClaim:
  case Anchor(situation: SituationId, expression: TimeExpression)
  case Relation(from: SituationId, relation: RecallTemporalRelation, to: SituationId)

/** A spatial claim: a situation located at, or an entity moving to, a named place. */
enum SpatialClaim:
  case AtLocation(situation: SituationId, location: PlaceName)
  case Movement(situation: SituationId, from: Option[PlaceName], to: Option[PlaceName])

enum MentalStateKind:
  case Thought, Emotion, Intention, Belief, Uncertainty

/** Closed label vocabulary for mental states; chart-derived or imported labels use `Custom`. */
enum MentalStateLabel:
  case Embarrassment, Happiness, Sadness, Nervousness, Anxiety, Fear, Excitement, Pride, Anger,
    Surprise, Relief, Gratitude, Loneliness, Boredom
  case Intent, Belief, Thought, Uncertainty
  case Custom(namespace: String, label: String)

final case class MentalState(kind: MentalStateKind, label: MentalStateLabel)

/** A narrative relation stated between two situations of the transcript. */
enum NarrativeRelationRef:
  case Causal(cause: SituationId, effect: SituationId)
  case Temporal(from: SituationId, relation: RecallTemporalRelation, to: SituationId)
  case Elaborates(parent: SituationId, child: SituationId)

/** The countable detail kinds of design record §62.
  *
  * Why not AMR triples: one manual "detail" has no stable one-node/one-edge equivalent; these atoms
  * are the unit that scoring policies count and that assessments address.
  */
enum DetailAtom:
  case EventOccurrence(event: SituationId)
  case ParticipantFact(event: SituationId, role: ParticipantRole, entity: EntityId)
  case AttributeFact(target: AtomTarget, attribute: Attribute)
  case TemporalFact(relation: TemporalClaim)
  case SpatialFact(relation: SpatialClaim)
  case PerceptualFact(experiencer: EntityId, modality: Modality, content: NarrativeNodeId)
  case MentalStateFact(holder: EntityId, state: MentalState)
  case RelationalFact(relation: NarrativeRelationRef)

  /** The situation this atom is primarily about, when it has one. */
  def situation: Option[SituationId] = this match
    case EventOccurrence(e)                                              => Some(e)
    case ParticipantFact(e, _, _)                                        => Some(e)
    case AttributeFact(AtomTarget.Node(NarrativeNodeId.Situation(s)), _) => Some(s)
    case AttributeFact(_, _)                                             => None
    case TemporalFact(TemporalClaim.Anchor(s, _))                        => Some(s)
    case TemporalFact(TemporalClaim.Relation(f, _, _))                   => Some(f)
    case SpatialFact(SpatialClaim.AtLocation(s, _))                      => Some(s)
    case SpatialFact(SpatialClaim.Movement(s, _, _))                     => Some(s)
    case PerceptualFact(_, _, NarrativeNodeId.Situation(s))              => Some(s)
    case PerceptualFact(_, _, _)                                         => None
    case MentalStateFact(_, _)                                           => None
    case RelationalFact(NarrativeRelationRef.Causal(c, _))               => Some(c)
    case RelationalFact(NarrativeRelationRef.Temporal(f, _, _))          => Some(f)
    case RelationalFact(NarrativeRelationRef.Elaborates(p, _))           => Some(p)

  /** Every situation the atom links (relations link two). */
  def situations: Set[SituationId] = this match
    case TemporalFact(TemporalClaim.Relation(f, _, t))          => Set(f, t)
    case RelationalFact(NarrativeRelationRef.Causal(c, e))      => Set(c, e)
    case RelationalFact(NarrativeRelationRef.Temporal(f, _, t)) => Set(f, t)
    case RelationalFact(NarrativeRelationRef.Elaborates(p, c))  => Set(p, c)
    case other                                                  => other.situation.toSet

  def isRelational: Boolean = this match
    case RelationalFact(_)                             => true
    case TemporalFact(TemporalClaim.Relation(_, _, _)) => true
    case _                                             => false

/** A detail atom anchored to its transcript evidence and carrying its expected manual-count mass
  * `w_i` (design record §65.1).
  *
  * `expectedCountMass` is `Observed(1.0)` per atom in v0.1 — an explicit placeholder for the
  * learned, rule-constrained projection; it is never derived from a lexicon lookup. A `Missing`
  * mass is never read as zero: scoring excludes the detail and reports the exclusion as coverage.
  */
final case class Detail(
    id: DetailId,
    atom: DetailAtom,
    support: SpanSet,
    turn: TurnId,
    sourceUnit: RecallUnitId,
    expectedCountMass: ScoreEstimate
):
  /** The count mass when it was observed; `None` means "unknown", not "zero". */
  def observedMass: Option[Double] = expectedCountMass.toOption

/** Rule-based, conservative projection of a recall unit into detail atoms.
  *
  * Rules are deliberately shallow: an event for a predicate; participants for the sketch's agent
  * and patient; spatial facts for named locations; temporal anchors for time expressions;
  * perceptual facts for sensory terms; mental-state facts for emotion/thought cues; an attribute
  * fact for description-only units; and one statement atom for units without propositional content
  * so that every unit stays countable. When a checked chart is supplied its predicates and embedded
  * propositions refine the event count and mental-state kinds; a missing frame never blocks
  * projection.
  */
object AtomProjection:
  /** The speaker as an entity of the inferred episode. */
  val Speaker: EntityId = EntityId.unsafe("interview:speaker")

  private val Emotions: Map[String, MentalStateLabel] = Map(
    "embarrassed" -> MentalStateLabel.Embarrassment,
    "embarrassing" -> MentalStateLabel.Embarrassment,
    "embarrassment" -> MentalStateLabel.Embarrassment,
    "happy" -> MentalStateLabel.Happiness,
    "happiness" -> MentalStateLabel.Happiness,
    "sad" -> MentalStateLabel.Sadness,
    "nervous" -> MentalStateLabel.Nervousness,
    "anxious" -> MentalStateLabel.Anxiety,
    "scared" -> MentalStateLabel.Fear,
    "afraid" -> MentalStateLabel.Fear,
    "excited" -> MentalStateLabel.Excitement,
    "proud" -> MentalStateLabel.Pride,
    "angry" -> MentalStateLabel.Anger,
    "surprised" -> MentalStateLabel.Surprise,
    "relieved" -> MentalStateLabel.Relief,
    "grateful" -> MentalStateLabel.Gratitude,
    "lonely" -> MentalStateLabel.Loneliness,
    "bored" -> MentalStateLabel.Boredom
  )
  private val ThoughtCues =
    """\b(?:thought|figured|realized|realised|decided|remember thinking)\b""".r
  private val IntentionCues = """\b(?:wanted|planned|hoped|meant) to\b""".r
  private val BeliefCues = """\b(?:believed|assumed|was sure|knew)\b""".r
  private val UncertaintyCues = """\b(?:not sure|don't know|can't remember|unsure)\b""".r
  private val TimeExpressionRe =
    """\b(?:(?:in )?(?:19|20)\d{2}|(?:last|next|that|the following|the previous) (?:year|month|week|night|morning|evening|summer|winter)|at (?:noon|midnight|\d{1,2}(?::\d{2})?(?: ?[ap]m)?)|(?:the )?year before|on my \w+ birthday|my \w+ birthday|(?:january|february|march|april|may|june|july|august|september|october|november|december)\b(?: \d{1,2})?)\b""".r
  private val Descriptors: Set[String] = Set(
    "small",
    "big",
    "little",
    "old",
    "new",
    "french",
    "italian",
    "quiet",
    "noisy",
    "crowded",
    "empty",
    "fancy",
    "cheap",
    "expensive",
    "tiny",
    "huge",
    "cozy",
    "cosy"
  )
  private val Visual: Set[String] = Set(
    "red",
    "black",
    "white",
    "dark",
    "darkness",
    "light",
    "bright",
    "fogged",
    "foggy",
    "fog",
    "candles",
    "candle",
    "rain",
    "raining",
    "windows",
    "window"
  )
  private val Auditory: Set[String] =
    Set("noise", "sound", "loud", "quiet", "voice", "shout", "singing", "sang", "sing", "song")
  private val Olfactory: Set[String] = Set("smell", "smelled", "smelt", "scent")
  private val Gustatory: Set[String] = Set("taste", "tasted", "sweet", "salty", "bitter")
  private val Tactile: Set[String] = Set("cold", "hot", "warm", "wet", "soft", "rough")
  private val SensoryAll: Set[String] =
    Visual ++ Auditory ++ Olfactory ++ Gustatory ++ Tactile

  def situationOf(unit: RecallUnit): SituationId =
    SituationId.unsafe(s"interview:sit:${unit.id.value}")

  def entityOf(p: SketchParticipant): EntityId =
    p.entity match
      case Some(e) => EntityId.unsafe(s"interview:ent:${e.value}")
      case None if Set("i", "me", "we", "us").contains(Text.lower(p.label)) => Speaker
      case None => EntityId.unsafe(s"interview:ent:${Text.lower(p.label).replace(' ', '_')}")

  private def roleOf(r: SketchRole): ParticipantRole = r match
    case SketchRole.Agent       => ParticipantRole.Agent
    case SketchRole.Patient     => ParticipantRole.Patient
    case SketchRole.Theme       => ParticipantRole.Theme
    case SketchRole.Experiencer => ParticipantRole.Experiencer
    case SketchRole.Location    => ParticipantRole.Location
    case SketchRole.Destination => ParticipantRole.Destination
    case SketchRole.Source      => ParticipantRole.Source
    case SketchRole.Instrument  => ParticipantRole.Instrument
    case SketchRole.Time        => ParticipantRole.Time
    case SketchRole.Beneficiary => ParticipantRole.Beneficiary
    case SketchRole.Other(l)    => ParticipantRole.Custom("recall", l)

  private def modalityOf(word: String): Modality =
    if Auditory.contains(word) then Modality.Auditory
    else if Olfactory.contains(word) then Modality.Olfactory
    else if Gustatory.contains(word) then Modality.Gustatory
    else if Tactile.contains(word) then Modality.Tactile
    else Modality.Visual

  /** Embedded-proposition kinds that denote a mental state of the speaker. The mapping is partial
    * on purpose: speech, hypothetical, counterfactual and imagination embeddings are not mental
    * states of the rememberer and produce no atom here.
    */
  private def mentalKindOf(kind: EmbeddingKind): Option[MentalStateKind] = kind match
    case EmbeddingKind.Belief    => Some(MentalStateKind.Belief)
    case EmbeddingKind.Desire    => Some(MentalStateKind.Intention)
    case EmbeddingKind.Intention => Some(MentalStateKind.Intention)
    case EmbeddingKind.Memory    => Some(MentalStateKind.Thought)
    case EmbeddingKind.Speech | EmbeddingKind.Hypothetical | EmbeddingKind.Counterfactual |
        EmbeddingKind.Imagination | EmbeddingKind.Unknown =>
      None

  def fromUnit(
      unit: RecallUnit,
      turn: TurnId,
      chart: Option[PropositionChart[Checked]] = None
  ): Vector[Detail] =
    val sit = situationOf(unit)
    val node = NarrativeNodeId.Situation(sit)
    val lower = Text.lower(unit.text)
    val words = Text.words(lower)
    val sketch = unit.proposition
    val atoms = Vector.newBuilder[DetailAtom]

    val propositional = sketch.predicate.nonEmpty ||
      chart.exists(_.predicates.nonEmpty)
    if propositional then
      atoms += DetailAtom.EventOccurrence(sit)
      sketch.participants.foreach { p =>
        atoms += DetailAtom.ParticipantFact(sit, roleOf(p.role), entityOf(p))
      }

    sketch.locations.distinct.foreach { loc =>
      atoms += DetailAtom.SpatialFact(SpatialClaim.AtLocation(sit, PlaceName(loc)))
    }
    (sketch.times ++ TimeExpressionRe.findAllIn(lower).toVector).distinct.foreach { t =>
      atoms += DetailAtom.TemporalFact(TemporalClaim.Anchor(sit, TimeExpression(t)))
    }
    (sketch.sensoryTerms ++ words.filter(SensoryAll.contains)).distinct.foreach { s =>
      atoms += DetailAtom.PerceptualFact(Speaker, modalityOf(s), node)
    }
    words.flatMap(Emotions.get).distinct.foreach { e =>
      atoms += DetailAtom.MentalStateFact(Speaker, MentalState(MentalStateKind.Emotion, e))
    }
    if IntentionCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(
        Speaker,
        MentalState(MentalStateKind.Intention, MentalStateLabel.Intent)
      )
    else if BeliefCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(
        Speaker,
        MentalState(MentalStateKind.Belief, MentalStateLabel.Belief)
      )
    else if ThoughtCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(
        Speaker,
        MentalState(MentalStateKind.Thought, MentalStateLabel.Thought)
      )
    else if UncertaintyCues.findFirstIn(lower).nonEmpty &&
      unit.function != DiscourseFunction.SourceMonitoring
    then
      atoms += DetailAtom.MentalStateFact(
        Speaker,
        MentalState(MentalStateKind.Uncertainty, MentalStateLabel.Uncertainty)
      )
    chart.foreach { c =>
      c.embedded.foreach { e =>
        mentalKindOf(e.kind).foreach { k =>
          val label = c
            .concept(e.content)
            .map(cn => MentalStateLabel.Custom("chart", cn.lemma.value))
            .getOrElse(MentalStateLabel.Custom("chart", "embedded"))
          atoms += DetailAtom.MentalStateFact(Speaker, MentalState(k, label))
        }
      }
    }
    val descriptors = words.filter(Descriptors.contains).distinct
    if descriptors.nonEmpty then
      val target =
        sketch.locations.headOption
          .map(l => AtomTarget.Entity(EntityId.unsafe(s"interview:loc:$l")))
          .getOrElse(AtomTarget.Node(node))
      descriptors.foreach(d =>
        atoms += DetailAtom.AttributeFact(target, Attribute(AttributeKey.Description, d))
      )

    val built = atoms.result()
    val all =
      if built.nonEmpty then built
      else
        Vector(
          DetailAtom.AttributeFact(
            AtomTarget.Node(node),
            Attribute(AttributeKey.Statement, unit.text)
          )
        )
    all.zipWithIndex.map { case (atom, i) =>
      Detail(
        DetailId.unsafe(s"${unit.id.value}:d$i"),
        atom,
        unit.span,
        turn,
        unit.id,
        Estimate.observed(1.0)
      )
    }

  /** Relational atoms from explicit recall relations (causal/temporal connectives). */
  def fromRelations(graph: RecallGraph, turnOf: RecallUnitId => Option[TurnId]): Vector[Detail] =
    def sit(id: RecallUnitId): Option[SituationId] = graph.unit(id).map(situationOf)
    val causal = graph.relations.causal.flatMap { e =>
      for
        c <- sit(e.cause)
        f <- sit(e.effect)
        u <- graph.unit(e.effect)
        t <- turnOf(e.effect)
      yield Detail(
        DetailId.unsafe(s"${e.effect.value}:causal:${e.cause.value}"),
        DetailAtom.RelationalFact(NarrativeRelationRef.Causal(c, f)),
        e.cue.map(SpanSet.one).getOrElse(u.span),
        t,
        e.effect,
        Estimate.observed(1.0)
      )
    }
    val temporal = graph.relations.temporal.flatMap { e =>
      for
        a <- sit(e.from)
        b <- sit(e.to)
        u <- graph.unit(e.to)
        t <- turnOf(e.to)
      yield Detail(
        DetailId.unsafe(s"${e.to.value}:temporal:${e.from.value}"),
        DetailAtom.TemporalFact(TemporalClaim.Relation(a, e.relation, b)),
        e.cue.map(SpanSet.one).getOrElse(u.span),
        t,
        e.to,
        Estimate.observed(1.0)
      )
    }
    causal ++ temporal
