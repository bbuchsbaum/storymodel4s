package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.recall.*

/** Runs the recall segmenter over an interview transcript and keeps only participant speech.
  *
  * The transcript atlas remains the single coordinate system: units keep their transcript offsets
  * and are tagged with the turn they fall in; interviewer turns produce no units. Ordinals are
  * renumbered over the kept units so the resulting `RecallGraph` validates.
  */
object InterviewSegmenter:
  final case class Segmented(graph: RecallGraph, turnOf: Map[RecallUnitId, TurnId])

  def segment(source: InterviewSource): Segmented =
    val t = source.transcript
    val full = RecallSegmenter.segment(t.atlas.source)
    val kept = full.ordered.flatMap { u =>
      t.turnAt(u.minSpan.start) match
        case Some(turn) if t.roleOf(turn.speaker).contains(SpeakerRole.Participant) =>
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
    Segmented(
      RecallGraph(full.transcript, t.atlas, units, relations),
      kept.map { case (u, turn) => u.id -> turn }.toMap
    )
