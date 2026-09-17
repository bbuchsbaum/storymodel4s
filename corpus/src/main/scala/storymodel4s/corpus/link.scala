package storymodel4s.corpus

/** A position in a named segmentation. Two segmentations are two ordinal spaces, so they get two
  * values rather than one bare `Int` -- which is what lets composition check that the intermediate
  * segmentation agrees.
  */
final case class SegmentRef(segmentation: SegmentationId, ordinal: Int)

/** Why a link says what it says. Never optional: a mapping between two annotation scales is a claim
  * about a stimulus, and an unevidenced one is a guess with a version number.
  */
enum LinkEvidence:
  /** Read off the sources, at these coordinates. */
  case Observed(at: Vector[SourceCoordinate], note: String)

  /** Stated by a publication or a code book. */
  case Declared(citation: Citation, note: String)

  /** Derived by composing two links. Composition cannot invent direct evidence, and saying so is
    * what lets a composed link be compared with a declared one modulo evidence.
    */
  case Composed(first: LinkEvidence, second: LinkEvidence)

  def render: String = this match
    case Observed(at, note) => s"observed@${at.size}(${note})"
    case Declared(c, note)  => s"declared[$c]($note)"
    case Composed(a, b)     => s"composed(${a.render}, ${b.render})"

/** What a link claims about its own shape, CHECKED at construction rather than labelled. */
enum LinkClaim:
  /** Every source maps, injectively, onto every target. Admits no `Removed` at all.
    *
    * The "no Removed" part is not pedantry. Without it `{a -> To(x), b -> Removed}` satisfies
    * totality, injectivity on `To`, and exactly-once coverage of the targets that are reached --
    * and is not a bijection. That counterexample survived one review pass of this design.
    */
  case Bijection

  /** Many sources to one target, every target reached, nothing dropped. */
  case Coarsening

  /** Sources may be dropped; everything that survives maps. */
  case Edit

  def render: String = productPrefix.toLowerCase

/** Where one source segment went. */
enum Target:
  case To(ref: SegmentRef, evidence: LinkEvidence)
  case Removed(evidence: LinkEvidence)

  def evidenceOf: LinkEvidence = this match
    case To(_, e)   => e
    case Removed(e) => e
  def target: Option[SegmentRef] = this match
    case To(r, _)   => Some(r)
    case Removed(_) => None

/** A versioned, evidenced mapping between two segmentations of one stimulus.
  *
  * This exists because the remaps that do this work today are arithmetic inside scorers with no
  * provenance and no oracle: Film Festival's `+106`, Friends' 56->52, Sherlock's 1000->50.
  *
  * There is ONE mapping case, `To`. An earlier design had `Maps` and `MergedInto` as separate
  * cases, which made multiplicity a class the AUTHOR asserts when the MAP determines it -- under a
  * coarsening, a coarse segment holding exactly one fine segment would be `Maps` and its siblings
  * `MergedInto`, so a constructor choice could disagree with the data and nothing would catch it.
  * Multiplicity is now computed.
  */
final class SegmentLink private (
    val from: SegmentationId,
    val to: SegmentationId,
    val claim: LinkClaim,
    val version: Int,
    val mapping: Map[Int, Target]
):
  def apply(ordinal: Int): Option[SegmentRef] = mapping.get(ordinal).flatMap(_.target)
  def removed: Set[Int] = mapping.collect { case (k, Target.Removed(_)) => k }.toSet

  /** How many sources land on each target. Computed, never declared. */
  def multiplicity: Map[SegmentRef, Int] =
    mapping.values.flatMap(_.target).groupBy(identity).view.mapValues(_.size).toMap

  override def toString: String =
    s"SegmentLink(${from.value} -> ${to.value} v$version, ${claim.render}, ${mapping.size} sources)"

