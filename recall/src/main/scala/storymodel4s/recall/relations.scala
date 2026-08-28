package storymodel4s.recall

import storymodel4s.core.TextSpan

/** Temporal relation the rememberer states between two units ("before that", "then"). */
enum RecallTemporalRelation:
  case Before, After, Simultaneous

  def inverse: RecallTemporalRelation = this match
    case Before       => After
    case After        => Before
    case Simultaneous => Simultaneous

final case class RecallTemporalEdge(
    from: RecallUnitId,
    relation: RecallTemporalRelation,
    to: RecallUnitId,
    cue: Option[TextSpan]
):
  def inverse: RecallTemporalEdge = RecallTemporalEdge(to, relation.inverse, from, cue)

final case class RecallCausalEdge(cause: RecallUnitId, effect: RecallUnitId, cue: Option[TextSpan])

/** A referent as introduced in the recall, with the spans of its mentions. */
final case class RecallEntity(id: RecallEntityId, label: String, mentions: Vector[TextSpan])

/** `child` elaborates or restates `parent`. */
final case class ElaborationEdge(parent: RecallUnitId, child: RecallUnitId)

/** How a recall-side coreference link was established. */
enum RecallCorefKind:
  /** Two nominal mentions with the same distinctive key ("the young man" … "the young man"). */
  case SameKey

  /** A pronoun resolved to the nearest preceding nominal mention of compatible number. */
  case Pronoun

/** A within-recall coreference link from an anaphoric mention span to a recall entity. Links are
  * only ever made backwards in discourse (the antecedent precedes the anaphor); no link crosses
  * incompatible number or conflicting modifiers.
  */
final case class RecallCorefLink(
    anaphor: TextSpan,
    antecedent: RecallEntityId,
    kind: RecallCorefKind
)

/** Sparse recall-side relation layers; the sequential chain is implicit in unit ordinals. */
final case class RecallRelations(
    temporal: Vector[RecallTemporalEdge],
    causal: Vector[RecallCausalEdge],
    entities: Vector[RecallEntity],
    elaboration: Vector[ElaborationEdge],
    coreference: Vector[RecallCorefLink] = Vector.empty
)

object RecallRelations:
  val empty: RecallRelations =
    RecallRelations(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
