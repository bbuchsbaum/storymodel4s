package storymodel4s.pipeline

import storymodel4s.document.*

/** Negative control: s43 has no proposal, rather than an explicit provider abstention. */
private[pipeline] object WogMissingProposalFixture:
  def compile(input: NarrativeCompilerInput): Either[NarrativeCompilerError, NarrativeCompilation] =
    def isS43(ref: ChartNodeRef): Boolean = ref.sentence.value.endsWith(":s43")
    NarrativeCompilerInput
      .of(
        input.source,
        input.atlas,
        input.localCharts,
        input.evidence.values,
        input.situations.map(a =>
          if isS43(a.source) then a.copy(bundle = a.bundle.copy(proposals = Vector.empty)) else a
        ),
        input.contexts.map(a =>
          if isS43(a.source) then a.copy(bundle = a.bundle.copy(proposals = Vector.empty)) else a
        ),
        input.summary,
        input.memberships.map(a =>
          if isS43(a.member) then a.copy(bundle = a.bundle.copy(proposals = Vector.empty)) else a
        ),
        input.causal,
        input.entityMentions,
        input.participants,
        input.participantCoverage,
        input.circumstances,
        input.temporal,
        input.policy,
        input.receipt,
        input.provenance
      )
      .flatMap(NarrativeCompiler.compile)
