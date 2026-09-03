package storymodel4s.view

import cats.data.NonEmptyVector
import storymodel4s.align.{AlignRef, AlignState, AlignmentMatrix, AlignmentRow, SourceNodeRef}
import storymodel4s.core.*
import storymodel4s.recall.{RecallRef, RecallUnitId}

/** The **Recall Voyage** (ADR 0002 §14): one recall drawn against its source on two clocks.
  *
  * Every mark is a quantity the aligner already computed (V-R1): the anchor a unit landed on and
  * the posterior mass it carries, every other admitted anchor with its mass, the mass the row puts
  * outside the source, and how the drawn anchor came to be. Nothing here infers. The compiler never
  * touches a posterior; it reads rows and decisions it was given, proves at construction that every
  * reference resolves, and re-derives every drawn number from the row at compile time so a forged
  * mark is refused (the evidence law, [[VoyageCompiler.checkEvidence]]).
  *
  * The uncertainty this projection shows is the posterior itself, spread over the source clock. It
  * mints no credence axis and calls nothing calibrated (ADR 0002 §11 A2): area is
  * [[MeasureMeaning.AnchorMass]], a named aligner quantity, and the shape of a mark is its
  * [[AnchorOrigin]], a fact about the decode, never a colour (V-U5).
  */

/** Seconds on a clock: nonnegative and finite, or not a time at all. */
final case class Seconds private (value: Double)

object Seconds:
  /** Construct a clock reading; negative, NaN and infinite readings are refused. */
  def of(value: Double): Either[DomainError, Seconds] =
    if value.isNaN || value.isInfinite || value < 0.0 then
      Left(DomainError.InvalidFormat("Seconds", value.toString, "not a nonnegative finite time"))
    else Right(new Seconds(value))

  given Ordering[Seconds] = Ordering.by(_.value)

/** A closed interval on a clock, `start <= end`. */
final case class ClockSpan private (start: Seconds, end: Seconds):
  def midpoint: Double = (start.value + end.value) / 2.0
  def length: Double = end.value - start.value

object ClockSpan:
  /** Construct a span only when its ends are ordered. */
  def of(start: Double, end: Double): Either[DomainError, ClockSpan] =
    for
      s <- Seconds.of(start)
      e <- Seconds.of(end)
      span <-
        if e.value < s.value then
          Left(DomainError.InvalidFormat("ClockSpan", s"[$start,$end]", "end precedes start"))
        else Right(new ClockSpan(s, e))
    yield span

/** How the drawn anchor came to be. Each case names a fact about the aligner's own procedure, so a
  * reader can tell an anchor the posterior chose from one an ordering decode imposed, and both from
  * one the decode had to look up outside the posterior (mass zero, drawn as an absence of evidence,
  * never as a small amount of it).
  */
enum AnchorOrigin:
  /** `AlignmentRow.mapSource`: the highest-mass source anchor of the row. */
  case PosteriorArgmax

  /** A sequence decode assigned the unit a source group and chose the row's best anchor inside it,
    * an anchor that carries posterior mass.
    */
  case DecodeBound

  /** A sequence decode assigned a group where the row carries no mass, and an anchor was found by
    * re-scoring the group's leaves; its posterior mass is exactly zero.
    */
  case DecodeFilled

  def label: String = this match
    case PosteriorArgmax => "posterior argmax"
    case DecodeBound     => "decode-bound"
    case DecodeFilled    => "decode-filled"

/** One source node on the source clock: its hierarchy level, the group it belongs to, its span. */
final case class SourceTimelineNode(
    ref: SourceNodeRef,
    level: Int,
    group: Option[Int],
    span: ClockSpan,
    label: String
)

/** One group of source nodes (a scene, for a film) with its own span and label. */
final case class SourceTimelineGroup(ordinal: Int, label: String, span: ClockSpan)

/** Every alignable node and group placed on the source clock. Checked: refs unique, group ordinals
  * unique, every node's group declared, levels nonnegative.
  */
