package storymodel4s.amr.interop

import cats.data.{NonEmptyVector, Validated}
import cats.syntax.all.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles
import storymodel4s.proposition as p

/** Encodes a checked chart back into a standards-compatible AMR graph where AMR can express it.
  *
  * Why partial: the chart is deliberately richer in one direction (explicit unknowns, normalized
  * roles with credence, embedding kinds) and AMR in another (variables, `-of` spellings). The
  * inverse is defined on the *AMR-expressible subset*; everything outside it is reported through
  * [[InteropError.Lossy]] under the strict policy, or dropped with the lossy policy.
  *
  * Round-trip laws (tested):
  *   - `FromChart(ToChart(g)) ≅ g` (AMR isomorphism) for checked canonical graphs without alignment
  *     markers;
  *   - `ToChart(FromChart(c)) ≅ c` (chart isomorphism) for charts in the expressible subset — in
  *     particular charts produced by [[ToChart]], since predicate polarity, concept kinds, and
  *     embeddings are all regenerated from structure and tables.
  *
  * Not representable (lossy): `ConceptTarget.Unknown` fillers, `Concept.unknown` concepts, charts
  * without a focus, embeddings with no container→content relation, lemmas containing PENMAN
  * delimiters, and numbered roles outside 0–9. Normalized roles are *not* lossy: they are
  * regenerated from the standard-role table or the lexicon on the way back.
  */
object FromChart:
  enum Policy:
    /** Refuse any conversion that would drop meaning. */
    case Strict

    /** Drop inexpressible parts, mapping unknown concepts to `amr-unknown`. */
    case Lossy

  /** Reasons the chart cannot be expressed exactly in AMR (empty ⇒ fully expressible). */
  def lossReasons[C <: p.CheckState](chart: p.PropositionChart[C]): Vector[String] =
    val reasons = Vector.newBuilder[String]
    if chart.isEmpty then reasons += "chart has no concepts"
    if chart.focus.isEmpty && !chart.isEmpty then reasons += "chart has no focus (AMR needs a top)"
    chart.conceptIds.foreach { id =>
      val c = chart.concepts(id)
      if c.isUnknown then reasons += s"concept ${id.value} is unknown"
      else if c.frame.isEmpty && Lemma.from(c.lemma.value).isLeft then
        reasons += s"lemma '${c.lemma.value}' contains PENMAN delimiters"
      else if c.frame.exists(f => FrameId.from(f.id).isLeft) then
        reasons += s"frame '${c.frame.get.id}' is not a PropBank-shaped roleset"
    }
    chart.relations.zipWithIndex.foreach { (r, i) =>
      r.to match
        case p.ConceptTarget.Unknown => reasons += s"relation $i has an unknown filler"
        case _                       => ()
      r.role.source match
        case p.SourceRole.Numbered(n) if n < 0 || n > 9    => reasons += s"relation $i: ARG$n"
        case p.SourceRole.Operand(n) if n < 1              => reasons += s"relation $i: op$n"
        case p.SourceRole.Named(n) if Role.parse(n).isLeft =>
          reasons += s"relation $i: role '$n' is not a valid AMR role"
        case _ => ()
    }
    val relationSet = chart.relations.map(r => (r.from, r.to.nodeId)).toSet
    chart.embedded.zipWithIndex.foreach { (e, i) =>
      if !relationSet.contains((e.container, Some(e.content))) then
        reasons += s"embedding $i has no container→content relation"
    }
    reasons.result()

  def convert(
      chart: p.PropositionChart[p.Checked],
      policy: Policy = Policy.Strict
  ): Either[InteropError, AmrGraph[Checked, CanonicalRoles]] =
    val reasons = lossReasons(chart)
    (policy, NonEmptyVector.fromVector(reasons)) match
      case (Policy.Strict, Some(rs)) => Left(InteropError.Lossy(rs))
      case _                         => encode(chart)

  private def encode(
      chart: p.PropositionChart[p.Checked]
  ): Either[InteropError, AmrGraph[Checked, CanonicalRoles]] =
    for
      topId <- chart.focus
        .orElse(chart.predicates.headOption)
        .orElse(chart.conceptIds.headOption)
        .toRight(InteropError.Malformed("empty chart"))
      ids <- chart.conceptIds.traverse(c => nodeId(c).map(c -> _)).map(_.toMap)
      concepts <- chart.conceptIds.traverse(c => conceptOf(chart.concepts(c)).map(ids(c) -> _))
      edges <- chart.relations.traverse(r => edgeOf(ids, r)).map(_.flatten)
      polarityEdges = chart.polarity.toVector.collect { case (id, p.Polarity.Negative) =>
        Edge(ids(id), SurfaceRole.direct(Role.polarity), AmrValue.Literal(AmrLiteral.Symbol("-")))
      }
      unchecked = AmrGraph.unchecked(ids(topId), concepts, edges ++ polarityEdges.sortBy(_.render))
      graph <- RoleCanonicalizer.fromUnchecked(unchecked) match
        case Validated.Valid(g)   => Right(g)
        case Validated.Invalid(e) => Left(InteropError.GraphInvalid(e.toChain.toVector))
    yield graph

  private def nodeId(c: p.ConceptId): Either[InteropError, NodeId] =
    NodeId.from(c.value).leftMap(e => InteropError.InvalidIdentifier("NodeId", c.value, e.message))

  private def conceptOf(c: p.Concept): Either[InteropError, Concept] =
    if c.isUnknown then Right(Concept.Special("amr-unknown"))
    else
      c.frame match
        case Some(f) =>
          FrameId.from(f.id) match
            case Right(id) => Right(Concept.Frame(id))
            case Left(_)   => lexical(c.lemma.value)
        case None => lexical(c.lemma.value)

  private def lexical(raw: String): Either[InteropError, Concept] =
    Concept.parse(raw).leftMap(e => InteropError.InvalidIdentifier("Concept", raw, e.message))

  private def edgeOf(
      ids: Map[p.ConceptId, NodeId],
      r: p.PropositionRelation
  ): Either[InteropError, Option[Edge]] =
    val role: Either[InteropError, Role] = r.role.source match
      case p.SourceRole.Numbered(n) =>
        ArgIndex
          .from(n)
          .bimap(
            e => InteropError.InvalidIdentifier("ArgIndex", n.toString, e.message),
            Role.Arg(_)
          )
      case p.SourceRole.Named(n) =>
        Role.parse(n).leftMap(e => InteropError.InvalidIdentifier("Role", n, e.message))
      case p.SourceRole.Operand(n) =>
        PosIndex
          .from(n)
          .bimap(
            e => InteropError.InvalidIdentifier("PosIndex", n.toString, e.message),
            Role.Operand(_)
          )
      case p.SourceRole.Extension(ns, n) => Right(Role.Extension(ns, n))
    val target: Option[AmrValue] = r.to match
      case p.ConceptTarget.Node(id)     => Some(AmrValue.Node(ids(id)))
      case p.ConceptTarget.Literal(lit) => Some(AmrValue.Literal(literalOf(lit)))
      case p.ConceptTarget.Unknown      => None
    role.map(ro => target.map(t => Edge(ids(r.from), SurfaceRole.direct(ro), t)))

  private def literalOf(l: p.LiteralValue): AmrLiteral = l match
    case p.LiteralValue.Text(s)   => AmrLiteral.Text(s)
    case p.LiteralValue.Number(n) => AmrLiteral.Number(n.toString)
    case p.LiteralValue.Symbol(s) => AmrLiteral.Symbol(s)
