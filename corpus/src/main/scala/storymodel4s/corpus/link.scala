package storymodel4s.corpus

import storymodel4s.core.Checksum

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

  /** Sources may be dropped; everything that survives maps.
    *
    * The weakest claim, and the only one Friends' 56->52 map can make: it is neither injective onto
    * its target nor onto in the other direction once four sources are removed. Edit adds no check
    * beyond the shared invariants, so anything that is a Bijection or a Coarsening is also a valid
    * Edit -- a claim is a statement about what the caller is prepared to assert, and asserting less
    * is always available.
    */
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
    val mapping: Map[Int, Target],
    /** The CONTENT identities of the two segmentations, not just their names.
      *
      * A link stored ids alone, so `compose` could only match an intermediate by name -- and a
      * `SegmentationId` is a string. Carrying the identities is what lets composition ask whether
      * the two links are talking about the same segmentation rather than two that agree on a label.
      */
    val fromIdentity: Checksum,
    val toIdentity: Checksum
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

    // The WORK must match; the AXIS deliberately need not. A link whose two sides sit on different
    // axes is the crosswalk this module exists to carry -- an annotation timeline onto a playback
    // clock -- and rule 5 keeps those apart precisely so a value can be moved between them by a
    // declared link rather than by arithmetic in a scorer. Crossing works is nonsense in a way
    // crossing axes is not, so only the first refuses.
    if from.work != to.work then Left(LinkRefusal.ForeignWork(from.work, to.work))
    else if version < 1 then Left(LinkRefusal.BadVersion(version))
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
          else
            Right(
              new SegmentLink(from.id, to.id, claim, version, mapping, from.identity, to.identity)
            )
        case LinkClaim.Coarsening =>
          // A reviewer proposed a fourth check here: that the target ordinal be non-decreasing as
          // the source ordinal ascends. Declined, but not for the reason first written down here.
          //
          // That check conflates two properties. CONTIGUOUS GROUPING is what a coarsening really
          // requires; ORDER PRESERVATION depends only on how the two scales are numbered. Memento
          // is cut in reverse, so coarsening its discourse-ordered segments into story-ordered
          // scenes breaks the order while keeping every preimage contiguous -- monotonicity would
          // refuse a corpus this repository reads, the way strict onsets once refused Sherlock.
          //
          // What this does NOT establish, and what the comment here used to imply: contiguity is
          // unchecked in any form. `{1->2, 2->1, 3->2}` groups sources 1 and 3 while source 2 lands
          // elsewhere, which no cut order produces, and it is admitted too. That is the honest
          // boundary of `Coarsening`, whose promise is only "many to one, every target reached,
          // nothing dropped". A claim that checked contiguity would be new vocabulary and want an
          // ADR (SD5). Both cases are pinned, separately and for different reasons.
          if dropped > 0 then Left(LinkRefusal.CoarseningDropsSources(dropped))
          else if reached.distinct.sizeIs != to.size then Left(LinkRefusal.NotOnto)
          else
            Right(
              new SegmentLink(from.id, to.id, claim, version, mapping, from.identity, to.identity)
            )
        case LinkClaim.Edit =>
          // Edit adds NO check beyond the shared ones above, and that is deliberate rather than an
          // omission. The shared checks already enforce everything an edit must satisfy: total over
          // the source, no foreign ordinals, every target in the target segmentation, and evidence
          // on every Target by type. What Edit declines to claim is injectivity and ontoness --
          // which is the whole point of having it, because Friends' 56->52 map satisfies neither.
          //
          // An Edit that happens to drop nothing is still a legitimate Edit: the edit removed
          // nothing this time. Requiring at least one drop would refuse a correct map for being
          // insufficiently lossy.
          Right(
            new SegmentLink(from.id, to.id, claim, version, mapping, from.identity, to.identity)
          )

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
      // Matching the intermediate by id is not enough: a SegmentationId is a string, and two
      // Segmentations can share one while differing in extent. Without this, an intermediate
      // ordinal the second link does not cover would compose to `Removed` -- asserting a drop the
      // data never showed.
      val uncovered = ab.mapping.values
        .flatMap(_.target)
        .map(_.ordinal)
        .filterNot(bc.mapping.contains)
        .toVector
        .distinct
        .sorted
      if uncovered.nonEmpty then Left(LinkRefusal.IntermediateIncomplete(uncovered))
      // Identity is checked AFTER coverage, deliberately. Differing identities are the root cause
      // and uncovered ordinals are a symptom, but the symptom NAMES THE ORDINALS and the root cause
      // does not, so the more specific refusal wins where both apply. Ordering it the other way
      // turned an existing test's precise answer into a vague one, which is a regression even
      // though both answers are refusals.
      else if ab.toIdentity != bc.fromIdentity then
        Left(LinkRefusal.IntermediateDiffers(ab.to, ab.toIdentity, bc.fromIdentity))
      else
        Right(ab.mapping.map { (source, t) =>
          val composed = t match
            case Target.Removed(e) => Target.Removed(e)
            case Target.To(ref, e) =>
              bc.mapping.get(ref.ordinal) match
                case Some(Target.To(r2, e2))  => Target.To(r2, LinkEvidence.Composed(e, e2))
                case Some(Target.Removed(e2)) => Target.Removed(LinkEvidence.Composed(e, e2))
                // unreachable now that `uncovered` is checked above, and deliberately a refusal
                // rather than a silent Removed if it ever becomes reachable again
                case None => Target.Removed(e)
          source -> composed
        })