final class SourceTimeline private (
    val nodes: Vector[SourceTimelineNode],
    val groups: Vector[SourceTimelineGroup]
):
  lazy val byRef: Map[SourceNodeRef, SourceTimelineNode] = nodes.map(n => n.ref -> n).toMap
  lazy val byGroup: Map[Int, SourceTimelineGroup] = groups.map(g => g.ordinal -> g).toMap
  def node(ref: SourceNodeRef): Option[SourceTimelineNode] = byRef.get(ref)
  def end: Double = (nodes.map(_.span.end.value) ++ groups.map(_.span.end.value)).maxOption
    .getOrElse(0.0)

object SourceTimeline:
  /** Build a timeline only when every reference inside it resolves. */
  def of(
      nodes: Vector[SourceTimelineNode],
      groups: Vector[SourceTimelineGroup]
  ): Either[DomainError, SourceTimeline] =
    val dupNode =
      nodes.map(_.ref).groupBy(identity).collectFirst { case (r, xs) if xs.size > 1 => r }
    val dupGroup =
      groups.map(_.ordinal).groupBy(identity).collectFirst { case (g, xs) if xs.size > 1 => g }
    val groupIds = groups.map(_.ordinal).toSet
    dupNode match
      case Some(r) => Left(DomainError.DuplicateId("SourceTimelineNode", r.key))
      case None    =>
        dupGroup match
          case Some(g) => Left(DomainError.DuplicateId("SourceTimelineGroup", g.toString))
          case None    =>
            nodes.find(n => n.level < 0) match
              case Some(n) =>
                Left(
                  DomainError.InvalidFormat("SourceTimelineNode", n.ref.key, "negative level")
                )
              case None =>
                nodes.find(n => n.group.exists(g => !groupIds.contains(g))) match
                  case Some(n) =>
                    Left(
                      DomainError.InvariantViolation(
                        s"view/voyage/timeline/${n.ref.key}",
                        s"group ${n.group.getOrElse(-1)} is not declared"
                      )
                    )
                  case None => Right(new SourceTimeline(nodes, groups))

/** One recall unit as the voyage needs it: identity, order, its own words, and when it was said.
  * `onset` is absent when the recall carries no audio timing for the unit; such a unit is drawn as
  * an absence on the recall clock, never placed by guess.
  */
final case class VoyageUnit(
    id: RecallUnitId,
    ordinal: Int,
    text: String,
    onset: Option[Seconds],
    lastWordOnset: Option[Seconds]
)

/** What the aligner's procedure concluded for one unit: the drawn anchor, the group the decode
  * assigned (if any), and the anchor's origin.
  */
final case class VoyageDecision(
    unit: RecallUnitId,
    anchor: Option[SourceNodeRef],
    group: Option[Int],
    origin: AnchorOrigin
)

/** One interval of an independent human coding of the recall: during `recall`, the person was
  * describing source group `group`.
  */
final case class CodedInterval(recall: ClockSpan, group: Int)

/** An independent human coding of the recall, carried with its identity so the bands it draws can
  * never be mistaken for the aligner's output.
  */
final case class IndependentCoding(
    name: String,
    checksum: Checksum,
    intervals: Vector[CodedInterval]
)

/** The proven join the compiler reads: units in recall order, one posterior row per unit, a
  * timeline every referenced node resolves in, one decision per unit, and an optional coding.
  *
  * Not a case class, and constructed only through [[RecallVoyageInput.of]]: the fields encode a
  * relation (rows ↔ units ↔ timeline ↔ decisions) that no product constructor can be trusted to
  * hold, which is exactly the cartesian-product trigger.
  */
final class RecallVoyageInput private (
    val units: Vector[VoyageUnit],
    val matrix: AlignmentMatrix,
    val timeline: SourceTimeline,
    val decisions: Vector[VoyageDecision],
    val coding: Option[IndependentCoding],
    val recallLength: Seconds
):
  lazy val decisionOf: Map[RecallUnitId, VoyageDecision] = decisions.map(d => d.unit -> d).toMap

