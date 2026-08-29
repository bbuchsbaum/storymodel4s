package storymodel4s.align

import storymodel4s.core.{Checksum, ContentAddress, SpanRef}
import storymodel4s.features.{CanonicalDouble, Coverage, Estimate}
import storymodel4s.proposition.Canonical
import storymodel4s.recall.{ExpressedUncertainty, RecallGraph, RecallUnitId}
import storymodel4s.recall.RecallGraphStatus.Checked

/** Align-local tagged, length-prefixed rendering shared by the wire digests (ADR 0001 §D5, "wire
  * renderings"). Every digest is a `ContentAddress.digest` over a flat token vector in which every
  * value is preceded by its tag and by its own length (decimal code units) as a separate token, and
  * every list by its length, so no re-bracketing of adjacent values can collide even when a value
  * embeds the NUL separator (`outcome = "o\0cause\0c", cause = ""` NUL-joins to the same bytes as
  * `outcome = "o", cause = "c\0cause\0"` but length-prefixes differently); composite values
  * length-prefix each component the same way. Evidence identity is `proposition.Canonical.checksum`
  * — align never reaches the codec.
  */
private[align] object Render:
  val Sep: String = "\u0001"

  /** A component-wise length-prefixed join: injective on the component sequence. */
  def composite(parts: Iterable[String]): String =
    parts.map(x => s"${x.length}$Sep$x").mkString(Sep)

  /** The flat token vector of a digest: `tag, length, value` per field; `tag, count` then
    * `length, value` per element for a list.
    */
  final class Tokens:
    private val b = Vector.newBuilder[String]
    def field(name: String, v: String): Unit =
      b += name
      b += v.length.toString
      b += v
    def list(name: String, xs: Iterable[String]): Unit =
      b += name
      b += xs.size.toString
      xs.foreach { x =>
        b += x.length.toString
        b += x
      }
    def digest: Checksum = ContentAddress.digest(b.result())

  def spanRef(r: SpanRef): String =
    composite(
      Vector(r.unit.map(_.value).getOrElse(""), r.span.start.toString, r.span.endExclusive.toString)
    )

  /** The full `Estimate[Double]` variant: an observed value with its credence, or the reason. */
  def estimate(e: Estimate[Double]): String = e match
    case Estimate.Observed(v, credence) =>
      val c = credence.toVector.flatMap { c =>
        Vector(
          CanonicalDouble.render(c.rawScore),
          c.calibrated.map(p => CanonicalDouble.render(p.value)).getOrElse(""),
          c.calibrationModel.getOrElse("")
        )
      }
      composite(Vector("observed", CanonicalDouble.render(v)) ++ c)
    case Estimate.Missing(reason) => composite(Vector("missing", reason.toString))

/** Content address of a [[SourceView]] as the aligner reads it (`view-fingerprint/v1`, ADR 0001
  * §D5; bead `HsmmResult wire`). A gated result carries the fingerprint of the view it was proved
  * against so that a wire record decoded against a *different* view — one with the same node ids
  * but other predicates, participants, contexts, or edges — is refused rather than silently
  * re-gated.
  *
  * Why a content address and not the view's identity: two views built from the same story by the
  * same bridge must fingerprint equal on every platform, and any change to a field the gate or the
  * cost model reads must change it. Iteration order of the underlying maps never does. The field
  * list is versioned in the ADR: a change to what a gate or cost reads is a version bump.
  */
