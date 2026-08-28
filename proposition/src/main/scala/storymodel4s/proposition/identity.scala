package storymodel4s.proposition

import storymodel4s.core.*

/** Structural identity of charts: what two charts must share to be "the same proposition".
  *
  * Identity ignores chart-local concept ids, relation order, glosses, sense credences, normalized-
  * role credences, alignments, and provenance. It keeps lemma, kind, frame identity, source and
  * normalized roles, literal values, polarity, embeddings, and focus.
  *
  * Every free-text component is escaped before it enters a serialization line, so the line grammar
  * (`kind label key...`, space- and `|`-delimited) is injective: no lemma, frame id, literal, or
  * role name can forge a separator.
  */
object ChartIdentity:
  /** Percent-encode everything outside `[A-Za-z0-9_.-]` so separators cannot be forged. */
  def esc(s: String): String =
    val sb = new StringBuilder(s.length)
    var i = 0
    while i < s.length do
      val ch = s.charAt(i)
      val safe = (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
        ch == '_' || ch == '.' || ch == '-'
      if safe then sb.append(ch)
      else
        sb.append('%')
        val hex = Integer.toHexString(ch.toInt)
        var pad = 4 - hex.length
        while pad > 0 do
          sb.append('0')
          pad -= 1
        sb.append(hex)
      i += 1
    sb.result()

  /** Concept signature independent of its id. */
  def conceptKey(c: Concept): String =
    val frame = c.frame.map(f => s"${esc(f.namespace)}/${esc(f.id)}").getOrElse("-")
    s"${c.kind}|${esc(c.lemma.value)}|$frame"

  def roleKey(r: RoleAssignment): String =
    val normalized = r.normalizedRole match
      case Some(ParticipantRole.Custom(ns, l)) => s"Custom/${esc(ns)}/${esc(l)}"
      case Some(other)                         => other.toString
      case None                                => "-"
    s"${esc(r.source.render)}|$normalized"

  def literalKey(lit: LiteralValue): String = lit match
    case LiteralValue.Text(v)   => s"t:${esc(v)}"
    case LiteralValue.Number(v) => s"n:${LiteralValue.canonicalNumber(v)}"
    case LiteralValue.Symbol(v) => s"s:${esc(v)}"

  def targetKey(t: ConceptTarget, label: ConceptId => String): String = t match
    case ConceptTarget.Node(id)     => s"n:${esc(label(id))}"
    case ConceptTarget.Literal(lit) => s"l:${literalKey(lit)}"
    case ConceptTarget.Unknown      => "?"

  /** Canonical serialization under a labeling. Lines are sorted so relation order is irrelevant. */
  def serialize[C <: CheckState](chart: PropositionChart[C], label: ConceptId => String): String =
    val lab: ConceptId => String = id => esc(label(id))
    val concepts = chart.conceptIds.map(id => s"c ${lab(id)} ${conceptKey(chart.concepts(id))}")
    val relations =
      chart.relations.map(r => s"r ${lab(r.from)} ${roleKey(r.role)} ${targetKey(r.to, label)}")
    val polarity = chart.polarity.toVector.map((id, p) => s"p ${lab(id)} $p")
    val embedded = chart.embedded.map(e => s"e ${lab(e.container)} ${e.kind} ${lab(e.content)}")
    val focus = chart.focus.map(id => s"f ${lab(id)}").toVector
    (concepts.sorted ++ relations.sorted ++ polarity.sorted ++ embedded.sorted ++ focus)
      .mkString("\n")

/** Deterministic canonical relabeling of a chart.
  *
  * Algorithm:
  *   1. colour refinement (iterated Weisfeiler–Leman over identity keys; colours are hashes of
  *      label-free strings, so they are comparable across charts);
  *   2. concepts that are *twins* — interchangeable under an automorphism that swaps only the two
  *      of them — are grouped into cells and ordered arbitrarily, because any order within a twin
  *      cell yields the same serialization (this covers the common "many identical fillers" case
  *      without enumeration);
  *   3. remaining ties are resolved by individualization–refinement: individualize one cell,
  *      refine, recurse; the lexicographically least leaf serialization is canonical.
  *
  * Step 3 is exact but exponential on pathological symmetric charts, so it carries an explicit
  * budget, [[Canonical.MaxLeaves]]. When the budget is exhausted, [[Canonical.isExact]] is `false`
  * and the canonical serialization degrades to the label-invariant *quotient* serialization (each
  * concept labelled by its colour class). That fallback never depends on input concept ids; it may
  * identify two non-isomorphic charts of that symmetry class, which is documented and detectable.
  * [[Canonical.form]] in that case is isomorphic to the input but not unique across isomorphic
  * inputs.
  */
object Canonical:
  /** Budget on individualization–refinement leaves before falling back to the quotient form. */
  val MaxLeaves: Int = 5040
  private val Prefix = "k"
  private val QuotientHeader = "~quotient"

  def labelOf(index: Int): ConceptId = ConceptId.unsafe(s"$Prefix$index")

  private def label(index: Int): String = f"$Prefix$index%05d"

  private def initialColors[C <: CheckState](chart: PropositionChart[C]): Map[ConceptId, String] =
    chart.conceptIds.map { id =>
      val base = ChartIdentity.conceptKey(chart.concepts(id))
      val pol = chart.polarityOf(id).toString
      val focus = if chart.focus.contains(id) then "F" else "-"
      id -> Sha256.hexDigest(s"$base|$pol|$focus")
    }.toMap

  /** Iterated refinement from `start` to a stable partition; colours are cross-chart comparable. */
  private[proposition] def refineColors[C <: CheckState](
      chart: PropositionChart[C],
      start: Map[ConceptId, String]
  ): Map[ConceptId, String] =
    val ids = chart.conceptIds
    var color = start
    var classes = color.values.toSet.size
    var stable = false
    var iter = 0
    while !stable && iter < ids.size + 1 do
      val next = ids.map { id =>
        val out = chart
          .relationsFrom(id)
          .map { r =>
            val t = r.to match
              case ConceptTarget.Node(o) => color(o)
              case other                 => ChartIdentity.targetKey(other, _.value)
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
        id -> Sha256.hexDigest(color(id) + "{" + (out ++ in ++ emb).mkString(";") + "}")
      }.toMap
      val newClasses = next.values.toSet.size
      stable = newClasses == classes
      classes = newClasses
      color = next
      iter += 1
    color

  private def classesOf(colors: Map[ConceptId, String]): Vector[Vector[ConceptId]] =
    colors.groupBy(_._2).toVector.sortBy(_._1).map(_._2.keys.toVector.sorted)

  /** Colour classes after refinement, in canonical (colour) order. */
  def refine[C <: CheckState](chart: PropositionChart[C]): Vector[Vector[ConceptId]] =
    if chart.isEmpty then Vector.empty else classesOf(refineColors(chart, initialColors(chart)))

  /** Colour of every concept after refinement; equal colours are comparable across charts. */
  def colors[C <: CheckState](chart: PropositionChart[C]): Map[ConceptId, String] =
    if chart.isEmpty then Map.empty else refineColors(chart, initialColors(chart))

  /** Signature of `x` with `y` abstracted as OTHER and `x` itself as SELF. */
  private def swapSignature[C <: CheckState](
      chart: PropositionChart[C],
      x: ConceptId,
      y: ConceptId
  ): Vector[String] =
    def name(id: ConceptId): String =
      if id == x then "SELF" else if id == y then "OTHER" else "id:" + id.value
    val out = chart.relationsFrom(x).map { r =>
      val t = r.to match
        case ConceptTarget.Node(o) => name(o)
        case other                 => ChartIdentity.targetKey(other, _.value)
      s"o ${ChartIdentity.roleKey(r.role)} $t"
    }
    val in = chart.relationsTo(x).map(r => s"i ${ChartIdentity.roleKey(r.role)} ${name(r.from)}")
    val emb = chart.embedded.collect {
      case e if e.content == x   => s"ec ${e.kind} ${name(e.container)}"
      case e if e.container == x => s"eh ${e.kind} ${name(e.content)}"
    }
    (out ++ in ++ emb).sorted

  /** Two concepts are twins when swapping them is an automorphism. */
  private[proposition] def twins[C <: CheckState](
      chart: PropositionChart[C],
      a: ConceptId,
      b: ConceptId
  ): Boolean =
    a != b &&
      ChartIdentity.conceptKey(chart.concepts(a)) == ChartIdentity.conceptKey(chart.concepts(b)) &&
      chart.polarityOf(a) == chart.polarityOf(b) &&
      chart.focus.contains(a) == chart.focus.contains(b) &&
      swapSignature(chart, a, b) == swapSignature(chart, b, a)

  /** Partition one colour class into twin cells (members ordered by id; cells by first member). */
  private def twinCells[C <: CheckState](
      chart: PropositionChart[C],
      cls: Vector[ConceptId]
  ): Vector[Vector[ConceptId]] =
    val cells = Vector.newBuilder[Vector[ConceptId]]
    var acc = Vector.empty[Vector[ConceptId]]
    cls.foreach { id =>
      acc.indexWhere(cell => twins(chart, cell.head, id)) match
        case -1 => acc = acc :+ Vector(id)
        case i  => acc = acc.updated(i, acc(i) :+ id)
    }
    cells ++= acc
    cells.result()

  private final class Budget(var leaves: Int)

  /** Exhaustive individualization–refinement; `None` when the leaf budget is exhausted. */
  private def search[C <: CheckState](
      chart: PropositionChart[C],
      colors: Map[ConceptId, String],
      budget: Budget
  ): Option[(String, Vector[ConceptId])] =
    val classes = classesOf(colors)
    val cells = classes.map(cls => twinCells(chart, cls))
    cells.indexWhere(_.size > 1) match
      case -1 =>
        budget.leaves += 1
        if budget.leaves > MaxLeaves then None
        else
          val order = cells.flatten.flatten
          val idx = order.zipWithIndex.toMap
          Some((ChartIdentity.serialize(chart, id => label(idx(id))), order))
      case k =>
        var best: Option[(String, Vector[ConceptId])] = None
        var exhausted = false
        cells(k).foreach { cell =>
          if !exhausted then
            val individualized = colors ++ cell.map(id => id -> (colors(id) + "!"))
            search(chart, refineColors(chart, individualized), budget) match
              case None    => exhausted = true
              case Some(r) => if best.forall(_._1 > r._1) then best = Some(r)
        }
        if exhausted then None else best

  private def exact[C <: CheckState](
      chart: PropositionChart[C]
  ): Option[(String, Vector[ConceptId])] =
    if chart.isEmpty then Some(("", Vector.empty))
    else search(chart, colors(chart), new Budget(0))

  /** Whether the canonical form of `chart` is unique across isomorphic inputs (budget not hit). */
  def isExact[C <: CheckState](chart: PropositionChart[C]): Boolean = exact(chart).nonEmpty

  /** Label-invariant fallback order: colour classes, twin cells, members by id. Isomorphic to the
    * input but not unique across isomorphic inputs.
    */
  private def fallbackOrder[C <: CheckState](chart: PropositionChart[C]): Vector[ConceptId] =
    refine(chart).flatMap(cls => twinCells(chart, cls).flatten)

  /** The canonical ordering of concept ids for `chart`. */
  def order[C <: CheckState](chart: PropositionChart[C]): Vector[ConceptId] =
    exact(chart).map(_._2).getOrElse(fallbackOrder(chart))

  /** Relabel `chart` into canonical form: canonical ids, relations and embeddings in canonical
    * order (so two canonical forms are `==` exactly when the serializations agree and the
    * non-identity payload — alignments, provenance, sentence — also agrees).
    */
  def form[C <: CheckState](chart: PropositionChart[C]): PropositionChart[C] =
    val idx = order(chart).zipWithIndex.toMap
    val relabeled = chart.relabel(id => labelOf(idx(id)))
    val relKey: PropositionRelation => String =
      r =>
        s"${ChartIdentity.esc(r.from.value)} ${ChartIdentity.roleKey(r.role)} " +
          ChartIdentity.targetKey(r.to, _.value)
    relabeled.reordered(
      relabeled.relations.sortBy(relKey),
      relabeled.embedded.sortBy(e => (e.container.value, e.kind.ordinal, e.content.value))
    )

  /** Quotient serialization: concepts labelled by colour class. Label-invariant, not injective. */
  private def quotientSerialization[C <: CheckState](chart: PropositionChart[C]): String =
    val cs = colors(chart)
    val classIndex = cs.values.toVector.distinct.sorted.zipWithIndex.toMap
    QuotientHeader + "\n" + ChartIdentity.serialize(chart, id => label(classIndex(cs(id))))

  /** Canonical serialization (identity-invariant). Exact when [[isExact]]; quotient otherwise. */
  def serialization[C <: CheckState](chart: PropositionChart[C]): String =
    exact(chart).map(_._1).getOrElse(quotientSerialization(chart))

  /** Content address of the chart's structure, invariant under concept renaming and relation order.
    * Excludes alignments, provenance, glosses, and credences.
    */
  def checksum[C <: CheckState](chart: PropositionChart[C]): Checksum =
    Checksum.ofText(serialization(chart))

/** Exact chart isomorphism by backtracking with colour-class candidates and incremental pruning. */
object ChartIsomorphism:
  def isomorphic[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): Boolean =
    if a.concepts.size != b.concepts.size || a.relations.size != b.relations.size ||
      a.embedded.size != b.embedded.size || a.focus.isDefined != b.focus.isDefined ||
      a.polarity.size != b.polarity.size
    then false
    else mapping(a, b).nonEmpty

  /** Keys of the relations touching `x` whose other endpoint is already mapped (or is `x` itself, a
    * literal, or unknown), expressed in the image chart's ids via `img`.
    */
  private def localKeys[C <: CheckState](
      chart: PropositionChart[C],
      x: ConceptId,
      self: String,
      img: ConceptId => Option[String]
  ): Vector[String] =
    val out = chart.relationsFrom(x).flatMap { r =>
      r.to match
        case ConceptTarget.Node(t) if t == x => Some(s"o ${ChartIdentity.roleKey(r.role)} $self")
        case ConceptTarget.Node(t)           =>
          img(t).map(l => s"o ${ChartIdentity.roleKey(r.role)} $l")
        case other =>
          Some(s"o ${ChartIdentity.roleKey(r.role)} ${ChartIdentity.targetKey(other, _.value)}")
    }
    val in = chart.relationsTo(x).flatMap { r =>
      if r.from == x then None
      else img(r.from).map(l => s"i ${ChartIdentity.roleKey(r.role)} $l")
    }
    val emb = chart.embedded.flatMap { e =>
      if e.content == x then img(e.container).map(l => s"ec ${e.kind} $l")
      else if e.container == x then img(e.content).map(l => s"eh ${e.kind} $l")
      else None
    }
    (out ++ in ++ emb).sorted

  /** A concept bijection witnessing isomorphism, if one exists. */
  def mapping[A <: CheckState, B <: CheckState](
      a: PropositionChart[A],
      b: PropositionChart[B]
  ): Option[Map[ConceptId, ConceptId]] =
    if a.isEmpty && b.isEmpty then return Some(Map.empty)
    if a.concepts.size != b.concepts.size then return None
    val ca = Canonical.colors(a)
    val cb = Canonical.colors(b)
    val classesA = ca.groupBy(_._2).map((c, m) => c -> m.keys.toVector.sorted)
    val classesB = cb.groupBy(_._2).map((c, m) => c -> m.keys.toVector.sorted)
    if classesA.keySet != classesB.keySet then return None
    if classesA.exists((c, ids) => classesB(c).size != ids.size) then return None
    // Visit concepts class by class (colour order) so candidate sets are as small as possible.
    val aIds = classesA.toVector.sortBy(_._1).flatMap(_._2)
    val candidates: Map[ConceptId, Vector[ConceptId]] = aIds.map(id => id -> classesB(ca(id))).toMap
    val bSer = ChartIdentity.serialize(b, _.value)

    def compatible(m: Map[ConceptId, ConceptId], x: ConceptId, y: ConceptId): Boolean =
      ChartIdentity.conceptKey(a.concepts(x)) == ChartIdentity.conceptKey(b.concepts(y)) &&
        a.polarityOf(x) == b.polarityOf(y) &&
        a.focus.contains(x) == b.focus.contains(y) && {
          val image = m.values.toSet
          val keysA = localKeys(a, x, "SELF", t => m.get(t).map(_.value))
          val keysB = localKeys(b, y, "SELF", t => Option.when(image(t))(t.value))
          keysA == keysB
        }

    def go(
        i: Int,
        m: Map[ConceptId, ConceptId],
        used: Set[ConceptId]
    ): Option[Map[ConceptId, ConceptId]] =
      if i == aIds.size then
        if ChartIdentity.serialize(a.relabel(id => m(id)), _.value) == bSer then Some(m) else None
      else
        val x = aIds(i)
        candidates(x).iterator
          .filterNot(used)
          .filter(y => compatible(m, x, y))
          .map(y => go(i + 1, m.updated(x, y), used + y))
          .collectFirst { case Some(r) => r }

    go(0, Map.empty, Set.empty)