object RecallVoyageInput:
  /** Prove the join or say exactly which strand failed. */
  def of(
      units: Vector[VoyageUnit],
      matrix: AlignmentMatrix,
      timeline: SourceTimeline,
      decisions: Vector[VoyageDecision],
      coding: Option[IndependentCoding],
      recallLength: Seconds
  ): Either[DomainError, RecallVoyageInput] =
    def violation(path: String, reason: String) =
      Left(DomainError.InvariantViolation(s"view/voyage/input/$path", reason))
    val rowIds = matrix.rows.map(_.unit)
    val unitIds = units.map(_.id)
    if rowIds != unitIds then
      violation("rows", "the posterior rows must be the units, in recall order")
    else if decisions.map(_.unit) != unitIds then
      violation("decisions", "one decision per unit, in recall order")
    else
      val unresolved = matrix.rows.iterator
        .flatMap(_.mass.keysIterator)
        .flatMap(_.anchor)
        .find(ref => timeline.node(ref).isEmpty)
      unresolved match
        case Some(ref) =>
          violation("timeline", s"${ref.key} carries mass but is not on the timeline")
        case None =>
          decisions.find(d => d.anchor.exists(ref => timeline.node(ref).isEmpty)) match
            case Some(d) =>
              violation(s"decisions/${d.unit.value}", "the decided anchor is not on the timeline")
            case None =>
              decisions.find(d => d.group.exists(g => !timeline.byGroup.contains(g))) match
                case Some(d) =>
                  violation(s"decisions/${d.unit.value}", "the decided group is not declared")
                case None =>
                  val badOrigin = decisions.find { d =>
                    val row = matrix.byUnit(d.unit)
                    d.origin match
                      case AnchorOrigin.PosteriorArgmax => d.anchor != row.mapSource
                      case AnchorOrigin.DecodeBound     =>
                        !d.anchor.exists(ref => row.anchorMass.getOrElse(ref, 0.0) > 0.0)
                      case AnchorOrigin.DecodeFilled =>
                        !d.anchor.exists(ref => row.anchorMass.getOrElse(ref, 0.0) == 0.0)
                  }
                  badOrigin match
                    case Some(d) =>
                      violation(
                        s"decisions/${d.unit.value}",
                        s"origin ${d.origin.label} does not describe the row"
                      )
                    case None =>
                      coding.flatMap(c =>
                        c.intervals.find(i => !timeline.byGroup.contains(i.group))
                      ) match
                        case Some(i) =>
                          violation("coding", s"coded group ${i.group} is not declared")
                        case None =>
                          units.find(u => u.onset.exists(_.value > recallLength.value)) match
                            case Some(u) =>
                              violation(s"units/${u.id.value}", "onset after the recall's end")
                            case None =>
                              Right(
                                new RecallVoyageInput(
                                  units,
                                  matrix,
                                  timeline,
                                  decisions,
                                  coding,
                                  recallLength
                                )
                              )

/** Renderer-neutral marks of a voyage. Every field is read from the input; none is computed. */
enum VoyageMark:
  /** Where a unit landed and how surely. `mass` is `AlignmentRow.anchorMass(anchor)`. */
  case UnitAnchor(
      identity: VisualIdentity,
      unit: RecallUnitId,
      unitOrdinal: Int,
      at: Seconds,
      anchor: SourceNodeRef,
      level: Int,
      group: Option[Int],
      span: ClockSpan,
      mass: Double,
      sourceMass: Double,
      externalMass: Double,
      localizability: Option[Double],
      origin: AnchorOrigin,
      argmax: Option[SourceNodeRef]
  )

  /** Another admitted anchor of the same unit, by descending mass then key; `rank` starts at 1. */
  case Alternative(
      identity: VisualIdentity,
      unit: RecallUnitId,
      rank: Int,
      anchor: SourceNodeRef,
      level: Int,
      group: Option[Int],
      span: ClockSpan,
      mass: Double
  )

  /** A timed unit the aligner anchored nowhere in the source: an absence, with the mass it put
    * outside.
    */
  case Unanchored(
      identity: VisualIdentity,
      unit: RecallUnitId,
      unitOrdinal: Int,
      at: Seconds,
      externalMass: Double
  )

  /** A unit with no recall-clock onset: it cannot be placed on x, and is listed rather than
    * guessed.
    */
  case Untimed(identity: VisualIdentity, unit: RecallUnitId, unitOrdinal: Int)

  def identity: VisualIdentity
  def address: Address = identity.address
  def unit: RecallUnitId

/** Bidirectional lookup between addresses and voyage marks, refusing duplicate mark identities. */
final case class VoyageNavigation private (
    byAddress: Map[Address, Vector[MarkId]],
    addressOf: Map[MarkId, Address]
):
  def marksFor(address: Address): Vector[MarkId] = byAddress.getOrElse(address, Vector.empty)

