package storymodel4s.align

import cats.syntax.all.*
import storymodel4s.core.Checksum
import storymodel4s.features.CanonicalDouble
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Local mapping evidence: every nominated anchor gated and every admitted state priced ONCE,
  * before any sequence inference.
  *
  * Why: local reference measurement and [[GraphHsmm]] must read the same nominations and the same
  * local costs, so that any difference between their outputs is attributable to inference and never
  * to re-pricing. Only [[LocalEvidence.compute]] builds one. It records the recall, the view, and
  * whether the mode gate ran, and [[GraphHsmm]] refuses evidence bound to anything else.
  *
  * The legacy recall checksum and view fingerprint do not separate an absent predicate, outcome or
  * cause from an explicitly empty one, so each is paired with the supplement that does
  * (`MappingBindingRender.recall`, `MappingSourceRender.scope`), exactly as [[DerivationBinding]]
  * binds a mapping. Equality and hashing digest the whole evidence once.
  *
  * Candidates that are not nodes of the view are dropped here, exactly as inference always did:
  * they are neither priced nor nominated.
  */
final class LocalEvidence private (
    val recallChecksum: Checksum,
    val recallSupplement: Checksum,
    val viewFingerprint: ViewFingerprint,
    val scopeDigest: Checksum,
    val gated: Boolean,
    val provenance: CandidateProvenance,
    val units: Vector[RecallUnitId],
    val candidates: Vector[CandidateSet],
    val nominated: Vector[Vector[SourceNodeRef]],
    val admissibility: Vector[Map[SourceNodeRef, Admissibility]],
    val breakdowns: Vector[Map[AlignState, CostBreakdown]]
):

  /** The states each unit can occupy: its non-excluded priced states, in key order. */
  def states: Vector[Vector[AlignState]] =
    breakdowns.map(m => m.toVector.collect { case (s, b) if !b.excluded => s }.sortBy(_.key))

  /** The engine evidence identity: a content digest of everything above, derived here and never
    * accepted from a caller. It does NOT identify the cost model, render, or scoring providers that
    * produced the prices; those receipts bind in the mapping-run envelope.
    */
  lazy val identity: LocalEvidenceId = LocalEvidenceId.derive(this)

  override def equals(that: Any): Boolean = that match
    case o: LocalEvidence => identity == o.identity
    case _                => false

  override def hashCode: Int = identity.hashCode

  override def toString: String =
    s"LocalEvidence(units=${units.size}, gated=$gated, " +
      s"view=${viewFingerprint.checksum.short()}, recall=${recallChecksum.short()})"

