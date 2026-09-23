package storymodel4s.codec

import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.view.*

/** A conservative adapter to the existing clock projection. It never computes a mapping, changes
  * the selected decision, rounds a clock, or drops a unit from the common workspace inventory.
  */
object WorkspaceVoyage:
  val Version = "workspace-voyage/v1"

  enum Unavailable:
    case ClocksNotSupplied, RecallStartUndeclared, ProcessingNotComplete, PosteriorNotSupplied,
      DecisionNotPosteriorArgmax, DecisionDiffersFromSourceArgmax, ClockNotRepresentable

  enum Disposition:
    case Plotted, Untimed
    case Unsupported(reason: Unavailable)

  final case class UnitDisposition(unit: RecallUnitId, ordinal: Int, disposition: Disposition)

  final class Projection private[WorkspaceVoyage] (
      val policy: ArtifactId,
      val mappingDigest: Checksum,
      val document: Option[RecallVoyageDocument],
      val units: Vector[UnitDisposition],
      val addresses: Map[Address, Set[Address]]
  ):
    def workspaceSelection(legacy: Address): Set[Address] = addresses.getOrElse(legacy, Set.empty)
    def legacySelection(selected: Set[Address]): Set[Address] = addresses.collect {
      case (legacy, qualified) if qualified.exists(selected.contains) => legacy
    }.toSet

  private final case class Row(unit: VoyageUnit, posterior: AlignmentRow, decision: VoyageDecision)

  /** All mappings remain available through the matrix and inspector. Only genuinely compatible
    * posterior-argmax rows enter the legacy Voyage input; every other row has a typed disposition.
    */
  def from(
      workspace: SourceRecallWorkspace,
      policyId: ArtifactId
  ): Either[WorkspaceRefusal, Projection] =
    workspace.policy(policyId).toRight(WorkspaceRefusal.IncompatiblePolicy).flatMap { policy =>
      def unavailable(reason: Unavailable): Projection = new Projection(
        policy.id,
        policy.record.digest,
        None,
        workspace.inventory.units
          .map(u => UnitDisposition(u.id, u.ordinal, Disposition.Unsupported(reason))),
        Map.empty
      )
      workspace.clocks match
        case None => Right(unavailable(Unavailable.ClocksNotSupplied))
        case Some(clocks) if clocks.origin == WorkspaceClockOrigin.Unestablished =>
          Right(unavailable(Unavailable.RecallStartUndeclared))
        case Some(clocks) =>
          val attempted = workspace.inventory.units.map { member =>
            val outcome = policy.record.outcome(member.id).get
            val row = for
              _ <- Either.cond(
                outcome.processing == ProcessingStatus.Complete,
                (),
                Unavailable.ProcessingNotComplete
              )
              posterior <- outcome.measures.posterior.toRight(Unavailable.PosteriorNotSupplied)
              decision <- outcome.decision
                .filter(d =>
                  d.basis.kind == MeasureKind.ModelPosterior &&
                    d.origin == DecisionOrigin.RawArgmax && d.request == DecisionRequest.RawArgmax
                )
                .toRight(Unavailable.DecisionNotPosteriorArgmax)
              alignment <- AlignmentRow
                .of(member.id, posterior.mass)
                .left
                .map(_ => Unavailable.PosteriorNotSupplied)
              anchor = decision.chosen.collect { case Destination.Target(ref) => ref }
              _ <- Either.cond(
                decision.chosen.nonEmpty && anchor == alignment.mapSource,
                (),
                Unavailable.DecisionDiffersFromSourceArgmax
              )
              // A later available word is not the first member; interval end is not last-word onset.
              onset <- member.words.headOption.flatMap(clocks.words.entry).map(_.observation) match
                case None | Some(RecallTiming.Observation.Missing(_)) => Right(None)
                case Some(RecallTiming.Observation.OnsetOnly(at, _))  =>
                  WorkspaceClocksCodec
                    .legacySeconds(at)
                    .map(Some(_))
                    .toRight(Unavailable.ClockNotRepresentable)
                case Some(RecallTiming.Observation.Interval(start, _, _)) =>
                  WorkspaceClocksCodec
                    .legacySeconds(start)
                    .map(Some(_))
                    .toRight(Unavailable.ClockNotRepresentable)
            yield Row(
              VoyageUnit(
                member.id,
                member.ordinal,
                workspace.recallEvidence(member.id).toOption.get.map(_._2).mkString(" … "),
                onset,
                None
              ),
              alignment,
              VoyageDecision(member.id, anchor, None, AnchorOrigin.PosteriorArgmax)
            )
            member -> row
          }
          val rows = attempted.flatMap(_._2.toOption)
          val dispositions = attempted.map { (member, result) =>
            UnitDisposition(
              member.id,
              member.ordinal,
              result match
                case Left(reason) => Disposition.Unsupported(reason)
                case Right(row)   =>
                  if row.unit.onset.isDefined then Disposition.Plotted else Disposition.Untimed
            )
          }
          val document =
            if rows.isEmpty then Right(None)
            else
              for
                matrix <- AlignmentMatrix
                  .of(rows.map(_.posterior))
                  .left
                  .map(_ => WorkspaceRefusal.SemanticJoinMismatch)
                input <- RecallVoyageInput
                  .of(
                    rows.map(_.unit),
                    matrix,
                    clocks.sourceTimeline,
                    rows.map(_.decision),
                    None,
                    clocks.recallExtent
                  )
                  .left
                  .map(_ => WorkspaceRefusal.SemanticJoinMismatch)
                provenance <- ViewProvenance
                  .of(
                    workspace.model.source.canonicalChecksum,
                    None,
                    if clocks.kind == WorkspaceClockKind.Synthetic then
                      ViewBasis.SuppliedAlignmentSyntheticPresentation
                    else ViewBasis.SuppliedAlignmentArtifact,
                    VoyageCompiler.compilerVersion,
                    Checksum.ofText(
                      Vector(
                        Version,
                        workspace.modelArtifact.hex,
                        workspace.recallArtifact.hex,
                        policy.record.digest.hex,
                        MappingJson.print(clocks.json)
                      ).mkString("\n")
                    )
                  )
                  .left
                  .map(_ => WorkspaceRefusal.SemanticJoinMismatch)
                document = RecallVoyageDocument(input, provenance)
                scene <- document
                  .compile(Set.empty)
                  .left
                  .map(_ => WorkspaceRefusal.SemanticJoinMismatch)
              yield Some(document -> scene)
          document.map { projected =>
            // Derive the bridge from emitted marks, including the main anchor's unit address.
            val addresses = projected.toVector
              .flatMap(_._2.marks)
              .map { mark =>
                val anchor = mark match
                  case value: VoyageMark.UnitAnchor  => Some(value.anchor)
                  case value: VoyageMark.Alternative => Some(value.anchor)
                  case _                             => None
                mark.address -> (Set(workspace.recallAddress(mark.unit).get) ++ anchor.flatMap(
                  workspace.sourceAddress
                ))
              }
              .toMap
            new Projection(
              policy.id,
              policy.record.digest,
              projected.map(_._1),
              dispositions,
              addresses
            )
          }
    }