object VoyageNavigation:
  def from(marks: Vector[VoyageMark]): Either[DomainError, VoyageNavigation] =
    val ids = marks.map(_.identity.mark)
    ids.groupBy(identity).collectFirst { case (id, xs) if xs.size > 1 => id } match
      case Some(dup) => Left(DomainError.DuplicateId("MarkId", dup.value))
      case None      =>
        Right(
          new VoyageNavigation(
            marks.groupMap(_.address)(_.identity.mark).view.mapValues(_.sorted).toMap,
            marks.map(m => m.identity.mark -> m.address).toMap
          )
        )

/** The compiled voyage: marks under a contract, the timeline they are drawn on, the coding beside
  * them, selection placements, and provenance.
  */
final case class VoyageScene private[view] (
    contract: ProjectionContract,
    recallLength: Seconds,
    timeline: SourceTimeline,
    marks: Vector[VoyageMark],
    navigation: VoyageNavigation,
    selectionPlacements: Map[Address, SelectionPlacement[MarkId]],
    coding: Option[IndependentCoding],
    provenance: ViewProvenance
):
  def textualTwin: String = VoyageTextualTwin.render(this)

object ProjectionContractVoyage:
  /** The Recall Voyage contract: both axes are clocks, area is anchor mass, distance means nothing,
    * and the marks are aligner quantities (V-R1).
    */
  val recallVoyage: ProjectionContract = ProjectionContract.of(
    ProjectionKind.RecallVoyage,
    AxisMeaning.RecallClock,
    AxisMeaning.SourceClock,
    DistanceMeaning.NoMeaning,
    area = Some(MeasureMeaning.AnchorMass),
    Vector(
      ChannelMeaning(VisualChannel.X, "seconds into the recall, from word onsets"),
      ChannelMeaning(VisualChannel.Y, "seconds into the source, the anchored node's span"),
      ChannelMeaning(
        VisualChannel.LandmarkPosition,
        "a unit's drawn anchor: the posterior argmax, or the anchor a sequence decode chose"
      ),
      ChannelMeaning(
        VisualChannel.Area,
        "posterior mass on the drawn anchor (AlignmentRow.anchorMass)"
      ),
      ChannelMeaning(
        VisualChannel.RegionExtent,
        "a group-level anchor spans its whole group; a coded interval spans its group over the " +
          "recall interval coded to it"
      ),
      ChannelMeaning(
        VisualChannel.Route,
        "from the posterior argmax to a decode-moved anchor; from an anchor to an alternative"
      ),
      ChannelMeaning(VisualChannel.Absence, "a unit anchored nowhere in the source, or untimed"),
      ChannelMeaning(
        VisualChannel.Epistemic,
        "mark shape: posterior argmax, decode-bound, decode-filled (mass zero), external-dominant"
      )
    ),
    Set(
      VisualInvariant.EvidenceBacked,
      VisualInvariant.SelectionPreserved,
      VisualInvariant.Deterministic,
      VisualInvariant.AbsenceIsMarked,
      VisualInvariant.EpistemicChannelIsNonColour
    )
  )