extension (link: SegmentLink)
  /** Agreement on the mapping alone, ignoring evidence. */
  def sameMappingAs(other: SegmentLink): Boolean =
    link.from == other.from && link.to == other.to &&
      link.mapping.map((k, v) => k -> v.target) == other.mapping.map((k, v) => k -> v.target)

enum LinkRefusal:
  case BadVersion(version: Int)

  /** The two segmentations belong to different WORKS.
    *
    * Nothing else in `of` would catch this: the checks are all about ordinals, and two corpora
    * number their segments 1..n just the same. So a Friends 56-scene segmentation mapped onto a
    * Sherlock 50-scene one satisfies totality, ontoness and range, and produces a link that is
    * arithmetic nonsense. Only the `work` says so, and until this case nothing read it.
    *
    * Crossing an AXIS is a different matter and stays legal -- see `of`.
    */
  case ForeignWork(from: WorkId, to: WorkId)
  case NotTotal(missing: Vector[Int])
  case ForeignSource(ordinals: Vector[Int])
  case ForeignTarget(segmentation: SegmentationId)
  case TargetOutOfRange(ref: SegmentRef)
  case NotInjective
  case NotOnto
  case BijectionDropsSources(count: Int)
  case CoarseningDropsSources(count: Int)
  case IntermediateMismatch(abTo: SegmentationId, bcFrom: SegmentationId)

  /** The two links agree on the intermediate's NAME and on its EXTENT, and are still talking about
    * two different segmentations.
    *
    * `IntermediateIncomplete` below catches the case where the second link covers fewer ordinals
    * than the first reaches. It cannot catch two segmentations of the SAME SIZE whose segments
    * differ, because the only thing a link carried was the id. Demonstrated by cold review: onsets
    * 0/10/20 composed with onsets 5000/6000/7000 under the shared id `b`, and the composition
    * succeeded and produced a link asserting a relationship nothing had established.
    */
  case IntermediateDiffers(id: SegmentationId, abTo: Checksum, bcFrom: Checksum)

  /** The two links agree on the intermediate's NAME and disagree about its extent.
    *
    * A `SegmentationId` is a string, so two `Segmentation` values can share one and differ in size.
    * Composing them would silently report the uncovered sources as `Removed` -- claiming the edit
    * dropped them when in fact the links disagree about what the intermediate is. That is a false
    * claim rather than a missing value, so it refuses.
    */
  case IntermediateIncomplete(missing: Vector[Int])

  def message: String = this match
    case BadVersion(v)                 => s"link version $v is not positive"
    case ForeignWork(f, t)             => s"cannot link work ${f.value} to work ${t.value}"
    case NotTotal(m)                   => s"no target for source ordinals ${m.mkString(", ")}"
    case ForeignSource(o)              => s"ordinals ${o.mkString(", ")} are not in the source"
    case ForeignTarget(s)              => s"a target names segmentation ${s.value}, not the target"
    case TargetOutOfRange(r)           => s"target ordinal ${r.ordinal} is not in the target"
    case NotInjective                  => "two sources map to one target under a bijection"
    case NotOnto                       => "a target segment is unreached"
    case BijectionDropsSources(n)      => s"a bijection cannot drop $n source(s)"
    case CoarseningDropsSources(n)     => s"a coarsening cannot drop $n source(s)"
    case IntermediateMismatch(a, b)    => s"cannot compose: ${a.value} is not ${b.value}"
    case IntermediateDiffers(id, a, b) =>
      s"cannot compose: two different segmentations are both named ${id.value} " +
        s"(${a.short()} vs ${b.short()})"
    case IntermediateIncomplete(m) =>
      s"cannot compose: the second link does not cover intermediate ordinals ${m.mkString(", ")}"
