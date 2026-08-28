package storymodel4s.amr.interop

import cats.data.{NonEmptyVector, Validated}
import cats.syntax.all.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles
import storymodel4s.amr.schema.FrameLexicon
import storymodel4s.proposition as p

/** Encodes a checked chart back into a standards-compatible AMR graph where AMR can express it.
  *
  * Why partial: the chart is deliberately richer in one direction (explicit unknowns, normalized
  * roles with credence, embedding kinds, alignments, glosses) and AMR in another (variables, `-of`
  * spellings). The inverse is defined on the *AMR-expressible subset*; everything outside it is
  * reported through [[InteropError.Lossy]] under the strict policy, or dropped with the lossy
  * policy. "Expressible" means exactly: `ToChart(FromChart(c)) ≅ c`, i.e. every field [[ToChart]]
  * regenerates from structure and tables is regenerated *identically*.
  *
  * Round-trip laws (tested):
  *   - `FromChart(ToChart(g)) ≅ g` (AMR isomorphism) for checked canonical graphs;
  *   - `lossReasons(c, lexicon).isEmpty ⇒ ToChart(FromChart(c)) ≅ c` (chart isomorphism).
  *
  * Reported as lossy: unknown fillers or concepts; missing focus; PENMAN-illegal lemmas; numbered
  * roles outside 0–9; named roles that are not AMR roles; alignments (AMR has no alignment layer);
  * `Unknown` polarity on a predicate (AMR would re-decode it as an assertion); `Positive` polarity
  * on a non-predicate; embeddings the embedding table would not regenerate; normalized roles the
  * standard-role table or the lexicon would not regenerate; frame sense credences, glosses, and
  * non-PropBank frame namespaces; concept kinds that differ from what structure implies; number-
  * shaped symbols (re-decoded as numbers).
  */
