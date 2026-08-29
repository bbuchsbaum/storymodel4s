package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Runs the recall segmenter over an interview transcript and keeps only participant speech.
  *
  * The transcript atlas remains the single coordinate system: units keep their transcript offsets
  * and are tagged with the turn they fall in; interviewer turns produce no units. A unit whose
  * support crosses a turn boundary cannot be attributed to one speaker; it is excluded and reported
  * in `crossing` rather than silently credited to the first turn. Ordinals are renumbered over the
  * kept units so the resulting `RecallGraph` validates.
  */
object InterviewSegmenter:
  final case class Segmented(
      graph: RecallGraph[Checked],
      turnOf: Map[RecallUnitId, TurnId],
      crossing: Vector[RecallUnitId]
  )

  def segment(source: InterviewSource): Segmented =
    val t = source.transcript
    val full = RecallSegmenter.segment(t.atlas.source)
    val crossing = Vector.newBuilder[RecallUnitId]
    val kept = full.ordered.flatMap { u =>
      val span = u.minSpan
      val startTurn = t.turnAt(span.start)
      val endTurn = if span.isEmpty then startTurn else t.turnAt(span.endExclusive - 1)
      (startTurn, endTurn) match
        case (Some(a), Some(b)) if a.id != b.id =>
          crossing += u.id
          None
        case (Some(turn), _) if t.roleOf(turn.speaker).contains(SpeakerRole.Participant) =>
          Some((u, turn.id))
        case _ => None
    }
    val units = kept.zipWithIndex.map { case ((u, _), i) => u.copy(ordinal = i) }
    val keptIds = units.map(_.id).toSet
    val rel = full.relations
    val relations = RecallRelations(
      rel.temporal.filter(e => keptIds.contains(e.from) && keptIds.contains(e.to)),
      rel.causal.filter(e => keptIds.contains(e.cause) && keptIds.contains(e.effect)),
      rel.entities,
      rel.elaboration.filter(e => keptIds.contains(e.parent) && keptIds.contains(e.child))
    )
    val graph = RecallGraph
      .validated(full.transcript, t.atlas, units, relations)
      .fold(
        errors =>
          throw new IllegalStateException(s"InterviewSegmenter produced an invalid graph: $errors"),
        identity
      )
    Segmented(graph, kept.map { case (u, turn) => u.id -> turn }.toMap, crossing.result())
