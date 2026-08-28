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

/** Sparse recall-side relation layers; the sequential chain is implicit in unit ordinals. */
final case class RecallRelations(
    temporal: Vector[RecallTemporalEdge],
    causal: Vector[RecallCausalEdge],
    entities: Vector[RecallEntity],
    elaboration: Vector[ElaborationEdge]
)

object RecallRelations:
  val empty: RecallRelations =
    RecallRelations(Vector.empty, Vector.empty, Vector.empty, Vector.empty)
