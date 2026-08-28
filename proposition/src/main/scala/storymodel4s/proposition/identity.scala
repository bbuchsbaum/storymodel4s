package storymodel4s.proposition

import storymodel4s.core.*

/** Structural identity of charts: what two charts must share to be "the same proposition".
  *
  * Identity ignores chart-local concept ids, relation order, glosses, sense credences, normalized-
  * role credences, alignments, and provenance. It keeps lemma, kind, frame identity, source and
  * normalized roles, literal values, polarity, embeddings, and focus.
  */
object ChartIdentity:
  /** Concept signature independent of its id. */
  def conceptKey(c: Concept): String =
    val frame = c.frame.map(f => s"${f.namespace}/${f.id}").getOrElse("-")
    s"${c.kind}|${c.lemma.value}|$frame"

  def roleKey(r: RoleAssignment): String =
    s"${r.source.render}|${r.normalizedRole.map(_.toString).getOrElse("-")}"

  def targetKey(t: ConceptTarget, label: ConceptId => String): String = t match
    case ConceptTarget.Node(id)     => s"n:${label(id)}"
    case ConceptTarget.Literal(lit) => s"l:${lit.render}"
    case ConceptTarget.Unknown      => "?"

  /** Canonical serialization under a labeling. Lines are sorted so relation order is irrelevant. */
  def serialize[C <: CheckState](chart: PropositionChart[C], label: ConceptId => String): String =
    val concepts = chart.conceptIds.map(id => s"c ${label(id)} ${conceptKey(chart.concepts(id))}")
    val relations =
      chart.relations.map(r => s"r ${label(r.from)} ${roleKey(r.role)} ${targetKey(r.to, label)}")
    val polarity = chart.polarity.toVector.map((id, p) => s"p ${label(id)} $p")
    val embedded = chart.embedded.map(e => s"e ${label(e.container)} ${e.kind} ${label(e.content)}")
    val focus = chart.focus.map(id => s"f ${label(id)}").toVector
    (concepts.sorted ++ relations.sorted ++ polarity.sorted ++ embedded.sorted ++ focus)
      .mkString("\n")

/** Deterministic canonical relabeling of a chart.
  *
  * Concepts are first partitioned by iterated Weisfeiler–Leman refinement over the identity keys;
  * remaining ties are resolved exactly by enumerating orderings within tied classes and taking the
  * lexicographically least serialization, so isomorphic charts yield identical forms. Enumeration
  * is capped at [[Canonical.MaxOrderings]]; beyond that the refinement order is used and the result
  * is still isomorphic to the input but may not be unique across isomorphic inputs (documented
  * limitation; charts of that symmetry do not occur in sentence-scale semantics).
  */
