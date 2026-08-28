package storymodel4s.document

import cats.data.NonEmptySet
import storymodel4s.core.*

/** Mentions that denote exactly the same entity or situation, with their canonical identity.
  *
  * Kind-indexed so an entity mention can never be clustered with a situation mention at compile
  * time. Only exact identity forms clusters; prospective, retrospective, summary, partial,
  * bridging, and thematic references are `NarrativeReference` edges, never cluster membership
  * (design record §44.1).
  */
final case class ExactCorefCluster[K <: NarrativeKind](
    mentions: NonEmptySet[MentionId[K]],
    canonical: CanonicalId[K]
):
  def contains(m: MentionId[K]): Boolean = mentions.contains(m)

/** A partition of mentions into exact-coreference clusters: the equivalence relation `~` whose
  * quotient is the canonical graph (§44.2). Clusters are pairwise disjoint (validated) and
  * singletons are implicit: a mention in no cluster is its own class.
  */
final case class CorefPartition[K <: NarrativeKind] private (
    clusters: Vector[ExactCorefCluster[K]]
):
  lazy val canonicalOf: Map[MentionId[K], CanonicalId[K]] =
    clusters.flatMap(c => c.mentions.toSortedSet.toVector.map(_ -> c.canonical)).toMap

  /** `u ~ v`: same cluster, or `u == v`. Reflexive, symmetric, transitive by construction. */
  def same(u: MentionId[K], v: MentionId[K]): Boolean =
    u == v || ((canonicalOf.get(u), canonicalOf.get(v)) match
      case (Some(a), Some(b)) => a == b
      case _                  => false)

  /** The quotient map π applied to a set of mentions: one representative per class, deterministic
    * (the cluster's canonical ID, or the mention itself as a singleton).
    */
  def quotient(mentions: Iterable[MentionId[K]]): Map[MentionId[K], String] =
    mentions.toVector.distinct
      .map(m => m -> canonicalOf.get(m).map(_.value).getOrElse(m.value))
      .toMap

  def size: Int = clusters.size

object CorefPartition:
  def empty[K <: NarrativeKind]: CorefPartition[K] = CorefPartition(Vector.empty)

  /** Build from clusters, rejecting overlaps. */
  def of[K <: NarrativeKind](
      clusters: Vector[ExactCorefCluster[K]]
  ): Either[DomainError, CorefPartition[K]] =
    val all = clusters.flatMap(_.mentions.toSortedSet.toVector)
    val dup = all.groupBy(identity).collectFirst { case (m, ms) if ms.size > 1 => m }
    val dupCanonical =
      clusters.map(_.canonical).groupBy(identity).collectFirst { case (c, cs) if cs.size > 1 => c }
    (dup, dupCanonical) match
      case (Some(m), _) => Left(DomainError.DuplicateId("CorefPartition.mention", m.value))
      case (_, Some(c)) => Left(DomainError.DuplicateId("CorefPartition.canonical", c.value))
      case _            => Right(CorefPartition(clusters.sortBy(_.canonical.value)))

  /** Deterministic union–find closure of identity pairs: the result does not depend on pair order,
    * and each class is named by `canonicalFor` applied to its sorted members.
    */
  def fromPairs[K <: NarrativeKind](
      pairs: Iterable[(MentionId[K], MentionId[K])],
      canonicalFor: NonEmptySet[MentionId[K]] => CanonicalId[K]
  ): Either[DomainError, CorefPartition[K]] =
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
    val sorted = pairs.toVector
      .map((a, b) => if Ordering[MentionId[K]].lt(a, b) then (a, b) else (b, a))
      .distinct
      .sorted
    sorted.foreach { (a, b) =>
      parent.getOrElseUpdate(a, a)
      parent.getOrElseUpdate(b, b)
      union(a, b)
    }
    val classes = parent.keys.toVector.groupBy(find).values.toVector
    val clusters = classes.flatMap { ms =>
      NonEmptySet
        .fromSet(scala.collection.immutable.SortedSet(ms*))
        .map(s => ExactCorefCluster(s, canonicalFor(s)))
    }
    of(clusters)