object LocalEvidence:

  /** Gate (when `gate`) and price every nominated anchor of every unit, plus the external states.
    *
    * The mode gate runs first and is non-bypassable: the cost model is consulted only for the
    * `(anchor, mode)` pairs it admits (law L3). A unit the aligner could not rank has the single
    * state `Unranked`.
    */
  def compute(
      recall: RecallGraph[Checked],
      source: SourceView,
      candidates: Candidates,
      costModel: LocalCostModel,
      gate: Boolean
  ): Either[AlignError, LocalEvidence] =
    build(recall, source, candidates, CandidateProvenance.Unattested, costModel, gate)

  /** Gated evidence over strict candidates. The tie policy, every overflow and every uniform
    * semantic level are recorded on the evidence and in its identity, so no consumer can mistake a
    * withheld level for a successfully nominated universe.
    */
  def compute(
      recall: RecallGraph[Checked],
      source: SourceView,
      strict: StrictCandidates,
      costModel: LocalCostModel
  ): Either[AlignError, LocalEvidence] =
    val snapshot = MappingBindingRender.snapshot(source)
    def refuse(detail: String) = Left(AlignError.InconsistentResult(s"strict candidates $detail"))
    val bound = StrictBinding.of(recall, snapshot)
    val b = strict.binding
    val check: Either[AlignError, Unit] =
      if b.units != bound.units then refuse("cover different recall units or a different order")
      else if b.recallChecksum != bound.recallChecksum || b.recallSupplement != bound.recallSupplement
      then refuse("were generated for a different recall")
      else if b.viewFingerprint != bound.viewFingerprint || b.scopeDigest != bound.scopeDigest then
        refuse("were generated for a different source")
      else if bound.units.exists(u => strict.get(u).isEmpty) then refuse("are missing a unit")
      else Right(())
    check.flatMap { _ =>
      val sets = bound.units.map(u => strict.get(u).get)
      val provenance = new CandidateProvenance.Strict(
        strict.policy,
        sets.map(_.overflow),
        sets.map(_.uniformSemantic)
      )
      build(recall, snapshot, strict.candidates, provenance, costModel, gate = true)
    }

  private def build(
      recall: RecallGraph[Checked],
      source: SourceView,
      candidates: Candidates,
      provenance: CandidateProvenance,
      costModel: LocalCostModel,
      gate: Boolean
  ): Either[AlignError, LocalEvidence] =
    val units = recall.ordered
    if units.isEmpty then Left(AlignError.EmptyRecall)
    else
      // One snapshot prices and identifies the evidence, so a mutable view cannot change between.
      val view = MappingBindingRender.snapshot(source)
      val admissibility: Vector[Map[SourceNodeRef, Admissibility]] = units.map { u =>
        candidates
          .set(u.id)
          .ranked
          .flatMap { ref =>
            view.node(ref).map { n =>
              ref -> (if gate then ModeGate.assess(u, n, view) else Admissibility.faithfulOnly)
            }
          }
          .toMap
      }
      val breakdowns: Either[AlignError, Vector[Map[AlignState, CostBreakdown]]] =
        units.zip(admissibility).traverse { (u, adm) =>
          val set = candidates.set(u.id)
          val sources = set.ranked.flatMap { ref =>
            view.node(ref).toVector.flatMap { n =>
              adm(ref).modes.map(m => AlignState.anchored(ref, m) -> costModel.cost(u, n, m, view))
            }
          }
          val externals =
            if set.abstained && set.ranked.isEmpty then Vector(AlignState.unranked)
            else AlignState.externals
          val ext = externals.traverse {
            case s @ AlignState.External(x) =>
              CostBreakdown.external(costModel.externalCost(u, x)).map(s -> _)
            case s => Right(s -> CostBreakdown.unreachable)
          }
          ext.map(values => (sources ++ values).toMap)
        }
      val ids = units.map(_.id)
      val nominated = ids.map(id => candidates.anchorsOf(id).filter(ref => view.node(ref).nonEmpty))
      breakdowns.map { b =>
        new LocalEvidence(
          AlignWire.recallChecksum(recall),
          MappingBindingRender.recall(recall),
          ViewFingerprint.of(view),
          MappingSourceRender.scope(view.nodes),
          gate,
          provenance,
          ids,
          ids.map(candidates.set),
          nominated,
          admissibility,
          b
        )
      }

  /** Refuse evidence computed for another recall, another view, or under the other gate setting. */
  private[align] def bound(
      evidence: LocalEvidence,
      recall: RecallGraph[Checked],
      view: SourceView,
      gate: Boolean
  ): Either[AlignError, Unit] =
    def refuse(detail: String) = Left(AlignError.InconsistentResult(s"local evidence $detail"))
    if evidence.gated != gate then
      refuse(s"was computed with gate=${evidence.gated}; this inference requires gate=$gate")
    else if evidence.units != recall.ordered.map(_.id) then refuse("covers different recall units")
    else if evidence.recallChecksum != AlignWire.recallChecksum(recall) ||
      evidence.recallSupplement != MappingBindingRender.recall(recall)
    then refuse("was computed for a different recall")
    else if evidence.viewFingerprint != ViewFingerprint.of(view) ||
      evidence.scopeDigest != MappingSourceRender.scope(view.nodes)
    then refuse("was computed for a different source view")
    else Right(())

