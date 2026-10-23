package storymodel4s.document

import scala.collection.immutable.SortedMap

import cats.data.NonEmptyVector
import storymodel4s.core.*

/** Identity of one referent mention in one source or recall (ADR 0024). Why its own id: mentions
  * from video, speech and recall share no existing identifier space.
  */
object ReferentMentionId extends OpaqueId("ReferentMentionId")
type ReferentMentionId = ReferentMentionId.T

/** Where a referent mention was observed. Why: the bases that may join two mentions depend on where
  * each came from (a track joins only visual mentions; deixis joins speech to a depicted person),
  * so the origin is part of the mention, not a label beside it.
  */
enum MentionOrigin:
  /** A detection or track in the picture. */
  case Visual

  /** A mention inside an utterance, within the speech context that holds it. */
  case Dialogue(context: ContextId)

  /** A narration, caption or annotation mention. */
  case Narration

  /** A mention in a recall unit, named by its surface unit. */
  case Recall(unit: SurfaceUnitId)

/** A proper name attached directly to a mention by its own source. Why a type: identity by name
  * compares folded names, and a blank name would join every unnamed mention.
  */
final class ProperName private (val value: String):
  /** The comparison form: case-folded, whitespace-collapsed. */
  def folded: String = TextNorm.lower(value.trim.split("\\s+").mkString(" "))

  override def equals(other: Any): Boolean = other match
    case that: ProperName => folded == that.folded
    case _                => false
  override def hashCode(): Int = folded.##
  override def toString: String = s"ProperName($value)"

object ProperName:
  def of(raw: String): Either[ReferentError, ProperName] =
    if raw.trim.isEmpty then Left(ReferentError.BlankName) else Right(new ProperName(raw.trim))

/** Typed refusals of referent construction and identity licensing. Why typed: a refused licence is
  * a legitimate outcome that callers record (rule 12), not an exception.
  */
enum ReferentError:
  case BlankName
  case SupportFamily(mention: ReferentMentionId, origin: String, reason: String)
  case SameMention(mention: ReferentMentionId)
  case UnknownMention(mention: ReferentMentionId)
  case DuplicateMention(mention: ReferentMentionId)
  case ForeignLink(left: ReferentMentionId, right: ReferentMentionId)
  case Unlicensed(basis: String, reason: String)

  def message: String = this match
    case BlankName                 => "a proper name must not be blank"
    case SupportFamily(m, o, r)    => s"mention ${m.value} ($o): $r"
    case SameMention(m)            => s"a link needs two different mentions, got ${m.value} twice"
    case UnknownMention(m)         => s"mention ${m.value} is not in the referent context"
    case DuplicateMention(m)       => s"mention ${m.value} appears more than once"
    case ForeignLink(l, r)         => s"link ${l.value}-${r.value} was licensed in another context"
    case Unlicensed(basis, reason) => s"$basis does not license this link: $reason"

/** One mention of a person or thing, in any modality. A mention is not an entity: two mentions are
  * one referent only through a licensed [[IdentityLink]], never because their descriptions match.
  *
  * Why validated: a visual mention must sit in media time and a recall mention in its transcript; a
  * visual mention carrying text support would let a track basis join a sentence.
  */
final class ReferentMention private (
    val id: ReferentMentionId,
    val origin: MentionOrigin,
    val support: TypedSupport,
    val name: Option[ProperName]
):
  override def equals(other: Any): Boolean = other match
    case that: ReferentMention =>
      id == that.id && origin == that.origin && support == that.support && name == that.name
    case _ => false
  override def hashCode(): Int = (id, origin, support, name).##
  override def toString: String = s"ReferentMention(${id.value}, $origin)"

object ReferentMention:
  def of(
      id: ReferentMentionId,
      origin: MentionOrigin,
      support: TypedSupport,
      name: Option[ProperName]
  ): Either[ReferentError, ReferentMention] =
    def refuse(reason: String) = Left(ReferentError.SupportFamily(id, origin.toString, reason))
    (origin, support) match
      case (MentionOrigin.Visual, TypedSupport.Anchored(s))
          if s.anchors.forall(a => ReferentExtents.of(a).nonEmpty) =>
        Right(new ReferentMention(id, origin, support, name))
      case (MentionOrigin.Visual, _) => refuse("visual support must be anchored in media time")
      case (MentionOrigin.Recall(_), TypedSupport.Text(_)) =>
        Right(new ReferentMention(id, origin, support, name))
      case (MentionOrigin.Recall(_), _) => refuse("recall support must be transcript text")
      case _                            => Right(new ReferentMention(id, origin, support, name))