object ViewFingerprint:
  opaque type ViewFingerprint = Checksum

  /** Fingerprint of everything the aligner reads from `view`: every node summary field consulted by
    * the mode gate or a cost model (nodes sorted by reference), the sparse adjacency of every
    * relation layer (sorted, positive weights only, IEEE-754 rendered), the world order, and the
    * text length.
    */
  def of(view: SourceView): ViewFingerprint =
    val b = Render.Tokens()
    import b.{field, list}
    field("view-fingerprint", "v1")
    val nodes = view.nodes.sortBy(_.ref.key)
    field("nodes", nodes.size.toString)
    nodes.foreach { n =>
      field("ref", n.ref.key)
      field("level", n.level.toString)
      field("parent", n.parent.map(_.key).getOrElse(""))
      field("discoursePosition", n.discoursePosition.toString)
      list("support", n.support.refs.toVector.sorted.map(Render.spanRef))
      field("predicate", n.predicate.getOrElse(""))
      list(
        "participants",
        n.participants.map(p =>
          Render.composite(
            Vector(p.role.toString, p.label, p.aliases.size.toString) ++ p.aliases.toVector.sorted
          )
        )
      )
      field("context", n.context.toString)
      field("polarity", n.polarity.toString)
      field("modality", n.modality.toString)
      list("locations", n.locations)
      list("lemmas", n.lemmas.toVector.sorted)
      field("outcome", n.outcome.getOrElse(""))
      field("cause", n.cause.getOrElse(""))
      field("importance", Render.estimate(n.importance))
      field("evidence", n.evidence.map(e => Canonical.checksum(e.chart).hex).getOrElse(""))
    }
    RelationLayer.values.foreach { layer =>
      val entries = view
        .adjacency(layer)
        .toVector
        .flatMap { (a, row) => row.toVector.collect { case (c, w) if w > 0.0 => (a, c, w) } }
        .sortBy { (a, c, _) => (a.key, c.key) }
      list(
        layer.toString,
        entries.map { (a, c, w) =>
          Render.composite(Vector(a.key, c.key, CanonicalDouble.render(w)))
        }
      )
    }
    list(
      "worldOrder",
      view.worldOrder.toVector.flatMap(
        _.toVector.sortBy(_._1.key).map((r, i) => Render.composite(Vector(r.key, i.toString)))
      )
    )
    field("textLength", view.textLength.toString)
    b.digest

  /** Rehydrate a fingerprint carried on the wire; the value is compared, never trusted. */
  def fromChecksum(c: Checksum): ViewFingerprint = c

  extension (f: ViewFingerprint) def checksum: Checksum = f

type ViewFingerprint = ViewFingerprint.ViewFingerprint

extension (view: SourceView)
  /** The view's [[ViewFingerprint]]. An extension rather than a trait member for now: the
    * `SourceView` source is under another reservation; folding it into the trait as a memoized
    * member is a one-line follow-up with the same name and type.
    */
  def contentFingerprint: ViewFingerprint = ViewFingerprint.of(view)

/** Digest of a derived admissibility map, carried on the wire for **drift detection only**: the
  * decoder re-derives admissibility through the [[ModeGate]] and compares; a mismatch surfaces as
  * the typed [[AlignError.GateDrift]] ("the gate that produced this record is not the gate you are
  * running") instead of a bare gate violation. The echo is never authority.
  */
object AdmissibilityEcho:
  opaque type AdmissibilityEcho = Checksum

  def of(admissibility: Map[RecallUnitId, Map[SourceNodeRef, Admissibility]]): AdmissibilityEcho =
    val b = Render.Tokens()
    b.field("admissibility-echo", "v1")
    b.field("entries", admissibility.values.map(_.size).sum.toString)
    admissibility.toVector.sortBy(_._1.value).foreach { (u, m) =>
      m.toVector.sortBy(_._1.key).foreach { (ref, a) =>
        b.field("unit", u.value)
        b.field("anchor", ref.key)
        b.list("contradictions", a.contradictions.map(_.toString))
        b.field("faithful", a.faithful.toString)
        b.list("facets", a.facets.toVector.sorted.map(_.toString))
      }
    }
    b.digest

  def fromChecksum(c: Checksum): AdmissibilityEcho = c

  extension (e: AdmissibilityEcho) def checksum: Checksum = e

type AdmissibilityEcho = AdmissibilityEcho.AdmissibilityEcho

/** Validating factories for the *records* a gated result carries — [[CostBreakdown]] and the
  * structural-reduction receipts — plus the wire-side match checks. These are records, not proofs:
  * a decoder rebuilds them from parts through these factories, which refuse malformed or internally
  * inconsistent values (non-finite, negative, a mode that contradicts its terms, a reduced scalar
  * its receipt does not produce), and then hands the assembled parts to [[HsmmResult.validated]],
  * which is the proof.
  */
