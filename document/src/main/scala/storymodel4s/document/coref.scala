package storymodel4s.document

import cats.data.NonEmptySet
import storymodel4s.core.*

/** Mentions that denote exactly the same entity or situation, with their canonical identity.
  *
  * Kind-indexed so an entity mention can never be clustered with a situation mention at compile
  * time, and kind-checked at construction against the chart concepts the mentions occupy (see
  * [[KindWitness]]). The canonical ID is content-addressed from the story and the sorted member
  * mentions (roadmap §3, Part 2 item 10): the same members always name the same canonical node, and
  * no caller may invent one. Only exact identity forms clusters; prospective, retrospective,
  * summary, partial, bridging, and thematic references are `NarrativeReference` edges, never
  * cluster membership (design record §44.1).
  */
final case class ExactCorefCluster[K <: NarrativeKind] private (
    mentions: NonEmptySet[MentionId[K]],
    canonical: CanonicalId[K]
):
  def contains(m: MentionId[K]): Boolean = mentions.contains(m)
  def size: Int = mentions.length

object ExactCorefCluster:
  /** The content address every cluster (and every singleton) is named by. */
  def canonicalFor[K <: NarrativeKind](story: StoryId, members: Iterable[MentionId[K]])(using
      w: KindWitness[K]
  ): CanonicalId[K] =
    CanonicalId.unsafe(DeterministicId.forCanonical(story, w.tag, members.map(_.value)))

  /** Build a cluster whose members all exist in `table` (hence in the graph, with the right kind).
    */
  def of[K <: NarrativeKind](
      story: StoryId,
      mentions: NonEmptySet[MentionId[K]],
      table: MentionTable[K]
  )(using KindWitness[K]): Either[DocumentError, ExactCorefCluster[K]] =
    mentions.toSortedSet.find(m => !table.contains(m)) match
      case Some(m) => Left(DocumentError.UnknownMention(m.value, "ExactCorefCluster"))
      case None    => Right(ExactCorefCluster(mentions, canonicalFor(story, mentions.toSortedSet)))

  /** Accept a caller-supplied canonical only when it equals the content address. */
  def checked[K <: NarrativeKind](
      story: StoryId,
      mentions: NonEmptySet[MentionId[K]],
      canonical: CanonicalId[K],
      table: MentionTable[K]
  )(using KindWitness[K]): Either[DocumentError, ExactCorefCluster[K]] =
    of(story, mentions, table).flatMap { c =>
      if c.canonical == canonical then Right(c)
      else
        Left(
          DocumentError.CanonicalMismatch(canonical.value, c.canonical.value, "ExactCorefCluster")
        )
    }

/** A partition of mentions into exact-coreference clusters: the equivalence relation `~` whose
  * quotient is the canonical graph (§44.2). Clusters are pairwise disjoint (validated) and
  * singletons are implicit: a mention in no cluster is its own class, named by the content address
  * of the singleton member set.
  */
final case class CorefPartition[K <: NarrativeKind] private (
    story: StoryId,
    clusters: Vector[ExactCorefCluster[K]]
)(using w: KindWitness[K]):
  lazy val canonicalOf: Map[MentionId[K], CanonicalId[K]] =
    clusters.flatMap(c => c.mentions.toSortedSet.toVector.map(_ -> c.canonical)).toMap

  /** `u ~ v`: same cluster, or `u == v`. Reflexive, symmetric, transitive by construction. */
  def same(u: MentionId[K], v: MentionId[K]): Boolean =
    u == v || ((canonicalOf.get(u), canonicalOf.get(v)) match
      case (Some(a), Some(b)) => a == b
      case _                  => false)

  /** The canonical node of a mention: its cluster's, or the singleton content address. */
  def canonical(m: MentionId[K]): CanonicalId[K] =
    canonicalOf.getOrElse(m, ExactCorefCluster.canonicalFor(story, Vector(m)))

  /** The quotient map π applied to a set of mentions, deterministic and typed. */
  def quotient(mentions: Iterable[MentionId[K]]): Map[MentionId[K], CanonicalId[K]] =
    mentions.toVector.distinct.map(m => m -> canonical(m)).toMap

  def size: Int = clusters.size

object CorefPartition:
  def empty[K <: NarrativeKind](story: StoryId)(using KindWitness[K]): CorefPartition[K] =
    CorefPartition(story, Vector.empty)

  /** Build from clusters, rejecting overlaps and duplicate canonical names. */
  def of[K <: NarrativeKind](
      story: StoryId,
      clusters: Vector[ExactCorefCluster[K]]
  )(using KindWitness[K]): Either[DocumentError, CorefPartition[K]] =
    val all = clusters.flatMap(_.mentions.toSortedSet.toVector)
    val dup = all.groupBy(identity).collectFirst { case (m, ms) if ms.size > 1 => m }
    val dupCanonical =
      clusters.map(_.canonical).groupBy(identity).collectFirst { case (c, cs) if cs.size > 1 => c }
    (dup, dupCanonical) match
      case (Some(m), _) => Left(DocumentError.DuplicateMention(m.value, "CorefPartition"))
      case (_, Some(c)) => Left(DocumentError.DuplicateCanonical(c.value, "CorefPartition"))
      case _            => Right(CorefPartition(story, clusters.sortBy(_.canonical)))

  /** Deterministic union–find closure of identity pairs: the result does not depend on pair order,
    * every mention must be in `table`, and each class is content-addressed from its sorted members.
    */
  def fromPairs[K <: NarrativeKind](
      story: StoryId,
      pairs: Iterable[(MentionId[K], MentionId[K])],
      table: MentionTable[K]
  )(using KindWitness[K]): Either[DocumentError, CorefPartition[K]] =
    val sorted = pairs.toVector
      .map((a, b) => if Ordering[MentionId[K]].lt(a, b) then (a, b) else (b, a))
      .distinct
      .sorted
    sorted.flatMap((a, b) => Vector(a, b)).find(m => !table.contains(m)) match
      case Some(m) => Left(DocumentError.UnknownMention(m.value, "CorefPartition.fromPairs"))
      case None    =>
        val parent = scala.collection.mutable.Map.empty[MentionId[K], MentionId[K]]
        def find(m: MentionId[K]): MentionId[K] =
          val p = parent.getOrElse(m, m)
          if p == m then m
          else
            val r = find(p)
            parent(m) = r
            r
        def union(a: MentionId[K], b: MentionId[K]): Unit =
          val ra = find(a)
          val rb = find(b)
          if ra != rb then
            // deterministic: the lexicographically smaller root wins
            if Ordering[MentionId[K]].lt(ra, rb) then parent(rb) = ra else parent(ra) = rb
        sorted.foreach { (a, b) =>
          parent.getOrElseUpdate(a, a)
          parent.getOrElseUpdate(b, b)
          union(a, b)
        }
        val classes = parent.keys.toVector.groupBy(find).toVector.sortBy(_._1).map(_._2)
        val clusters = classes.flatMap { ms =>
          NonEmptySet
            .fromSet(scala.collection.immutable.SortedSet(ms*))
            .flatMap(s => ExactCorefCluster.of(story, s, table).toOption)
        }
        of(story, clusters)