/** Playback extents of an anchor as `(axis, stream, start, endExclusive)` ticks; a point is one
  * tick wide. Text anchors have none.
  */
private[document] object ReferentExtents:
  final case class ReferentExtent(
      axis: PresentationAxisId,
      stream: StreamId,
      start: Long,
      end: Long
  ):
    def overlaps(that: ReferentExtent): Boolean =
      axis == that.axis && start < that.end && that.start < end

  def of(anchor: EvidenceAnchor): Vector[ReferentExtent] = anchor match
    case EvidenceAnchor.Text(_, _, _)           => Vector.empty
    case EvidenceAnchor.MediaTime(_, s, _, set) =>
      set.intervals.toVector.map(i => ReferentExtent(i.axis, s, i.start, i.endExclusive))
    case EvidenceAnchor.MediaPoint(_, s, at) => Vector(ReferentExtent(at.axis, s, at.at, at.at + 1))
    case EvidenceAnchor.Shot(_, s, _, i)     =>
      Vector(ReferentExtent(i.axis, s, i.start, i.endExclusive))
    case EvidenceAnchor.Track(_, s, _, set) =>
      set.intervals.toVector.map(i => ReferentExtent(i.axis, s, i.start, i.endExclusive))

  def of(support: TypedSupport): Vector[ReferentExtent] = support match
    case TypedSupport.Anchored(s) => s.anchors.toVector.flatMap(of)
    case TypedSupport.Text(_)     => Vector.empty

  /** Observed coverage as maximal stretches: touching or overlapping intervals on one axis merge,
    * so only a real gap separates two stretches.
    */
  def stretches(coverage: Option[PlaybackIntervalSet]): Vector[(PresentationAxisId, Long, Long)] =
    coverage.toVector
      .flatMap(_.intervals.toVector)
      .sortBy(i => (i.axis.value, i.start))
      .foldLeft(Vector.empty[(PresentationAxisId, Long, Long)]) { (acc, i) =>
        acc.lastOption match
          case Some((ax, s, e)) if ax == i.axis && i.start <= e =>
            acc.init :+ ((ax, s, e max i.endExclusive))
          case _ => acc :+ ((i.axis, i.start, i.endExclusive))
      }

  /** Track extents of a mention, by track. */
  def tracks(support: TypedSupport): Map[TrackId, Vector[ReferentExtent]] = support match
    case TypedSupport.Anchored(s) =>
      s.anchors.toVector
        .collect { case a @ EvidenceAnchor.Track(_, _, t, _) => t -> of(a) }
        .groupMapReduce(_._1)(_._2)(_ ++ _)
    case TypedSupport.Text(_) => Map.empty

/** The mentions and observed track coverage that identity links are checked against.
  *
  * Why a context: a deictic basis needs the set of people present, and a track basis needs the
  * track's observed coverage. A link that could be checked without them would be a label.
  */
final class ReferentContext private (
    val mentions: SortedMap[ReferentMentionId, ReferentMention],
    val trackCoverage: Map[TrackId, PlaybackIntervalSet]
):
  def mention(id: ReferentMentionId): Either[ReferentError, ReferentMention] =
    mentions.get(id).toRight(ReferentError.UnknownMention(id))

  override def equals(other: Any): Boolean = other match
    case that: ReferentContext =>
      mentions == that.mentions && trackCoverage == that.trackCoverage
    case _ => false
  override def hashCode(): Int = (mentions, trackCoverage).##
  override def toString: String =
    s"ReferentContext(mentions=${mentions.size}, tracks=${trackCoverage.size})"

object ReferentContext:
  /** `trackCoverage` gives, per track, the intervals in which the track was actually observed. */
  def of(
      mentions: Vector[ReferentMention],
      trackCoverage: Map[TrackId, PlaybackIntervalSet]
  ): Either[ReferentError, ReferentContext] =
    mentions.groupBy(_.id).collectFirst { case (id, ms) if ms.size > 1 => id } match
      case Some(dup) => Left(ReferentError.DuplicateMention(dup))
      case None      =>
        Right(new ReferentContext(SortedMap.from(mentions.map(m => m.id -> m)), trackCoverage))