/** Content identity of a [[LocalEvidence]]. Only [[LocalEvidence]] derives one: there is no public
  * way to turn a checksum into an identity, so a caller cannot assert that two computations shared
  * evidence. It can only compare identities the library derived.
  */
object LocalEvidenceId:
  opaque type LocalEvidenceId = Checksum

  extension (id: LocalEvidenceId) def checksum: Checksum = id

  private[align] def derive(e: LocalEvidence): LocalEvidenceId =
    import MappingRender.{optional, sequence}
    def number(d: Double): String = CanonicalDouble.render(d)
    def nomination(n: Nomination): String = sequence(
      Vector(
        n.ref.key,
        n.channel,
        n.rank.toString,
        optional(n.rawScore.map(number)),
        optional(n.space),
        optional(n.receipt)
      )
    )
    def admissibility(ref: SourceNodeRef, a: Admissibility): String = sequence(
      Vector(
        ref.key,
        sequence(a.contradictions.map(_.toString).sorted),
        a.faithful.toString,
        sequence(a.facets.toVector.sorted.map(_.toString))
      )
    )
    val perUnit = e.units.indices.toVector.map { i =>
      val set = e.candidates(i)
      sequence(
        Vector(
          e.units(i).value,
          set.abstained.toString,
          sequence(
            set.nominations.map(nomination).sorted
          ),
          sequence(e.nominated(i).map(_.key)),
          sequence(e.admissibility(i).toVector.sortBy(_._1.key).map(admissibility)),
          sequence(
            e.breakdowns(i).toVector.sortBy(_._1.key).map { (state, cost) =>
              sequence(
                Vector(sequence(AlignState.keyParts(state)), MappingBindingRender.cost(cost))
              )
            }
          )
        )
      )
    }
    MappingRender.digest(
      Vector(
        "local-evidence/v3",
        e.recallChecksum.hex,
        e.recallSupplement.hex,
        e.viewFingerprint.checksum.hex,
        e.scopeDigest.hex,
        e.gated.toString,
        CandidateProvenance.render(e.provenance)
      ) ++ perUnit
    )

type LocalEvidenceId = LocalEvidenceId.LocalEvidenceId

/** Where a [[LocalEvidence]]'s candidates came from.
  *
  * Why: evidence built from plain [[Candidates]] cannot tell generator output from hand-built sets,
  * so it claims nothing (`Unattested`). Evidence built from [[StrictCandidates]] records the tie
  * policy and, per unit in recall order, what that policy withheld or observed.
  */
sealed trait CandidateProvenance
object CandidateProvenance:
  case object Unattested extends CandidateProvenance

  final class Strict private[align] (
      val policy: CandidateTiePolicy,
      val overflow: Vector[Vector[TieOverflow]],
      val uniformSemantic: Vector[Vector[UniformSemanticScores]]
  ) extends CandidateProvenance:
    /** True when some level of unit `i` exceeded its budget: a strict reference refuses it. */
    def overflowed(i: Int): Boolean = overflow.lift(i).exists(_.nonEmpty)
    override def equals(that: Any): Boolean = that match
      case o: Strict =>
        policy == o.policy && overflow == o.overflow && uniformSemantic == o.uniformSemantic
      case _ => false
    override def hashCode: Int = (policy, overflow, uniformSemantic).hashCode
    override def toString: String = s"Strict($policy)"

  private[align] def render(p: CandidateProvenance): String =
    import MappingRender.sequence
    p match
      case Unattested => sequence(Vector("unattested"))
      case s: Strict  =>
        sequence(
          Vector(
            "strict",
            sequence(CandidateTiePolicy.render(s.policy)),
            sequence(
              s.overflow.map(os =>
                sequence(
                  os.map(o => sequence(Vector(o.level, o.unionSize, o.budget).map(_.toString)))
                )
              )
            ),
            sequence(
              s.uniformSemantic.map(us =>
                sequence(us.map(u => sequence(Vector(u.level, u.scoredCount).map(_.toString))))
              )
            )
          )
        )