object AlignWire:
  /** Terms that may be absent because they can lack evidence. Sensory belongs: an empty
    * `sensoryTerms` list is no observation, not a perfect match. Chart and Structural belong
    * because a missing chart is not a zero distance. Semantic does not: it is dense and imputed.
    * This is not a permission list — a new term joins only if it can genuinely have no evidence.
    */
  private val MayBeMissing: Set[CostTerm] =
    Set(CostTerm.Chart, CostTerm.Structural, CostTerm.Sensory)

  /** Terms whose scalar is a reducer over a [[StructuralReductionReceipt]]. These are the terms
    * that have chart-sourced members. Sensory has no chart source, so it cannot carry a structural
    * receipt — a different property from being allowed to go missing.
    */
  private val HasReductionReceipt: Set[CostTerm] = Set(CostTerm.Chart, CostTerm.Structural)

  private def finite(d: Double): Boolean = !d.isNaN && !d.isInfinite
  private def bad(record: String, detail: String): AlignError =
    AlignError.MalformedRecord(record, detail)

  private def coverageOk(c: StructuralCoverage): Boolean =
    c.level >= 0 && c.members >= 0 && c.membersWithEvidence >= 0 &&
      c.membersWithEvidence <= c.members

  /** Content address of a recall as an alignment target (`recall-checksum/v1`, ADR 0001 §D5): the
    * transcript's canonical text and, in recall order, the **full** content of every unit that a
    * candidate channel, the gate, a cost model, or a provider reads — id, ordinal, exact spans,
    * text, discourse function, expressed uncertainty, the whole proposition sketch, grounding, and
    * the evidence chart's identity — plus the explicit temporal and causal relations refinement
    * reads. Same boundaries with different content therefore fail [[matched]]. The proof re-derives
    * this value and the decoder compares.
    */
  def recallChecksum(recall: RecallGraph[Checked]): Checksum =
    val b = Render.Tokens()
    import b.{field, list}
    def spans(s: storymodel4s.core.SpanSet): Vector[String] =
      s.refs.toVector.sorted.map(Render.spanRef)
    field("recall-checksum", "v1")
    field("transcript", recall.transcript.canonicalChecksum.hex)
    field("units", recall.ordered.size.toString)
    recall.ordered.foreach { u =>
      field("id", u.id.value)
      field("ordinal", u.ordinal.toString)
      list("span", spans(u.span))
      field("text", u.text)
      field("function", u.function.toString)
      u.expressedUncertainty match
        case ExpressedUncertainty.Unmarked       => field("uncertainty", "unmarked")
        case ExpressedUncertainty.Hedged(cues)   => list("uncertainty:hedged", spans(cues))
        case ExpressedUncertainty.Explicit(cues) => list("uncertainty:explicit", spans(cues))
      val p = u.proposition
      field("predicate", p.predicate.getOrElse(""))
      list(
        "participants",
        p.participants.map(sp =>
          Render.composite(
            Vector(
              sp.role.toString,
              sp.entity.map(_.value).getOrElse(""),
              sp.label,
              sp.specified.toString,
              sp.head,
              sp.determiner.map(_.toString).getOrElse(""),
              sp.number.map(_.toString).getOrElse("")
            ) ++ (sp.modifiers.size.toString +: sp.modifiers) ++
              (sp.aliases.size.toString +: sp.aliases.toVector.sorted)
          )
        )
      )
      field("polarity", p.polarity.toString)
      field("modality", p.modality.toString)
      list("locations", p.locations)
      list("times", p.times)
      list("sensoryTerms", p.sensoryTerms)
      list("lemmas", p.lemmas.toVector.sorted)
      field("outcome", p.outcome.getOrElse(""))
      field("cause", p.cause.getOrElse(""))
      field("grounding", u.grounding.map(g => CanonicalDouble.render(g.value)).getOrElse(""))
      field("evidence", u.evidence.map(e => Canonical.checksum(e.chart).hex).getOrElse(""))
    }
    list(
      "temporal",
      recall.relations.temporal
        .map(e => Render.composite(Vector(e.from.value, e.relation.toString, e.to.value)))
        .sorted
    )
    list(
      "causal",
      recall.relations.causal
        .map(e => Render.composite(Vector(e.cause.value, e.effect.value)))
        .sorted
    )
    b.digest

  /** Rebuild a [[CostBreakdown]] from its parts (fields mirrored exactly).
    *
    * Stated residual: `total` is a **cached value, not verified on the wire** — the weights and the
    * function prior that produced it are not on the record, so the factory checks only that it is
    * finite and nonnegative. A consumer that needs the total re-derivable must carry the
    * `CostWeights`/`FunctionPrior` alongside the wire and recompute.
    */
  def costBreakdown(
      terms: Map[CostTerm, Double],
      mode: Option[FidelityMode],
      exclusion: Option[Exclusion],
      total: Double,
      missingTerms: Set[CostTerm],
      sourceChartCoverage: Option[StructuralCoverage],
      reductions: Map[CostTerm, StructuralReductionReceipt],
      // NO DEFAULT, deliberately. A default lets a caller omit the field and fabricate maximal
      // support, which is exactly how the laws round-trip site compiled while dropping it. Making
      // it required turns every reconstruction into a compile error the author must answer.
      supportWeight: Double
  ): Either[AlignError, CostBreakdown] =
    val r = "CostBreakdown"
    val badTerm = terms.toVector.sortBy(_._1.ordinal).collectFirst {
      case (t, v) if !finite(v) || v < 0.0 => bad(r, s"term $t is not finite and nonnegative")
    }
    val checks: Vector[Option[AlignError]] = Vector(
      // Explicit finiteness rather than a comparison against 0: `NaN <= 0.0` is false, so a bare
      // range guard would FAIL OPEN and admit a NaN support (AGENTS.md rule 7).
      // Range is [0, 1], NOT (0, 1]. ZERO IS A PRODUCIBLE AND HONEST VALUE: a cell where nothing
      // eligible was measured rests on no support at all, and supportOf returns exactly 0 for it.
      // Refusing zero would mean the producer can emit a value its own checked constructor rejects
      // - and the alternative, making supportOf return 1.0 when nothing was measured, would
      // fabricate FULL support for a cell that measured nothing, which is the defect this whole
      // change exists to remove. Finiteness is tested explicitly because NaN fails every
      // comparison and a bare range check would admit it (AGENTS.md rule 7).
      Option.when(!finite(supportWeight) || supportWeight < 0.0 || supportWeight > 1.0)(
        bad(r, "supportWeight must be finite and in [0, 1]")
      ),
      badTerm,
      Option.when(!finite(total) || total < 0.0)(bad(r, "total is not finite and nonnegative")),
      Option.when(!missingTerms.subsetOf(MayBeMissing))(
        bad(r, "missingTerms may name only terms that can lack evidence")
      ),
      Option.when(missingTerms.exists(terms.contains))(
        bad(r, "a term cannot be both present and missing")
      ),
      Option.when(!reductions.keySet.subsetOf(HasReductionReceipt))(
        bad(r, "reductions may be recorded only for terms that have a chart-sourced receipt")
      ),
      Option.when(exclusion.nonEmpty && (terms.nonEmpty || mode.nonEmpty))(
        bad(r, "an excluded state carries neither terms nor a mode")
      ),
      Option.when(mode.isEmpty && exclusion.isEmpty && terms.nonEmpty)(
        bad(r, "an external state carries no content terms")
      ),
      Option.when(mode.exists(_.isFaithful) && terms.getOrElse(CostTerm.Distortion, 0.0) != 0.0)(
        bad(r, "a faithful state cannot carry a distortion penalty")
      ),
      Option.when(sourceChartCoverage.exists(c => !coverageOk(c)))(
        bad(r, "sourceChartCoverage is malformed")
      ),
      // Receipt coherence: a chart-sourced term is exactly the reducer over its receipt's
      // observed members (clamped as the cost model clamps), a missing receipt term has a
      // receipt that reduces to nothing, and every receipt's source-chart coverage is the
      // breakdown's. Sensory is allowed to be missing without a receipt.
      HasReductionReceipt.toVector
        .sortBy(_.ordinal)
        .flatMap { t =>
          val reduced = reductions
            .get(t)
            .flatMap(rc => rc.reducer.reduce(rc.members.flatMap(_.estimate.toOption)))
          (terms.get(t), reduced) match
            case (Some(_), _) if !reductions.contains(t) =>
              Some(bad(r, s"term $t is present without its reduction receipt"))
            case (Some(v), Some(x)) if clamp(x) != v =>
              Some(bad(r, s"term $t is $v but its receipt reduces to ${clamp(x)}"))
            case (Some(v), None) =>
              Some(bad(r, s"term $t is $v but its receipt reduces to nothing"))
            case (None, Some(x)) =>
              Some(bad(r, s"term $t is missing but its receipt reduces to $x"))
            case (None, None) if reductions.contains(t) && !missingTerms.contains(t) =>
              Some(bad(r, s"term $t reduces to nothing but is not recorded as missing"))
            case _ => None
        }
        .headOption,
      reductions.toVector.sortBy(_._1.ordinal).collectFirst {
        case (t, rc) if !sourceChartCoverage.contains(rc.sourceChartCoverage) =>
          bad(r, s"the $t receipt's sourceChartCoverage differs from the breakdown's")
      }
    )
    checks.flatten.headOption.toLeft(
      CostBreakdown(
        terms,
        mode,
        exclusion,
        total,
        missingTerms,
        sourceChartCoverage,
        reductions,
        supportWeight
      )
    )

  /** The cost model's clamp of an optional term into `[0, 1]` (`DefaultLocalCostModel.clamp`). */
  private def clamp(x: Double): Double =
    if x.isNaN then 1.0 else math.max(0.0, math.min(1.0, x))

  /** One compatible member and its estimate; an observed value must be finite. */
  def memberEstimate(
      member: SourceNodeRef,
      estimate: Estimate[Double]
  ): Either[AlignError, StructuralMemberEstimate] =
    estimate match
      case Estimate.Observed(v, _) if !finite(v) =>
        Left(bad("StructuralMemberEstimate", s"member ${member.key} has a non-finite estimate"))
      case _ => Right(StructuralMemberEstimate(member, estimate))

  /** One excluded member; an exclusion must name at least one contradiction. */
  def memberExclusion(
      member: SourceNodeRef,
      contradictions: Set[Contradiction]
  ): Either[AlignError, StructuralMemberExclusion] =
    if contradictions.isEmpty then
      Left(bad("StructuralMemberExclusion", s"member ${member.key} is excluded for no reason"))
    else Right(StructuralMemberExclusion(member, contradictions))

  private def canonical(refs: Vector[SourceNodeRef]): Boolean =
    refs.map(_.key) == refs.map(_.key).distinct.sorted

  /** A reduction receipt: members and exclusions in canonical order, disjoint, with the observed
    * estimate coverage counted over exactly these members.
    */
  def reductionReceipt(
      reducer: StructuralReducer,
      members: Vector[StructuralMemberEstimate],
      excludedMembers: Vector[StructuralMemberExclusion],
      sourceChartCoverage: StructuralCoverage,
      observedEstimateCoverage: Coverage
  ): Either[AlignError, StructuralReductionReceipt] =
    val r = "StructuralReductionReceipt"
    val expected = Coverage.unsafe(members.size, members.count(_.estimate.isObserved))
    val checks: Vector[Option[AlignError]] = Vector(
      Option.when(!canonical(members.map(_.member)))(
        bad(r, "members must be sorted by reference and unique")
      ),
      Option.when(!canonical(excludedMembers.map(_.member)))(
        bad(r, "excludedMembers must be sorted by reference and unique")
      ),
      Option.when(
        members.map(_.member).toSet.intersect(excludedMembers.map(_.member).toSet).nonEmpty
      )(
        bad(r, "a member cannot be both compatible and excluded")
      ),
      Option.when(observedEstimateCoverage != expected)(
        bad(
          r,
          s"observedEstimateCoverage ${observedEstimateCoverage.observed}/${observedEstimateCoverage.eligible} " +
            s"does not count the members (${expected.observed}/${expected.eligible})"
        )
      ),
      Option.when(!coverageOk(sourceChartCoverage))(bad(r, "sourceChartCoverage is malformed"))
    )
    checks.flatten.headOption.toLeft(
      StructuralReductionReceipt(
        reducer,
        members,
        excludedMembers,
        sourceChartCoverage,
        observedEstimateCoverage
      )
    )

  /** A structural reduction: the scalar must be what the receipt's reducer produces over the
    * receipt's observed member estimates, so a flattering distance cannot ride a receipt that does
    * not yield it — and a `Missing` estimate cannot hide members that do reduce to a value.
    */
  def structuralReduction(
      estimate: Estimate[Double],
      receipt: StructuralReductionReceipt
  ): Either[AlignError, StructuralReduction] =
    val r = "StructuralReduction"
    val observed = receipt.members.flatMap(_.estimate.toOption)
    estimate match
      case Estimate.Observed(v, _) if !finite(v) => Left(bad(r, "estimate is not finite"))
      case Estimate.Observed(v, _)               =>
        receipt.reducer.reduce(observed) match
          case Some(expected) if expected == v => Right(StructuralReduction(estimate, receipt))
          case Some(expected)                  =>
            Left(bad(r, s"estimate $v is not the ${receipt.reducer} of the members ($expected)"))
          case None => Left(bad(r, "an observed estimate needs an observed member"))
      case Estimate.Missing(_) =>
        receipt.reducer.reduce(observed) match
          case Some(x) => Left(bad(r, s"estimate is missing but the members reduce to $x"))
          case None    => Right(StructuralReduction(estimate, receipt))

  /** The decoder's mandatory match: the fingerprints carried on the wire must equal the values the
    * proof derived from the recall and view in hand.
    */
  def matched(
      result: HsmmResult,
      viewFingerprint: ViewFingerprint,
      recallChecksum: Checksum
  ): Either[AlignError, HsmmResult] =
    if result.viewFingerprint != viewFingerprint then
      Left(
        AlignError.FingerprintMismatch(
          "viewFingerprint",
          viewFingerprint.checksum.hex,
          result.viewFingerprint.checksum.hex
        )
      )
    else if result.recallChecksum != recallChecksum then
      Left(
        AlignError.FingerprintMismatch(
          "recallChecksum",
          recallChecksum.hex,
          result.recallChecksum.hex
        )
      )
    else Right(result)