/** A basis that is sufficient by itself to make two mentions one referent (ADR 0024 §2). Why a
  * closed enum: the licence class is fixed by the basis, so a caller cannot promote a weak basis.
  */
enum IdentityBasis:
  /** Both mentions lie in one observed track, inside one stretch of its coverage. */
  case ContinuousTrack(track: TrackId)

  /** An utterance refers to someone present, and exactly one visual mention is present. */
  case DeicticReference

  /** The same proper name is attached directly to both mentions. */
  case NamedReference(name: ProperName)

/** A basis sufficient by itself to make two mentions different referents (ADR 0024 §2). Why
  * separate from identity bases: distinctness reopens a class, so it needs its own licence.
  */
enum DistinctnessBasis:
  /** Two visual mentions on different tracks overlap in time on one stream. Known limit: a
    * duplicate detection or a reflection also yields two tracks; the track source must rule those
    * out before it is admitted.
    */
  case SimultaneousTracks

  /** Different proper names are attached directly to the two mentions. Known limit: aliases. */
  case DifferentNames

/** Evidence that can rank candidates but never decides identity or distinctness by itself. Why its
  * own type: no value of it can be passed where a sufficient basis is required.
  */
enum ContributoryBasis:
  case AppearanceMatch, TemporalProximity, CompatibleDescription

/** A licensed identity between two mentions. Construction checks the basis against the context;
  * there is no other way to obtain one. The link keeps that context, because its licence (who was
  * present, what the track covered) holds only there.
  */
final class IdentityLink private (
    val context: ReferentContext,
    val left: ReferentMention,
    val right: ReferentMention,
    val basis: IdentityBasis
):
  override def equals(other: Any): Boolean = other match
    case that: IdentityLink =>
      context == that.context && left == that.left && right == that.right && basis == that.basis
    case _ => false
  override def hashCode(): Int = (context, left, right, basis).##
  override def toString: String = s"IdentityLink(${left.id.value}, ${right.id.value}, $basis)"

object IdentityLink:
  def of(
      context: ReferentContext,
      left: ReferentMentionId,
      right: ReferentMentionId,
      basis: IdentityBasis
  ): Either[ReferentError, IdentityLink] =
    def refuse(reason: String) = Left(ReferentError.Unlicensed(basis.toString, reason))
    for
      a <- context.mention(left)
      b <- context.mention(right)
      _ <- Either.cond(left != right, (), ReferentError.SameMention(left))
      link <- basis match
        case IdentityBasis.ContinuousTrack(track) =>
          val coverage = ReferentExtents.stretches(context.trackCoverage.get(track))
          val extents =
            Vector(a, b).map(m => ReferentExtents.tracks(m.support).getOrElse(track, Vector()))
          // One stretch of observed coverage must hold both mentions: a gap means unobserved.
          val within = coverage.exists((axis, start, end) =>
            extents.forall(_.forall(x => x.axis == axis && x.start >= start && x.end <= end))
          )
          if a.origin != MentionOrigin.Visual || b.origin != MentionOrigin.Visual then
            refuse("a track joins only visual mentions")
          else if extents.exists(_.isEmpty) then refuse("a mention is not on this track")
          else if extents.flatten.map(_.stream).distinct.size != 1 then
            refuse("the mentions are on this track in different streams")
          else if !within then refuse("the mentions are not in one stretch of observed coverage")
          else Right(new IdentityLink(context, a, b, basis))
        case IdentityBasis.DeicticReference =>
          (a.origin, b.origin) match
            case (MentionOrigin.Dialogue(_), MentionOrigin.Visual) => deixis(context, a, b)
            case (MentionOrigin.Visual, MentionOrigin.Dialogue(_)) => deixis(context, b, a)
            case _ => refuse("deixis joins one utterance mention to one visual mention")
        case IdentityBasis.NamedReference(name) =>
          if a.name.contains(name) && b.name.contains(name) then
            Right(new IdentityLink(context, a, b, basis))
          else refuse("the name is not attached to both mentions")
    yield link

  private def deixis(
      context: ReferentContext,
      utterance: ReferentMention,
      depicted: ReferentMention
  ): Either[ReferentError, IdentityLink] =
    val spoken = ReferentExtents.of(utterance.support)
    val present = context.mentions.values.toVector.filter { m =>
      m.origin == MentionOrigin.Visual &&
      ReferentExtents.of(m.support).exists(e => spoken.exists(_.overlaps(e)))
    }
    present.map(_.id) match
      case Vector(only) if only == depicted.id =>
        Right(new IdentityLink(context, utterance, depicted, IdentityBasis.DeicticReference))
      case Vector() =>
        Left(ReferentError.Unlicensed("DeicticReference", "nobody depicted while it is said"))
      case Vector(_) =>
        Left(ReferentError.Unlicensed("DeicticReference", "the one person present is another"))
      case _ =>
        Left(ReferentError.Unlicensed("DeicticReference", "more than one person is present"))

