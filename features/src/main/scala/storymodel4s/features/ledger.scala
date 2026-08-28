package storymodel4s.features

import storymodel4s.core.*

/** Why a feature was used. Inductive uses shape the structure that descriptive/predictive analyses
  * later condition on, so they must be declared (design record §115–116).
  */
enum UsePurpose:
  case Exploratory, Confirmatory
  case Induction(stage: StageId)

/** One recorded use of feature spaces: which spaces participated and which were deliberately
  * withheld (e.g. to build boundaries without the feature under test).
  */
final case class FeatureUse(
    purpose: UsePurpose,
    spaces: Set[FeatureSpaceId],
    excluded: Set[FeatureSpaceId],
    note: Option[String] = None
):
  def effective: Set[FeatureSpaceId] = spaces.diff(excluded)

/** Append-only record of feature uses within a build or analysis. */
final case class FeatureUseLedger private (uses: Vector[FeatureUse]):
  def record(use: FeatureUse): FeatureUseLedger = FeatureUseLedger(uses :+ use)
  def inductionUses: Vector[FeatureUse] = uses.filter {
    case FeatureUse(UsePurpose.Induction(_), _, _, _) => true
    case _                                            => false
  }
  def size: Int = uses.size

  /** Score one candidate boundary for an induction stage, recording the feature use that fed it.
    *
    * This is the only way to obtain a [[BoundaryScore]]: the ledger entry and the score are
    * produced together, so an induction step cannot forget to declare its inputs. Returns the same
    * ledger and `None` when no weighted signal is observed at the evidence's level (nothing was
    * used, so nothing is recorded).
    */
  def scoreBoundary(
      weights: BoundaryBeliefInput,
      evidence: BoundaryEvidence,
      stage: StageId,
      excluded: Set[FeatureSpaceId] = Set.empty
  ): (FeatureUseLedger, Option[BoundaryScore]) =
    weights.rawScoreUnrecorded(evidence) match
      case None      => (this, None)
      case Some(raw) =>
        val use = FeatureUse(UsePurpose.Induction(stage), weights.usedSpaces(evidence), excluded)
        val score = BoundaryScore(evidence.target, evidence.level, raw, use)
        (record(use), Some(score))

object FeatureUseLedger:
  val empty: FeatureUseLedger = FeatureUseLedger(Vector.empty)

/** A detected circularity: an analysis tests a feature that helped construct the structure it
  * conditions on.
  */
final case class CircularityWarning(
    analysisSpaces: Set[FeatureSpaceId],
    inductionStages: Set[StageId],
    sharedSpaces: Set[FeatureSpaceId],
    viaDerivation: Set[FeatureSpaceId]
):
  def message: String =
    s"analysis features ${sharedSpaces.map(_.value).mkString(",")} participated in induction stages " +
      s"${inductionStages.map(_.value).mkString(",")}" +
      (if viaDerivation.nonEmpty then
         s" (through derived spaces ${viaDerivation.map(_.value).mkString(",")})"
       else "")

/** Detects feature leakage between induction and analysis (§116).
  *
  * A space leaks if it, or any space derived from it, or any of its inputs, was an effective input
  * to an induction use. Derivation ancestry is followed in both directions because a smoothed
  * imageability track and its raw source are the same scientific variable.
  */
object CircularityCheck:
  def dependsOn(
      analysisSpaces: Set[FeatureSpaceId],
      inductionUses: Vector[FeatureUse],
      graph: DerivationGraph = DerivationGraph.empty
  ): Option[CircularityWarning] =
    val induction = inductionUses.collect { case u @ FeatureUse(UsePurpose.Induction(_), _, _, _) =>
      u
    }
    if induction.isEmpty then None
    else
      val inductionInputs: Set[FeatureSpaceId] = induction.flatMap(_.effective).toSet
      // expand induction inputs to their raw ancestry so derived/raw variants match
      val inductionRoots = inductionInputs.flatMap(s => graph.ancestors(s) + s)
      val analysisRoots = analysisSpaces.flatMap(s => graph.ancestors(s) + s)
      val sharedRoots = inductionRoots.intersect(analysisRoots)
      if sharedRoots.isEmpty then None
      else
        val direct = analysisSpaces.intersect(inductionInputs)
        val via = sharedRoots.diff(direct)
        val stages = induction.collect {
          case FeatureUse(UsePurpose.Induction(st), sp, ex, _)
              if sp.diff(ex).flatMap(s => graph.ancestors(s) + s).exists(sharedRoots) =>
            st
        }.toSet
        Some(
          CircularityWarning(
            analysisSpaces,
            stages,
            if direct.nonEmpty then direct else sharedRoots,
            via
          )
        )