object FromChart:
  enum Policy:
    /** Refuse any conversion that would drop meaning. */
    case Strict

    /** Drop inexpressible parts, mapping unknown concepts to `amr-unknown`. */
    case Lossy

  /** Reasons the chart cannot be expressed exactly in AMR (empty ⇒ fully expressible). */
  def lossReasons[C <: p.CheckState](
      chart: p.PropositionChart[C],
      lexicon: FrameLexicon
  ): Vector[String] =
    val reasons = Vector.newBuilder[String]
    if chart.isEmpty then reasons += "chart has no concepts"
    if chart.focus.isEmpty && !chart.isEmpty then reasons += "chart has no focus (AMR needs a top)"
    if chart.alignments.nonEmpty then
      reasons += s"chart carries ${chart.alignments.size} alignment(s); AMR has no alignment layer"

    chart.conceptIds.foreach { id =>
      val c = chart.concepts(id)
      if c.isUnknown then reasons += s"concept ${id.value} is unknown"
      else
        if c.frame.isEmpty && Lemma.from(c.lemma.value).isLeft then
          reasons += s"lemma '${c.lemma.value}' contains PENMAN delimiters"
        c.frame.foreach { f =>
          if FrameId.from(f.id).isLeft then
            reasons += s"frame '${f.id}' is not a PropBank-shaped roleset"
          if f.namespace != InteropTables.FrameNamespace then
            reasons += s"frame '${f.id}' is in namespace '${f.namespace}', not propbank"
          if f.senseCredence.nonEmpty then
            reasons += s"frame '${f.id}' carries a sense credence AMR cannot hold"
        }
        if c.gloss.nonEmpty then reasons += s"concept ${id.value} carries a gloss AMR cannot hold"
        val expected = expectedKind(chart, id)
        if expected != c.kind then
          reasons += s"concept ${id.value} has kind ${c.kind}; AMR structure implies $expected"
        val polarity = chart.polarityOf(id)
        if c.kind == p.ConceptKind.Predicate && c.frame.nonEmpty then
          if polarity == p.Polarity.Unknown then
            reasons += s"predicate ${id.value} has unknown polarity; AMR would assert it"
        else if polarity == p.Polarity.Positive then
          reasons += s"positive polarity on non-predicate ${id.value} is not expressible"
    }

    chart.relations.zipWithIndex.foreach { (r, i) =>
      r.to match
        case p.ConceptTarget.Unknown => reasons += s"relation $i has an unknown filler"
        case p.ConceptTarget.Literal(p.LiteralValue.Symbol(s))
            if AmrLiteral.canonicalNumber(s).nonEmpty =>
          reasons += s"relation $i: symbol '$s' is number-shaped and would re-decode as a number"
        case _ => ()
      r.role.source match
        case p.SourceRole.Numbered(n) if n < 0 || n > 9    => reasons += s"relation $i: ARG$n"
        case p.SourceRole.Operand(n) if n < 1              => reasons += s"relation $i: op$n"
        case p.SourceRole.Named(n) if Role.parse(n).isLeft =>
          reasons += s"relation $i: role '$n' is not a valid AMR role"
        case _ => ()
      r.role.normalized.foreach { (role, _) =>
        val regenerated: Option[p.ParticipantRole] = r.role.source match
          case p.SourceRole.Numbered(n) =>
            chart.concepts
              .get(r.from)
              .flatMap(_.frame)
              .flatMap(f => FrameId.from(f.id).toOption)
              .flatMap(lexicon.lookup)
              .flatMap(spec => ArgIndex.from(n).toOption.flatMap(spec.arguments.get))
              .flatMap(_.functionalTag)
              .map(InteropTables.tagRole)
          case p.SourceRole.Named(n) => InteropTables.standardRoles.get(n)
          case _                     => None
        if !regenerated.contains(role) then
          reasons += s"relation $i: normalized role $role would not be regenerated from AMR"
      }
    }

    chart.embedded.zipWithIndex.foreach { (e, i) =>
      // several relations may link container and content (`:ARG1` and `:op2`); the embedding is
      // regenerable if any of them regenerates it
      val rels =
        chart.relations.filter(r => r.from == e.container && r.to.nodeId.contains(e.content))
      if rels.isEmpty then reasons += s"embedding $i has no container→content relation"
      else
        val containerConcept = chart.concepts.get(e.container).flatMap(amrConcept)
        val regenerated = rels.flatMap { r =>
          for
            cc <- containerConcept
            role <- Role.parse(roleRender(r.role.source)).toOption
            kind <- InteropTables.embeddingOf(cc, role)
          yield kind
        }
        if !regenerated.contains(e.kind) then
          reasons += s"embedding $i (${e.kind}) would not be regenerated by the embedding table"
    }
    reasons.result()

  /** The concept kind [[ToChart]] would assign from structure (mirrors its rules). */
  private def expectedKind[C <: p.CheckState](
      chart: p.PropositionChart[C],
      id: p.ConceptId
  ): p.ConceptKind =
    val c = chart.concepts(id)
    c.frame match
      case Some(f) =>
        if f.id.endsWith("-91") then p.ConceptKind.Special else p.ConceptKind.Predicate
      case None =>
        val name = c.lemma.value
        if name == "name" then p.ConceptKind.Name
        else if name.endsWith("-quantity") then p.ConceptKind.Quantity
        else if Concept.parse(name).exists(_.isInstanceOf[Concept.Special]) then
          p.ConceptKind.Special
        else
          val headsDomain =
            chart.relationsFrom(id).exists(r => roleRender(r.role.source) == "domain")
          val fillsMod = chart.relationsTo(id).exists(r => roleRender(r.role.source) == "mod")
          if headsDomain || fillsMod then p.ConceptKind.Property else p.ConceptKind.Entity

  private def roleRender(s: p.SourceRole): String = s match
    case p.SourceRole.Numbered(n)      => s"ARG$n"
    case p.SourceRole.Named(n)         => n
    case p.SourceRole.Operand(n)       => s"op$n"
    case p.SourceRole.Extension(ns, n) => s"$ns.$n"

  private def amrConcept(c: p.Concept): Option[Concept] =
    if c.isUnknown then Some(Concept.Special("amr-unknown"))
    else
      c.frame match
        case Some(f) => FrameId.from(f.id).toOption.map(Concept.Frame(_))
        case None    => Concept.parse(c.lemma.value).toOption

  def convert(
      chart: p.PropositionChart[p.Checked],
      lexicon: FrameLexicon,
      policy: Policy = Policy.Strict
  ): Either[InteropError, AmrGraph[Checked, CanonicalRoles]] =
    val reasons = lossReasons(chart, lexicon)
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

  /** Numbers are rendered in the same canonical spelling the decoder uses (plain notation, trailing
    * zeros stripped), so a chart→AMR→chart trip never changes a literal.
    */
  private def literalOf(l: p.LiteralValue): AmrLiteral = l match
    case p.LiteralValue.Text(s)   => AmrLiteral.Text(s)
    case p.LiteralValue.Number(n) =>
      AmrLiteral.Number(AmrLiteral.canonicalNumber(n.toString).getOrElse(n.toString))
    case p.LiteralValue.Symbol(s) => AmrLiteral.Symbol(s)
