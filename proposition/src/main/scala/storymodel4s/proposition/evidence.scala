package storymodel4s.proposition

import CheckState.Checked

/** A checked local proposition chart offered as *evidence* for a recall unit or a source node (ADR
  * 0001 rev 3 §D4b). It is an optional channel: when it is absent the structural distances
  * `d_chart` and `d_wl` are `Missing`, never imputed from sketches or embeddings.
  *
  * Why the provenance travels with the chart: a chart proposed by a parser, an agent, or a hand
  * annotation must stay distinguishable downstream, because `d_chart` gates (role reversal,
  * polarity, embedding conflict) are only as trustworthy as the chart's origin.
  */
final case class PropositionEvidence(chart: PropositionChart[Checked], provenance: ChartProvenance):
  def origin: ChartOrigin = provenance.origin

object PropositionEvidence:
  /** Evidence whose provenance is the chart's own. */
  def of(chart: PropositionChart[Checked]): PropositionEvidence =
    PropositionEvidence(chart, chart.provenance)

  /** Hand-authored evidence (fixtures and tests). */
  def hand(chart: PropositionChart[Checked]): PropositionEvidence =
    PropositionEvidence(chart, ChartProvenance.hand)
