package storymodel4s.story

import storymodel4s.proposition.PropositionEvidence

/** Optional source-side proposition evidence per alignable node (ADR 0001 rev 3 §D4b).
  *
  * Kept as a separate capability rather than a method on [[AlignmentSource]] so that sources built
  * before local charts exist (hand fixtures, M0 models) need no change, and so the aligner's bridge
  * can accept evidence from a document-composition stage that is not the story model itself.
  * Segments never receive a fabricated chart: only atomic situations carry evidence, and a
  * segment's evidence is the multiset of its members' charts (see `align.SegmentEvidence`).
  */
trait PropositionEvidenceSource:
  /** The checked chart for an atomic situation, if one was accepted; `None` for segments and for
    * situations without a chart.
    */
  def propositionEvidenceOf(target: NarrativeNodeId): Option[PropositionEvidence]

object PropositionEvidenceSource:
  /** No evidence for any node. */
  val none: PropositionEvidenceSource = _ => None

  /** Evidence from a lookup table keyed by situation id. */
  def fromMap(
      table: Map[storymodel4s.core.SituationId, PropositionEvidence]
  ): PropositionEvidenceSource =
    case NarrativeNodeId.Situation(id) => table.get(id)
    case NarrativeNodeId.Segment(_)    => None