object Canonical:
  val MaxOrderings: Int = 5040
  private val Prefix = "k"

  def labelOf(index: Int): ConceptId = ConceptId.unsafe(s"$Prefix$index")

  /** Color classes after refinement, in canonical class order. */
  def refine[C <: CheckState](chart: PropositionChart[C]): Vector[Vector[ConceptId]] =
    val ids = chart.conceptIds
    if ids.isEmpty then return Vector.empty
    var color: Map[ConceptId, String] = ids.map { id =>
      val base = ChartIdentity.conceptKey(chart.concepts(id))
      val pol = chart.polarityOf(id).toString
      val focus = if chart.focus.contains(id) then "F" else "-"
      id -> s"$base|$pol|$focus"
    }.toMap
    var classes = ids.size
    var iter = 0
    var stable = false
    while !stable && iter < ids.size + 1 do
      val next = ids.map { id =>
        val out = chart
          .relationsFrom(id)
          .map { r =>
            val t = r.to match
              case ConceptTarget.Node(o)      => color(o)
              case ConceptTarget.Literal(lit) => "l:" + lit.render
              case ConceptTarget.Unknown      => "?"
            s"o:${ChartIdentity.roleKey(r.role)}>$t"
          }
          .sorted
        val in = chart
          .relationsTo(id)
          .map(r => s"i:${ChartIdentity.roleKey(r.role)}<${color(r.from)}")
          .sorted
        val emb = chart.embedded.collect {
          case e if e.content == id   => s"ec:${e.kind}<${color(e.container)}"
          case e if e.container == id => s"eh:${e.kind}>${color(e.content)}"
        }.sorted
        id -> (color(id) + "{" + (out ++ in ++ emb).mkString(";") + "}")
      }.toMap
      // Compress colors to keep strings bounded while preserving the partition and its order.
      val palette = next.values.toVector.distinct.sorted.zipWithIndex.toMap
      val compressed = next.map((id, c) => id -> f"${palette(c)}%06d")
      val newClasses = compressed.values.toSet.size
      stable = newClasses == classes
      classes = newClasses
      color = compressed
      iter += 1
    ids.groupBy(color).toVector.sortBy(_._1).map(_._2.sorted)

  private def orderings(classes: Vector[Vector[ConceptId]]): Iterator[Vector[ConceptId]] =
    classes.foldLeft(Iterator.single(Vector.empty[ConceptId])) { (acc, cls) =>
      acc.flatMap(prefix => cls.permutations.map(prefix ++ _))
    }

  private def countOrderings(classes: Vector[Vector[ConceptId]]): Long =
    classes.foldLeft(1L)((acc, cls) => acc * (1L to cls.size.toLong).product)

  /** The canonical ordering of concept ids for `chart`. */
  def order[C <: CheckState](chart: PropositionChart[C]): Vector[ConceptId] =
    val classes = refine(chart)
    if classes.forall(_.size == 1) || countOrderings(classes) > MaxOrderings then classes.flatten
    else
      orderings(classes).minBy { ord =>
        val idx = ord.zipWithIndex.toMap
        ChartIdentity.serialize(chart, id => f"$Prefix${idx(id)}%04d")
      }

  /** Relabel `chart` into canonical form. */
  def form[C <: CheckState](chart: PropositionChart[C]): PropositionChart[C] =
    val idx = order(chart).zipWithIndex.toMap
    chart.relabel(id => labelOf(idx(id)))

  /** Canonical serialization (identity-invariant). */
  def serialization[C <: CheckState](chart: PropositionChart[C]): String =
    val idx = order(chart).zipWithIndex.toMap
    ChartIdentity.serialize(chart, id => f"$Prefix${idx(id)}%04d")

  /** Content address of the chart's structure, invariant under concept renaming and relation order.
    * Excludes alignments, provenance, glosses, and credences.
    */
  def checksum[C <: CheckState](chart: PropositionChart[C]): Checksum =
    Checksum.ofText(serialization(chart))

/** Exact chart isomorphism by backtracking with refinement-based pruning. */
object ChartIsomorphism:
  def isomorphic[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): Boolean =
    if a.concepts.size != b.concepts.size || a.relations.size != b.relations.size ||
      a.embedded.size != b.embedded.size || a.focus.isDefined != b.focus.isDefined
    then false
    else mapping(a, b).nonEmpty

  /** A concept bijection witnessing isomorphism, if one exists. */
  def mapping[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): Option[Map[ConceptId, ConceptId]] =
    val ca = Canonical.refine(a)
    val cb = Canonical.refine(b)
    if ca.map(_.size) != cb.map(_.size) then return None
    // Class-by-class candidate sets; colors are comparable across charts because they are computed
    // from identity keys, and equal class sequences (by size and order) are a necessary condition.
    val aIds = ca.flatten
    val candidates: Map[ConceptId, Vector[ConceptId]] =
      ca.zip(cb).flatMap((xa, xb) => xa.map(id => id -> xb)).toMap
    val aSer = ChartIdentity.serialize(a, _.value)
    def consistent(m: Map[ConceptId, ConceptId]): Boolean =
      val relabeled = a.relabel(id => m.getOrElse(id, id))
      // Compare only the parts fully determined by the mapped prefix: cheap full check at the end.
      m.size < aIds.size || ChartIdentity.serialize(relabeled, _.value) ==
        ChartIdentity.serialize(b, _.value)
    def go(
        i: Int,
        m: Map[ConceptId, ConceptId],
        used: Set[ConceptId]
    ): Option[Map[ConceptId, ConceptId]] =
      if i == aIds.size then if consistent(m) then Some(m) else None
      else
        val id = aIds(i)
        candidates(id).iterator
          .filterNot(used)
          .map(c => go(i + 1, m.updated(id, c), used + c))
          .collectFirst { case Some(r) => r }
    if aSer.isEmpty && ChartIdentity.serialize(b, _.value).isEmpty then Some(Map.empty)
    else go(0, Map.empty, Set.empty)
