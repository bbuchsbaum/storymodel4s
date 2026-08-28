package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.features.{Estimate, ScoreEstimate}
import storymodel4s.proposition.{EmbeddingKind, ParticipantRole, PropositionChart, Checked}
import storymodel4s.recall.*
import storymodel4s.story.NarrativeNodeId

/** Perceptual modality of a detail. Local to `interview` so that detail projection never depends on
  * the story trajectory types (which are being consolidated into `features`).
  */
enum Modality:
  case Visual, Auditory, Tactile, Motor, Spatial, Olfactory, Gustatory, Interoceptive

/** A key/value attribute asserted of a node ("colour" → "red", "description" → "small"). */
final case class Attribute(key: String, value: String)

/** Either a narrative node or an entity, as the target of an attribute. */
enum AtomTarget:
  case Node(id: NarrativeNodeId)
  case Entity(id: EntityId)

/** A temporal claim between a situation and either another situation or a named anchor. */
enum TemporalClaim:
  case Anchor(situation: SituationId, expression: String)
  case Relation(from: SituationId, relation: RecallTemporalRelation, to: SituationId)

/** A spatial claim: a situation located at, or an entity moving to, a named place. */
enum SpatialClaim:
  case AtLocation(situation: SituationId, location: String)
  case Movement(situation: SituationId, from: Option[String], to: Option[String])

enum MentalStateKind:
  case Thought, Emotion, Intention, Belief, Uncertainty

final case class MentalState(kind: MentalStateKind, label: String)

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
  * learned, rule-constrained projection; it is never derived from a lexicon lookup.
  */
final case class Detail(
    id: DetailId,
    atom: DetailAtom,
    support: SpanSet,
    turn: TurnId,
    sourceUnit: RecallUnitId,
    expectedCountMass: ScoreEstimate
):
  def mass: Double = expectedCountMass.toOption.getOrElse(0.0)

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

  private val Emotions: Map[String, String] = Map(
    "embarrassed" -> "embarrassment",
    "embarrassing" -> "embarrassment",
    "embarrassment" -> "embarrassment",
    "happy" -> "happiness",
    "happiness" -> "happiness",
    "sad" -> "sadness",
    "nervous" -> "nervousness",
    "anxious" -> "anxiety",
    "scared" -> "fear",
    "afraid" -> "fear",
    "excited" -> "excitement",
    "proud" -> "pride",
    "angry" -> "anger",
    "surprised" -> "surprise",
    "relieved" -> "relief",
    "grateful" -> "gratitude",
    "lonely" -> "loneliness",
    "bored" -> "boredom"
  )
  private val ThoughtCues =
    """\b(?:thought|figured|realized|realised|decided|remember thinking)\b""".r
  private val IntentionCues = """\b(?:wanted|planned|hoped|meant) to\b""".r
  private val BeliefCues = """\b(?:believed|assumed|was sure|knew)\b""".r
  private val UncertaintyCues = """\b(?:not sure|don't know|can't remember|unsure)\b""".r
  private val TimeExpression =
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
      case None if Set("i", "me", "we", "us").contains(p.label.toLowerCase) => Speaker
      case None => EntityId.unsafe(s"interview:ent:${p.label.toLowerCase.replace(' ', '_')}")

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
    case SketchRole.Other(l)    => ParticipantRole.Custom("recall", l)

  private def modalityOf(word: String): Modality =
    if Auditory.contains(word) then Modality.Auditory
    else if Olfactory.contains(word) then Modality.Olfactory
    else if Gustatory.contains(word) then Modality.Gustatory
    else if Tactile.contains(word) then Modality.Tactile
    else Modality.Visual

  def fromUnit(
      unit: RecallUnit,
      turn: TurnId,
      chart: Option[PropositionChart[Checked]] = None
  ): Vector[Detail] =
    val sit = situationOf(unit)
    val node = NarrativeNodeId.Situation(sit)
    val lower = unit.text.toLowerCase
    val words = """[a-z']+""".r.findAllIn(lower).toVector
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
      atoms += DetailAtom.SpatialFact(SpatialClaim.AtLocation(sit, loc))
    }
    (sketch.times ++ TimeExpression.findAllIn(lower).toVector).distinct.foreach { t =>
      atoms += DetailAtom.TemporalFact(TemporalClaim.Anchor(sit, t))
    }
    (sketch.sensoryTerms ++ words.filter(SensoryAll.contains)).distinct.foreach { s =>
      atoms += DetailAtom.PerceptualFact(Speaker, modalityOf(s), node)
    }
    words.flatMap(Emotions.get).distinct.foreach { e =>
      atoms += DetailAtom.MentalStateFact(Speaker, MentalState(MentalStateKind.Emotion, e))
    }
    if IntentionCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(Speaker, MentalState(MentalStateKind.Intention, "intent"))
    else if BeliefCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(Speaker, MentalState(MentalStateKind.Belief, "belief"))
    else if ThoughtCues.findFirstIn(lower).nonEmpty then
      atoms += DetailAtom.MentalStateFact(Speaker, MentalState(MentalStateKind.Thought, "thought"))
    else if UncertaintyCues.findFirstIn(lower).nonEmpty &&
      unit.function != DiscourseFunction.SourceMonitoring
    then
      atoms += DetailAtom.MentalStateFact(
        Speaker,
        MentalState(MentalStateKind.Uncertainty, "uncertainty")
      )
    chart.foreach { c =>
      c.embedded.foreach { e =>
        val kind = e.kind match
          case EmbeddingKind.Belief    => Some(MentalStateKind.Belief)
          case EmbeddingKind.Desire    => Some(MentalStateKind.Intention)
          case EmbeddingKind.Intention => Some(MentalStateKind.Intention)
          case EmbeddingKind.Memory    => Some(MentalStateKind.Thought)
          case _                       => None
        kind.foreach { k =>
          val label = c.concept(e.content).map(_.lemma.value).getOrElse("embedded")
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
        atoms += DetailAtom.AttributeFact(target, Attribute("description", d))
      )

    val built = atoms.result()
    val all =
      if built.nonEmpty then built
      else
        Vector(DetailAtom.AttributeFact(AtomTarget.Node(node), Attribute("statement", unit.text)))
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
