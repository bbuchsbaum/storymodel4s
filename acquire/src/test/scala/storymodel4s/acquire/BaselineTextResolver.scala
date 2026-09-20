package storymodel4s.acquire

import cats.data.NonEmptyVector

object BaselineTextResolver:
  private final case class Candidate[A](
      value: A,
      providers: Int,
      proposals: Int,
      best: Option[RawScore],
      evidence: Vector[EvidenceRef],
      order: Int
  ):
    def weighted: Weighted[A] = Weighted(value, providers, proposals, best)

  def resolve[A](
      family: ClaimFamily,
      bundle: EvidenceBundle[A],
      policy: AcceptancePolicy
  ): ResolutionState[A] =
    val fp = policy.forFamily(family)
    val blocking = CriticFinding.blocking(bundle.findings, fp.criticBlockThreshold).map(_.code)
    if !bundle.structural.valid then
      ResolutionState.Rejected(RejectionReason.StructurallyInvalid(bundle.structural.violations))
    else if blocking.nonEmpty then
      if fp.conservative then ResolutionState.Rejected(RejectionReason.BlockingFinding(blocking))
      else ResolutionState.Unresolved(ResolutionFailure.BlockingFinding(blocking))
    else
      val candidates = collect(bundle.proposals)
      NonEmptyVector.fromVector(candidates) match
        case None     => ResolutionState.Unresolved(ResolutionFailure.NoProposal)
        case Some(cs) =>
          val leading = cs.toVector.minBy(c => (c.weighted, c.order))
          if fp.conservative && bundle.sourceSupport.score <= 0.0 then
            ResolutionState.Rejected(RejectionReason.NoSourceSupport)
          else
            bundle.basisFor(leading.value) match
              case None =>
                if fp.requireCalibration && cs.length == 1 then
                  ResolutionState.Unresolved(ResolutionFailure.Uncalibrated)
                else alternatives(cs)
              case Some(basis) =>
                if leading.providers < fp.requireAgreement then
                  ResolutionState.Unresolved(
                    ResolutionFailure.InsufficientAgreement(leading.providers, fp.requireAgreement)
                  )
                else
                  basis match
                    case AcceptanceBasis.Calibrated(p, _) =>
                      if p.value >= fp.acceptThreshold.value then accept(fp, bundle, leading, basis)
                      else if p.value >= fp.reviewBand.value then alternatives(cs)
                      else
                        ResolutionState.Rejected(RejectionReason.BelowRejectBand(p, fp.reviewBand))
                    case AcceptanceBasis.Determined(_) =>
                      // A determined value has no probability to threshold: it is accepted on
                      // agreement and span evidence alone, or not at all.
                      accept(fp, bundle, leading, basis)

  /** Acceptance once the basis has licensed it: span evidence per policy, then the evidence set. */
  private def accept[A](
      fp: FamilyPolicy,
      bundle: EvidenceBundle[A],
      leading: Candidate[A],
      basis: AcceptanceBasis
  ): ResolutionState[A] =
    if fp.requireSpanEvidence && !hasSpans(bundle, leading) then
      ResolutionState.Unresolved(ResolutionFailure.NoSpanEvidence)
    else
      NonEmptyVector.fromVector(leading.evidence) match
        case Some(ev) => ResolutionState.Accepted(leading.value, basis, ev)
        case None     => ResolutionState.Unresolved(ResolutionFailure.NoSpanEvidence)

  private def hasSpans[A](bundle: EvidenceBundle[A], c: Candidate[A]): Boolean =
    bundle.sourceSupport.spans.nonEmpty || c.evidence.exists(_.spans.nonEmpty)

  /** Identity of the provider behind a proposal, for counting independent agreement. */
  private def providerKey(p: AgentProposal[?]): (String, String, String) =
    val call = p.receipt.call
    (call.provider, call.model, call.version)

  /** Distinct proposed values with the number of distinct providers that `Proposed` each, the
    * number of substantive proposals, their best raw score, the union of their evidence, and
    * first-appearance order for deterministic tie-breaking.
    */
  private def collect[A](proposals: Vector[AgentProposal[A]]): Vector[Candidate[A]] =
    val substantive = proposals.filter(_.isSubstantive)
    val order = substantive.map(_.value.get).distinct.zipWithIndex.toMap
    substantive
      .groupBy(_.value.get)
      .toVector
      .map { (v, ps) =>
        val proposedBy = ps.filter(_.disposition == ProposalDisposition.Proposed)
        val providers = proposedBy.map(providerKey).distinct.size
        val best = ps.flatMap(_.rawScore).maxOption
        val evidence = ps.flatMap(_.evidence).distinctBy(_.evidenceId)
        Candidate(v, providers, ps.size, best, evidence, order(v))
      }
      .sortBy(_.order)

  private def alternatives[A](cs: NonEmptyVector[Candidate[A]]): ResolutionState[A] =
    ResolutionState.Alternatives(
      NonEmptyVector.fromVectorUnsafe(
        cs.toVector
          .sortBy(c => (c.weighted, c.order))
          .map(_.weighted)
      )
    )