object SegmentLink:
  /** Builds a link, checking the claim rather than believing it. */
  private[corpus] def of(
      from: Segmentation,
      to: Segmentation,
      claim: LinkClaim,
      version: Int,
      mapping: Map[Int, Target]
  ): Either[LinkRefusal, SegmentLink] =
    val sources = from.ordinals
    val missing = sources.diff(mapping.keySet)
    val foreignKeys = mapping.keySet.diff(sources)
    val foreignTargets = mapping.values.flatMap(_.target).filter(_.segmentation != to.id)
    val outOfRange = mapping.values.flatMap(_.target).filter(r => !to.ordinals.contains(r.ordinal))

    if version < 1 then Left(LinkRefusal.BadVersion(version))
    else if missing.nonEmpty then Left(LinkRefusal.NotTotal(missing.toVector.sorted))
    else if foreignKeys.nonEmpty then Left(LinkRefusal.ForeignSource(foreignKeys.toVector.sorted))
    else if foreignTargets.nonEmpty then
      Left(LinkRefusal.ForeignTarget(foreignTargets.head.segmentation))
    else if outOfRange.nonEmpty then Left(LinkRefusal.TargetOutOfRange(outOfRange.head))
    else
      val reached = mapping.values.flatMap(_.target).toVector
      val dropped = mapping.values.collect { case Target.Removed(_) => () }.size
      claim match
        case LinkClaim.Bijection =>
          if dropped > 0 then Left(LinkRefusal.BijectionDropsSources(dropped))
          else if reached.distinct.sizeIs != reached.size then Left(LinkRefusal.NotInjective)
          else if reached.distinct.sizeIs != to.size then Left(LinkRefusal.NotOnto)
          else Right(new SegmentLink(from.id, to.id, claim, version, mapping))
        case LinkClaim.Coarsening =>
          if dropped > 0 then Left(LinkRefusal.CoarseningDropsSources(dropped))
          else if reached.distinct.sizeIs != to.size then Left(LinkRefusal.NotOnto)
          else Right(new SegmentLink(from.id, to.id, claim, version, mapping))
        case LinkClaim.Edit =>
          Right(new SegmentLink(from.id, to.id, claim, version, mapping))

  /** Function composition on `Option[SegmentRef]`.
    *
    * Defined only when the intermediate segmentation agrees, which is checkable precisely because
    * `SegmentRef` carries a `SegmentationId` and would not be if ordinals were bare `Int`s.
    *
    * The composed evidence is `Composed`, so a composed link is NOT equal to a directly declared
    * one of the same shape -- their evidence differs, and it should. Compare them with
    * [[sameMappingAs]] when the question is whether the mappings agree.
    *
    * It returns a MAPPING, not a link, and takes no claim: the caller runs `of` and states what it
    * claims, which is then CHECKED. Composing and claiming in one step would let a caller assert a
    * property of a composition it never established.
    */
  private[corpus] def compose(
      ab: SegmentLink,
      bc: SegmentLink
  ): Either[LinkRefusal, Map[Int, Target]] =
    if ab.to != bc.from then Left(LinkRefusal.IntermediateMismatch(ab.to, bc.from))
    else
      Right(ab.mapping.map { (source, t) =>
        val composed = t match
          case Target.Removed(e) => Target.Removed(e)
          case Target.To(ref, e) =>
            bc.mapping.get(ref.ordinal) match
              case Some(Target.To(r2, e2))  => Target.To(r2, LinkEvidence.Composed(e, e2))
              case Some(Target.Removed(e2)) => Target.Removed(LinkEvidence.Composed(e, e2))
              case None                     => Target.Removed(e)
        source -> composed
      })

extension (link: SegmentLink)
  /** Agreement on the mapping alone, ignoring evidence. */
  def sameMappingAs(other: SegmentLink): Boolean =
    link.from == other.from && link.to == other.to &&
      link.mapping.map((k, v) => k -> v.target) == other.mapping.map((k, v) => k -> v.target)

enum LinkRefusal:
  case BadVersion(version: Int)
  case NotTotal(missing: Vector[Int])
  case ForeignSource(ordinals: Vector[Int])
  case ForeignTarget(segmentation: SegmentationId)
  case TargetOutOfRange(ref: SegmentRef)
  case NotInjective
  case NotOnto
  case BijectionDropsSources(count: Int)
  case CoarseningDropsSources(count: Int)
  case IntermediateMismatch(abTo: SegmentationId, bcFrom: SegmentationId)

  def message: String = this match
    case BadVersion(v)              => s"link version $v is not positive"
    case NotTotal(m)                => s"no target for source ordinals ${m.mkString(", ")}"
    case ForeignSource(o)           => s"ordinals ${o.mkString(", ")} are not in the source"
    case ForeignTarget(s)           => s"a target names segmentation ${s.value}, not the target"
    case TargetOutOfRange(r)        => s"target ordinal ${r.ordinal} is not in the target"
    case NotInjective               => "two sources map to one target under a bijection"
    case NotOnto                    => "a target segment is unreached"
    case BijectionDropsSources(n)   => s"a bijection cannot drop $n source(s)"
    case CoarseningDropsSources(n)  => s"a coarsening cannot drop $n source(s)"
    case IntermediateMismatch(a, b) => s"cannot compose: ${a.value} is not ${b.value}"