/** A licensed distinctness between two mentions, kept with the context that licensed it. Why a
  * licence: a distinctness reopens identity, so an unlicensed one would erase established links.
  */
final class DistinctLink private (
    val context: ReferentContext,
    val left: ReferentMention,
    val right: ReferentMention,
    val basis: DistinctnessBasis
):
  override def equals(other: Any): Boolean = other match
    case that: DistinctLink =>
      context == that.context && left == that.left && right == that.right && basis == that.basis
    case _ => false
  override def hashCode(): Int = (context, left, right, basis).##
  override def toString: String = s"DistinctLink(${left.id.value}, ${right.id.value}, $basis)"

object DistinctLink:
  def of(
      context: ReferentContext,
      left: ReferentMentionId,
      right: ReferentMentionId,
      basis: DistinctnessBasis
  ): Either[ReferentError, DistinctLink] =
    def refuse(reason: String) = Left(ReferentError.Unlicensed(basis.toString, reason))
    for
      a <- context.mention(left)
      b <- context.mention(right)
      _ <- Either.cond(left != right, (), ReferentError.SameMention(left))
      link <- basis match
        case DistinctnessBasis.SimultaneousTracks =>
          val (ta, tb) = (ReferentExtents.tracks(a.support), ReferentExtents.tracks(b.support))
          val together = for
            x <- ta.values.flatten
            y <- tb.values.flatten
          yield x.stream == y.stream && x.overlaps(y)
          if a.origin != MentionOrigin.Visual || b.origin != MentionOrigin.Visual then
            refuse("simultaneity separates only visual mentions")
          else if ta.isEmpty || tb.isEmpty then refuse("both mentions need a track")
          else if ta.keySet.intersect(tb.keySet).nonEmpty then refuse("the mentions share a track")
          else if !together.exists(identity) then refuse("the tracks do not overlap on one stream")
          else Right(new DistinctLink(context, a, b, basis))
        case DistinctnessBasis.DifferentNames =>
          (a.name, b.name) match
            case (Some(x), Some(y)) if x != y => Right(new DistinctLink(context, a, b, basis))
            case (Some(_), Some(_))           => refuse("the names are the same")
            case _                            => refuse("both mentions need a name")
    yield link

/** Which way a contributory hint points. Why: a hint against identity is recorded beside a class
  * without reopening it (owner decision 1).
  */
enum HintDirection:
  case Toward, Against

/** Contributory evidence about two mentions. A plain record: it can never resolve or separate
  * referents, so a forged hint licenses nothing.
  */
final case class IdentityHint(
    left: ReferentMentionId,
    right: ReferentMentionId,
    basis: ContributoryBasis,
    direction: HintDirection
)

/** What the licensed links establish about two mentions. Why four cases: an absent link must not
  * read as distinctness, and a reopened identity must not read as either answer (rule 7). `Same` is
  * ADR 0024's resolved identity.
  */
enum ReferentRelation:
  /** Joined by a path of identity links in a class with no licensed distinctness inside it. */
  case Same

  /** A licensed distinctness separates them: directly, or between two classes neither of which is
    * open.
    */
  case Distinct

  /** Their class contains a licensed distinctness: identity is reopened, with these candidates. */
  case Open(candidates: Vector[ReferentMentionId])

  /** No licensed link decides it. */
  case Unknown

/** The identity classes of a referent context. Why a result type: consumers ask one question,
  * [[relation]], instead of reading classes from links. Construction is private to
  * [[ReferentIdentity]].
  */