/** Compiles a [[VoyageScene]] from a proven [[RecallVoyageInput]]. Pure and deterministic. */
object VoyageCompiler:
  val compilerVersion: String = "voyage-v1"

  private object Salt:
    val anchor = "voyage/anchor/"
    val alternative = "voyage/alt/"
    val unanchored = "voyage/none/"
    val untimed = "voyage/untimed/"

  private def unitAddress(id: RecallUnitId): Address =
    Addressable[RecallRef].address(RecallRef.Unit(id))

  private def cellAddress(id: RecallUnitId, ref: SourceNodeRef): Address =
    Addressable[AlignRef].address(AlignRef.Cell(id, AlignState.Source(ref)))

  private def identity(address: Address, mark: String): VisualIdentity =
    VisualIdentity.of(address, NarrativeLevel.Story, MarkId.unsafe(mark))

  /** Every admitted anchor of a row, by descending mass then key: the posterior as a list. */
  def rankedAnchors(row: AlignmentRow): Vector[(SourceNodeRef, Double)] =
    row.anchorMass.toVector.sortBy { case (ref, m) => (-m, ref.key) }

  def compile(
      input: RecallVoyageInput,
      selection: Set[Address],
      provenance: ViewProvenance
  ): Either[DomainError, VoyageScene] =
    val nodeCount = input.timeline.nodes.size
    val marks: Vector[VoyageMark] = input.units.zip(input.matrix.rows).flatMap { case (u, row) =>
      val decision = input.decisionOf(u.id)
      val addr = unitAddress(u.id)
      u.onset match
        case None =>
          Vector(VoyageMark.Untimed(identity(addr, Salt.untimed + u.id.value), u.id, u.ordinal))
        case Some(at) =>
          decision.anchor match
            case None =>
              Vector(
                VoyageMark.Unanchored(
                  identity(addr, Salt.unanchored + u.id.value),
                  u.id,
                  u.ordinal,
                  at,
                  row.externalMass
                )
              )
            case Some(ref) =>
              val node = input.timeline.byRef(ref)
              val head = VoyageMark.UnitAnchor(
                identity(addr, Salt.anchor + u.id.value),
                u.id,
                u.ordinal,
                at,
                ref,
                node.level,
                node.group,
                node.span,
                row.anchorMass.getOrElse(ref, 0.0),
                row.sourceMass,
                row.externalMass,
                row.localizability(nodeCount),
                decision.origin,
                row.mapSource
              )
              val alternatives =
                rankedAnchors(row).filter(_._1 != ref).zipWithIndex.map { case ((alt, m), i) =>
                  val n = input.timeline.byRef(alt)
                  VoyageMark.Alternative(
                    identity(cellAddress(u.id, alt), Salt.alternative + u.id.value + "/" + (i + 1)),
                    u.id,
                    i + 1,
                    alt,
                    n.level,
                    n.group,
                    n.span,
                    m
                  )
                }
              head +: alternatives
    }
    for
      _ <- marks.foldLeft[Either[DomainError, Unit]](Right(()))((acc, m) =>
        acc.flatMap(_ => checkEvidence(input, m))
      )
      navigation <- VoyageNavigation.from(marks)
    yield VoyageScene(
      ProjectionContractVoyage.recallVoyage,
      input.recallLength,
      input.timeline,
      marks,
      navigation,
      placements(selection, navigation),
      input.coding,
      provenance
    )

  /** V-L2: a selected address is on its marks, or honestly off this projection. */
  private def placements(
      selection: Set[Address],
      navigation: VoyageNavigation
  ): Map[Address, SelectionPlacement[MarkId]] =
    selection.toVector.map { address =>
      val marks = navigation.marksFor(address)
      address -> NonEmptyVector
        .fromVector(marks)
        .fold[SelectionPlacement[MarkId]](SelectionPlacement.OffProjection)(
          SelectionPlacement.OnMark(_)
        )
    }.toMap

  /** The evidence law for the voyage: every number on a mark is the row's own number, every span is
    * the timeline's, and the origin describes the decision. `private[view]` so a court can hand it
    * a forged mark.
    */
  private[view] def checkEvidence(
      input: RecallVoyageInput,
      mark: VoyageMark
  ): Either[DomainError, Unit] =
    val row = input.matrix.byUnit(mark.unit)
    val decision = input.decisionOf(mark.unit)
    val supported = mark match
      case VoyageMark.UnitAnchor(
            _,
            _,
            _,
            _,
            ref,
            level,
            group,
            span,
            mass,
            src,
            ext,
            loc,
            origin,
            argmax
          ) =>
        input.timeline
          .node(ref)
          .exists(n => n.level == level && n.group == group && n.span == span) &&
        decision.anchor.contains(ref) && decision.origin == origin &&
        mass == row.anchorMass.getOrElse(ref, 0.0) && src == row.sourceMass &&
        ext == row.externalMass && loc == row.localizability(input.timeline.nodes.size) &&
        argmax == row.mapSource
      case VoyageMark.Alternative(_, _, rank, ref, level, group, span, mass) =>
        input.timeline
          .node(ref)
          .exists(n => n.level == level && n.group == group && n.span == span) &&
        !decision.anchor.contains(ref) && rank >= 1 && mass == row.anchorMass.getOrElse(ref, -1.0)
      case VoyageMark.Unanchored(_, _, _, _, ext) =>
        decision.anchor.isEmpty && ext == row.externalMass
      case VoyageMark.Untimed(_, id, _) =>
        input.units.exists(u => u.id == id && u.onset.isEmpty)
    if supported then Right(())
    else
      Left(
        DomainError.InvariantViolation(
          s"view/voyage/marks/${mark.identity.mark.value}",
          s"${mark.address.render} does not match the row it claims to draw"
        )
      )

