package storymodel4s.align

import storymodel4s.core.*

/** Emits the checked synthetic TemporalQuerySuite concentration fixture as one replayable JSON row.
  *
  * Copy beside TemporalQuerySuite in a clean 7a5f1f591 clone before running it.
  */
object TemporalConcentrationJvmEvidence:
  import SherlockShapedSource.{a, e, bundles, physical, view}
  import MappingMeasureFixture.{ledger, recall, stage, stages}
  import TemporalConcentration.Target
  import TemporalQuery.*

  private val inventory = MappingMeasureFixture.inventory(recall)
  private val unit = inventory.units.head.id
  private val selection = TemporalSupport.Selection.Part(a.identity)
  private val external = Destination.External(ExternalState.Intrusion)
  private val weights = Map(
    Destination.Target(e(1)) -> 0.5,
    Destination.Target(e(2)) -> 0.25,
    Destination.Target(e(4)) -> 0.125,
    external -> 0.125
  )

  private def right[A](value: Either[?, A]): A = value.fold(error => sys.error(error.toString), identity)

  private def support(
      bundle: SourceBundle,
      intervals: Vector[(Long, Long)],
      points: Vector[Long]
  ): SourceSupportStatus =
    val axis = bundle.primaryAxis
    val stream = bundle.streams.head.id
    val anchors = intervals.map((lo, hi) =>
      EvidenceAnchor.MediaTime(
        bundle.id,
        stream,
        axis.id,
        PlaybackIntervalSet.one(right(PlaybackInterval.on(axis, lo, hi)))
      )
    ) ++ points.map(p =>
      EvidenceAnchor.MediaPoint(bundle.id, stream, PlaybackInstant.on(axis, p).toOption.get)
    )
    SourceSupportStatus.located(TypedSupport.Anchored(right(EvidenceSupport.of(bundle, anchors))))

  private val source = right(
    SourceRepresentation.of(
      view,
      bundles,
      None,
      physical(false)
        .updated(e(1), support(a, Vector(0L -> 2L, 8L -> 10L), Vector.empty))
        .updated(e(2), support(a, Vector.empty, Vector(4L)))
    )
  )

  private val mapping =
    val grain = TargetGrain.Hierarchy(source.targets.map(_.level).distinct.sorted)
    val universe = right(DeclaredUniverse.of(source.targets.map(_.ref), grain))
    val prior = ReferencePriorId.unsafe("synthetic")
    val policy = right(
      MappingPolicies.of(
        InferencePolicy.Unspecified("synthetic"),
        ContextPolicy.Unspecified("none"),
        CandidatePolicy.Declared(CandidatePolicyId.unsafe("fixture"), CandidateCoverage.Truncated(8)),
        ReferencePrior.Declared(prior),
        DecisionPolicy.NotApplicable("synthetic"),
        universe
      )
    )
    val roles = right(
      UnitRoles.of(
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.InferenceUnit(inventory.segmentation),
        AnalysisGrain.Targets(grain)
      )
    )
    val outcomes = inventory.units.map { u =>
      val normalized = right(NormalizedScoreMass.of(universe.id, prior, 1.0, weights, stage(Stage.Scoring)))
      val measures = right(UnitMeasures.of(Vector.empty, Some(normalized), None, None))
      val candidates = CandidateSetId.of(stages.candidates, u.id, weights.keySet)
      val links = weights.keys.toVector.map(MappingLink.ungated(_, stages, candidates))
      right(
        UnitOutcome.computed(
          u.id,
          measures,
          links,
          DecisionBasis.of(MeasureKind.NormalizedScoreMass, None).toOption.get,
          DecisionRequest.RawArgmax,
          stages
        )
      )
    }
    right(MappingResult.checked(inventory, source, policy, roles, ledger, outcomes))

  private val declarations = Vector(
    Declaration(e(1), Assumption.SuppliedSupportContainsReferent, Allocation.UniformIntervals),
    Declaration(e(2), Assumption.SuppliedSupportContainsReferent, Allocation.UniformPoints),
    Declaration(e(4), Assumption.SuppliedSupportContainsReferent, Allocation.UniformIntervals)
  )
  private val prepared = right(prepare(mapping, unit, Measure.NormalizedScoreMass, selection, declarations))

  private def quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def number(value: Double): String = java.lang.Double.toString(value)
  private def region(value: Region): String =
    val geometry = value.geometry.get
    support(geometry)
  private def support(value: PlaybackSupport): String =
    val intervals = value.intervals.map(i => s"[${i.start},${i.endExclusive}]").mkString("[", ",", "]")
    val points = value.points.map(p => p.at.toString).mkString("[", ",", "]")
    s"{\"intervals\":$intervals,\"points\":$points}"
  private def axis: String = prepared.coordinate.axis.extent match
    case extent: AxisExtent.PlaybackTicks =>
      s"{\"kind\":${quote(prepared.coordinate.axis.kind.toString)},\"start\":${extent.start},\"endExclusive\":${extent.endExclusive}}"
    case other => s"{\"kind\":${quote(prepared.coordinate.axis.kind.toString)},\"extent\":${quote(other.toString)}}"
  private def component(value: Component): String = value.location match
    case Location.Resolved(domain) =>
      s"{\"alternative\":${quote(value.alternative.key)},\"weight\":${number(value.weight)},\"location\":\"resolved\",\"support\":${support(domain.availability.toOption.get)},\"allocation\":${quote(domain.allocation.toOption.get.toString)}}"
    case Location.Unresolved(reason) =>
      s"{\"alternative\":${quote(value.alternative.key)},\"weight\":${number(value.weight)},\"location\":\"unresolved\",\"reason\":${quote(reason.toString)}}"
    case Location.External(state) =>
      s"{\"alternative\":${quote(value.alternative.key)},\"weight\":${number(value.weight)},\"location\":\"external\",\"state\":${quote(state.toString)}}"
  private def window(value: TemporalConcentration.Window): String =
    s"{\"width\":${value.width},\"region\":${region(value.readout.region)},\"allocatedRegionMass\":${number(value.readout.allocatedRegionMass)},\"target\":${value.target.map(t => quote(t.toString)).getOrElse("null")}}"
  private def ledgerJson: String =
    val l = prepared.ledger
    val intrusion = l.external.find(_._1 == ExternalState.Intrusion).map(_._2).getOrElse(0.0)
    s"{\"suppliedTotal\":${number(l.suppliedTotal)},\"allocatedSource\":${number(l.allocatedSource)},\"supportWithoutAllocation\":${number(l.supportWithoutAllocation)},\"unavailableLocation\":${number(l.unavailableLocation)},\"rowBudget\":${number(l.rowBudget)},\"rowSumDiscrepancy\":${number(l.rowSumDiscrepancy)},\"external\":[{\"state\":${quote(ExternalState.Intrusion.toString)},\"mass\":${number(intrusion)}}]}"

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: TemporalConcentrationJvmEvidence <reference-sha>")
    val grid = right(TemporalGrid.regular(prepared, 0, 10, BigInt(1)))
    val crop = right(TemporalGrid.regular(prepared, 0, 2, BigInt(1)))
    val cells = grid.regions.indices.map(i => number(grid.allocatedAt(i).get)).mkString("[", ",", "]")
    val cropCells = crop.regions.indices.map(i => number(crop.allocatedAt(i).get)).mkString("[", ",", "]")
    val best = right(TemporalConcentration.bestWindow(prepared, BigInt(1)))
    val shortest = right(TemporalConcentration.shortestWindow(prepared, Target.AbsoluteQuantity(0.5)))
    val disconnected = right(TemporalConcentration.disconnected(grid, Target.AbsoluteQuantity(0.5)))
    val unattainable = TemporalConcentration.shortestWindow(prepared, Target.ShareOfSupplied(0.9)).left.toOption.get
    val invalidWidth = TemporalConcentration.bestWindow(prepared, BigInt(0)).left.toOption.get
    val invalidTarget = TemporalConcentration.shortestWindow(prepared, Target.ShareOfSupplied(Double.NaN)).left.toOption.get
    println(
      s"{\"schema\":\"temporal-concentration-jvm-oracle/v1\",\"referenceSha\":${quote(args.head)},\"input\":{\"axis\":$axis,\"components\":${prepared.components.map(component).mkString("[", ",", "]")},\"ledger\":$ledgerJson},\"expected\":{\"bestWindowWidth1\":${window(best)},\"shortestAbsoluteHalf\":${window(shortest)},\"unitGridAllocated\":$cells,\"disconnectedAbsoluteHalf\":{\"selected\":${disconnected.selected.mkString("[", ",", "]")},\"quantity\":${number(disconnected.quantity)}},\"crop0To2\":{\"allocated\":$cropCells,\"allocatedOutside\":${number(crop.allocatedOutside)},\"allocationDiscrepancy\":${number(crop.allocationDiscrepancy)}},\"refusals\":{\"unattainableSupplied90\":${quote(unattainable.toString)},\"invalidWidth\":${quote(invalidWidth.toString)},\"invalidTarget\":${quote(invalidTarget.toString)}}}}"
    )