final class ReferentResolution private[document] (
    val context: ReferentContext,
    val classes: Vector[NonEmptyVector[ReferentMentionId]],
    val contested: Set[Int],
    val separated: Set[(Int, Int)],
    val apart: Set[(ReferentMentionId, ReferentMentionId)],
    val hints: Vector[IdentityHint]
):
  private val classOf: Map[ReferentMentionId, Int] =
    classes.zipWithIndex.flatMap((c, i) => c.toVector.map(_ -> i)).toMap

  /** What the links establish about two mentions of this context. */
  def relation(
      a: ReferentMentionId,
      b: ReferentMentionId
  ): Either[ReferentError, ReferentRelation] =
    for
      ca <- classOf.get(a).toRight(ReferentError.UnknownMention(a))
      cb <- classOf.get(b).toRight(ReferentError.UnknownMention(b))
    yield
      if ca == cb && contested(ca) then ReferentRelation.Open(classes(ca).toVector)
      else if ca == cb then ReferentRelation.Same
      // A distinctness lifts to whole classes only when neither class is open: inside an open
      // class membership itself is in doubt, so only the directly linked pair stays distinct.
      else if separated((ca min cb, ca max cb)) && !contested(ca) && !contested(cb) then
        ReferentRelation.Distinct
      else if apart(ReferentIdentity.pair(a, b)) then ReferentRelation.Distinct
      else ReferentRelation.Unknown

  /** Contributory hints recorded between the classes of two mentions; they decide nothing. */
  def hintsBetween(a: ReferentMentionId, b: ReferentMentionId): Vector[IdentityHint] =
    val (ca, cb) = (classOf.get(a), classOf.get(b))
    hints.filter(h =>
      Set(classOf.get(h.left), classOf.get(h.right)) == Set(ca, cb) && ca.nonEmpty && cb.nonEmpty
    )

  override def equals(other: Any): Boolean = other match
    case that: ReferentResolution =>
      context == that.context && classes == that.classes && contested == that.contested &&
      separated == that.separated && apart == that.apart && hints == that.hints
    case _ => false
  override def hashCode(): Int = (context, classes, contested, separated, apart, hints).##
  override def toString: String =
    s"ReferentResolution(classes=${classes.size}, contested=${contested.size})"

/** The deterministic resolver of ADR 0024: identity classes from licensed links only.
  *
  * Why deterministic: a model may propose links, but only a link that its basis licenses here joins
  * or separates mentions, so the same evidence always gives the same classes.
  */
object ReferentIdentity:
  def resolve(
      context: ReferentContext,
      identities: Vector[IdentityLink],
      distinctions: Vector[DistinctLink],
      hints: Vector[IdentityHint]
  ): Either[ReferentError, ReferentResolution] =
    // A licence holds only in the context that granted it: a pruned context can make anyone the
    // one person present.
    val foreign = (identities.map(l => (l.context, l.left, l.right)) ++
      distinctions.map(l => (l.context, l.left, l.right))).collectFirst {
      case (c, l, r) if c != context => (l, r)
    }
    val unknownHint =
      hints.flatMap(h => Vector(h.left, h.right)).find(!context.mentions.contains(_))
    (foreign, unknownHint) match
      case (Some((l, r)), _) => Left(ReferentError.ForeignLink(l.id, r.id))
      case (_, Some(m))      => Left(ReferentError.UnknownMention(m))
      case _                 =>
        val ids = context.mentions.keys.toVector
        val parent = scala.collection.mutable.Map.from(ids.map(i => i -> i))
        def find(i: ReferentMentionId): ReferentMentionId =
          val p = parent(i)
          if p == i then i
          else
            val root = find(p)
            parent(i) = root
            root
        identities.foreach { l =>
          val (x, y) = (find(l.left.id), find(l.right.id))
          if x != y then
            parent(Ordering[ReferentMentionId].max(x, y)) = Ordering[ReferentMentionId].min(x, y)
        }
        val classes = ids
          .groupBy(find)
          .values
          .toVector
          .map(c => NonEmptyVector.fromVectorUnsafe(c.sorted))
          .sortBy(_.head)
        val classOf = classes.zipWithIndex.flatMap((c, i) => c.toVector.map(_ -> i)).toMap
        val pairs = distinctions.map(d => (classOf(d.left.id), classOf(d.right.id)))
        val contested = pairs.collect { case (x, y) if x == y => x }.toSet
        val separated = pairs.collect { case (x, y) if x != y => (x min y, x max y) }.toSet
        val apart = distinctions.map(d => pair(d.left.id, d.right.id)).toSet
        Right(new ReferentResolution(context, classes, contested, separated, apart, hints))

  private[document] def pair(
      a: ReferentMentionId,
      b: ReferentMentionId
  ): (ReferentMentionId, ReferentMentionId) =
    if Ordering[ReferentMentionId].lteq(a, b) then (a, b) else (b, a)