/** Deterministic, total text twin of a voyage: every mark, one line each, no recall prose. */
object VoyageTextualTwin:
  private def num(d: Double): String = f"$d%.4f"
  private def sec(s: Seconds): String = f"${s.value}%.1f"

  def render(scene: VoyageScene): String =
    val out = new StringBuilder
    val p = scene.provenance
    out.append("Recall Voyage — ").append(scene.contract.kind.toString).append('\n')
    out.append("Basis: ").append(p.basis.label).append('\n')
    out.append("Source checksum: ").append(p.sourceChecksum.hex).append('\n')
    out.append("Compiler: ").append(p.compilerVersion).append('\n')
    out.append("Configuration: ").append(p.configChecksum.hex).append('\n')
    out.append("Recall length: ").append(sec(scene.recallLength)).append(" s\n")
    out
      .append("Timeline: ")
      .append(scene.timeline.nodes.size)
      .append(" nodes, ")
      .append(scene.timeline.groups.size)
      .append(" groups, ")
      .append(f"${scene.timeline.end}%.1f")
      .append(" s\n")
    scene.coding match
      case None    => out.append("Coding: none\n")
      case Some(c) =>
        out
          .append("Coding: ")
          .append(c.name)
          .append(' ')
          .append(c.checksum.hex)
          .append(", ")
          .append(c.intervals.size)
          .append(" intervals\n")
    out.append("Marks\n")
    scene.marks.foreach {
      case VoyageMark.UnitAnchor(
            id,
            _,
            ordinal,
            at,
            ref,
            level,
            group,
            span,
            mass,
            src,
            ext,
            loc,
            origin,
            argmax
          ) =>
        out
          .append("- ")
          .append(id.mark.value)
          .append(" unit ")
          .append(ordinal)
          .append(" at ")
          .append(sec(at))
          .append(" -> ")
          .append(ref.key)
          .append(" level ")
          .append(level)
          .append(" group ")
          .append(group.fold("-")(_.toString))
          .append(" span ")
          .append(sec(span.start))
          .append("..")
          .append(sec(span.end))
          .append(" mass ")
          .append(num(mass))
          .append(" source ")
          .append(num(src))
          .append(" external ")
          .append(num(ext))
          .append(" localizability ")
          .append(loc.fold("-")(num))
          .append(' ')
          .append(origin.label)
          .append(" argmax ")
          .append(argmax.fold("-")(_.key))
          .append('\n')
      case VoyageMark.Alternative(id, _, rank, ref, _, group, span, mass) =>
        out
          .append("- ")
          .append(id.mark.value)
          .append(" rank ")
          .append(rank)
          .append(" -> ")
          .append(ref.key)
          .append(" group ")
          .append(group.fold("-")(_.toString))
          .append(" span ")
          .append(sec(span.start))
          .append("..")
          .append(sec(span.end))
          .append(" mass ")
          .append(num(mass))
          .append('\n')
      case VoyageMark.Unanchored(id, _, ordinal, at, ext) =>
        out
          .append("- ")
          .append(id.mark.value)
          .append(" unit ")
          .append(ordinal)
          .append(" at ")
          .append(sec(at))
          .append(" unanchored, external ")
          .append(num(ext))
          .append('\n')
      case VoyageMark.Untimed(id, _, ordinal) =>
        out.append("- ").append(id.mark.value).append(" unit ").append(ordinal).append(" untimed\n")
    }
    out.append("Selection\n")
    scene.selectionPlacements.toVector.sortBy(_._1.render).foreach { case (address, placement) =>
      out.append("- ").append(address.render).append(": ")
      placement match
        case SelectionPlacement.OnMark(marks) =>
          out.append("on ").append(marks.toVector.map(_.value).mkString(", "))
        case SelectionPlacement.ViaAncestor(ancestor) => out.append("via ").append(ancestor.render)
        case SelectionPlacement.OffProjection         => out.append("off projection")
      out.append('\n')
    }
    out.toString
