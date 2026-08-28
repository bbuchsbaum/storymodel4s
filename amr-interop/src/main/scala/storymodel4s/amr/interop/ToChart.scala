package storymodel4s.amr.interop

import cats.data.NonEmptySet
import cats.syntax.all.*
import storymodel4s.amr.align.*
import storymodel4s.amr.graph.*
import storymodel4s.amr.graph.CheckState.Checked
import storymodel4s.amr.graph.RoleForm.CanonicalRoles
import storymodel4s.amr.schema.FrameLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p

/** Decodes a checked, canonical-role AMR graph into the canonical [[p.PropositionChart]].
  *
  * Why a one-way, table-driven decoder: the chart is the contract every narrative layer consumes;
  * AMR is one acquisition path into it. Nothing here consults the lexicon to *decide* structure —
  * the lexicon only licenses an optional normalized role on a numbered argument.
  *
  * Mapping summary:
  *   - frame concept `want-01` → `Concept(want, frame = propbank/want-01, Predicate)`; `-91`
  *     rolesets → `Special`; `name` → `Name`; `*-quantity` → `Quantity`; other AMR specials →
  *     `Special`; lexical concepts → `Property` when they head a `:domain` or fill a `:mod`, else
  *     `Entity`.
  *   - `:ARGn` → `Numbered(n)`, normalized only via a lexicon functional tag (weak credence);
  *     stable standard roles → `Named` with a high-credence normalized role; `:opN` → `Operand`;
  *     `:sntN` → `Named("sntN")`; extension roles → `Extension`.
  *   - `:polarity -` → chart polarity `Negative` on that concept; every other predicate is
  *     `Positive` (AMR asserts unnegated predicates).
  *   - table-listed container/role pairs with a predicate filler → `EmbeddedProposition`.
  *   - alignment sidecar entries → `PropositionAlignment`s; relation alignments on the polarity
  *     edge fall back to the source concept.
  *   - provenance: `Converted("amr", fingerprint)` plus a receipt whose `inputChecksum` is the AMR
  *     `Canonical.digest`, so the source artifact stays recoverable.
  */
object ToChart:
  val Version = "0.1"
  val Provider = "amr-interop"

  def fingerprint: Fingerprint = Fingerprint.unsafe(s"$Provider:ToChart:$Version")

  def convert(
      graph: AmrGraph[Checked, CanonicalRoles],
      alignment: Option[AmrAlignment],
      lexicon: FrameLexicon,
      sentence: Option[SurfaceUnitId],
      receipts: Vector[ProviderCall] = Vector.empty
  ): Either[InteropError, p.PropositionChart[p.Checked]] =
    for
      ids <- graph.nodes
        .traverse(n => conceptId(n).map(n -> _))
        .map(_.toMap)
      concepts <- graph.nodes.traverse(n => conceptOf(graph, n).map(ids(n) -> _)).map(_.toMap)
      built = build(graph, ids, concepts, lexicon)
      aligned = alignment.map(a => alignments(a, ids, built.relations)).getOrElse(Vector.empty)
      preliminary = p.PropositionChart.unchecked(
        focus = Some(ids(graph.top)),
        concepts = concepts,
        relations = built.relations,
        polarity = built.polarity,
        embedded = built.embedded,
        alignments = aligned,
        provenance = p.ChartProvenance.hand,
        sentence = sentence
      )
      digest = Canonical.digest(graph)
      call = ProviderCall(
        provider = Provider,
        model = "ToChart",
        version = Version,
        promptTemplateVersion = None,
        inputChecksum = digest,
        outputChecksum = p.Canonical.checksum(preliminary),
        params = Map("lexicon" -> lexicon.version),
        seed = None,
        cached = false
      )
      withProvenance = p.PropositionChart.unchecked(
        focus = preliminary.focus,
        concepts = preliminary.concepts,
        relations = preliminary.relations,
        polarity = preliminary.polarity,
        embedded = preliminary.embedded,
        alignments = preliminary.alignments,
        provenance = p.ChartProvenance(
          p.ChartOrigin.Converted("amr", fingerprint),
          receipts :+ call,
          Vector.empty
        ),
        sentence = sentence
      )
      checked <- p.ChartValidator.check(withProvenance).leftMap(InteropError.ChartInvalid(_))
    yield checked

  /** The AMR digest recorded on a converted chart, if it was produced by this decoder. */
  def sourceDigest[C <: p.CheckState](chart: p.PropositionChart[C]): Option[Checksum] =
    chart.provenance.receipts
      .find(c => c.provider == Provider && c.model == "ToChart")
      .map(_.inputChecksum)

  private final case class Built(
      relations: Vector[p.PropositionRelation],
      polarity: Map[p.ConceptId, p.Polarity],
      embedded: Vector[p.EmbeddedProposition]
  )

  private def conceptId(n: NodeId): Either[InteropError, p.ConceptId] =
    p.ConceptId
      .from(n.value)
      .leftMap(e => InteropError.InvalidIdentifier("ConceptId", n.value, e.message))

  private def lemma(raw: String): Either[InteropError, p.Lemma] =
    p.Lemma.from(raw).leftMap(e => InteropError.InvalidIdentifier("Lemma", raw, e.message))

  private def conceptOf(
      g: AmrGraph[Checked, CanonicalRoles],
      n: NodeId
  ): Either[InteropError, p.Concept] =
    g.concepts(n) match
      case Concept.Frame(f) =>
        val kind =
          if InteropTables.isSpecialFrame(f) then p.ConceptKind.Special else p.ConceptKind.Predicate
        lemma(f.lemma).map { l =>
          p.Concept(l, None, Some(p.FrameRef(InteropTables.FrameNamespace, f.value, None)), kind)
        }
      case Concept.Special(name) =>
        val kind =
          if name == "name" then p.ConceptKind.Name
          else if name.endsWith("-quantity") then p.ConceptKind.Quantity
          else p.ConceptKind.Special
        lemma(name).map(l => p.Concept(l, None, None, kind))
      case Concept.Lexical(l) =>
        val headsDomain = g.relations(n).exists((r, _) => r.render == "domain")
        val fillsMod = g.incoming(n).flatMap(_.canonical).exists((_, r, _) => r.render == "mod")
        val kind = if headsDomain || fillsMod then p.ConceptKind.Property else p.ConceptKind.Entity
        lemma(l.value).map(x => p.Concept(x, None, None, kind))

  private def build(
      g: AmrGraph[Checked, CanonicalRoles],
      ids: Map[NodeId, p.ConceptId],
      concepts: Map[p.ConceptId, p.Concept],
      lexicon: FrameLexicon
  ): Built =
    val relations = Vector.newBuilder[p.PropositionRelation]
    val embedded = Vector.newBuilder[p.EmbeddedProposition]
    var polarity = Map.empty[p.ConceptId, p.Polarity]

    g.canonicalTriples.foreach { (s, role, target) =>
      (role, target) match
        case (r, AmrValue.Literal(AmrLiteral.Symbol("-"))) if r == Role.polarity =>
          polarity = polarity.updated(ids(s), p.Polarity.Negative)
        case _ =>
          relations += p.PropositionRelation(
            ids(s),
            roleOf(g, s, role, lexicon),
            targetOf(ids, target)
          )
          target.nodeId.foreach { t =>
            val fillerIsPredicate = g.concepts(t) match
              case Concept.Frame(_) => true
              case _                => false
            // A self-loop is a reentrancy artefact, not a held proposition.
            if fillerIsPredicate && t != s then
              InteropTables.embeddingOf(g.concepts(s), role).foreach { kind =>
                embedded += p.EmbeddedProposition(ids(s), kind, ids(t))
              }
          }
    }

    concepts.foreach { (id, c) =>
      if c.kind == p.ConceptKind.Predicate && !polarity.contains(id) then
        polarity = polarity.updated(id, p.Polarity.Positive)
    }

    Built(relations.result(), polarity, embedded.result())

  private def roleOf(
      g: AmrGraph[Checked, CanonicalRoles],
      source: NodeId,
      role: Role,
      lexicon: FrameLexicon
  ): p.RoleAssignment =
    role match
      case Role.Arg(i) =>
        val licensed = g.concepts(source) match
          case Concept.Frame(f) =>
            lexicon
              .lookup(f)
              .flatMap(_.arguments.get(i))
              .flatMap(_.functionalTag)
              .map(tag =>
                (InteropTables.tagRole(tag), Credence.unsafeRaw(InteropTables.LexiconCredence))
              )
          case _ => None
        p.RoleAssignment(p.SourceRole.Numbered(i.value), licensed)
      case Role.Standard(n) =>
        val normalized = InteropTables.standardRoles
          .get(n.value)
          .map(r => (r, Credence.unsafeRaw(InteropTables.StandardRoleCredence)))
        p.RoleAssignment(p.SourceRole.Named(n.value), normalized)
      case Role.Operand(i)       => p.RoleAssignment(p.SourceRole.Operand(i.value), None)
      case Role.Sentence(i)      => p.RoleAssignment(p.SourceRole.Named(s"snt${i.value}"), None)
      case Role.Extension(ns, n) => p.RoleAssignment(p.SourceRole.Extension(ns, n), None)

  private def targetOf(ids: Map[NodeId, p.ConceptId], v: AmrValue): p.ConceptTarget =
    v match
      case AmrValue.Node(t)                     => p.ConceptTarget.Node(ids(t))
      case AmrValue.Literal(AmrLiteral.Text(s)) => p.ConceptTarget.Literal(p.LiteralValue.Text(s))
      case AmrValue.Literal(l @ AmrLiteral.Number(raw)) =>
        l.numeric match
          case Some(bd) => p.ConceptTarget.Literal(p.LiteralValue.Number(bd))
          case None     => p.ConceptTarget.Literal(p.LiteralValue.Symbol(raw))
      case AmrValue.Literal(AmrLiteral.Symbol(s)) =>
        p.ConceptTarget.Literal(p.LiteralValue.Symbol(s))

  private def alignments(
      a: AmrAlignment,
      ids: Map[NodeId, p.ConceptId],
      relations: Vector[p.PropositionRelation]
  ): Vector[p.PropositionAlignment] =
    def conceptsTarget(nodes: Iterable[NodeId]): Option[p.AlignmentTarget] =
      NonEmptySet
        .fromSet(scala.collection.immutable.SortedSet.from(nodes.flatMap(ids.get)))
        .map(p.AlignmentTarget.Concepts(_))

    def relationTarget(e: Edge): Option[p.AlignmentTarget] =
      e.canonical.flatMap { (s, role, t) =>
        val from = ids.get(s)
        val matching = relations.find { r =>
          from.contains(r.from) && r.role.source.render == roleRender(role) &&
          targetMatches(ids, r.to, t)
        }
        matching
          .map(p.AlignmentTarget.Relation(_))
          .orElse(conceptsTarget(Vector(s)))
      }

    a.entries.flatMap {
      case AlignmentEntry.Subgraph(x) =>
        conceptsTarget(x.nodes.toSortedSet)
          .map(p.PropositionAlignment(_, x.spans, x.credence, x.meta))
      case AlignmentEntry.Duplicate(x) =>
        conceptsTarget(x.nodes.toSortedSet)
          .map(p.PropositionAlignment(_, x.spans, x.credence, x.meta))
      case AlignmentEntry.Reentrancy(x) =>
        conceptsTarget(Vector(x.node)).map(p.PropositionAlignment(_, x.spans, x.credence, x.meta))
      case AlignmentEntry.Relation(x) =>
        relationTarget(x.edge).map(p.PropositionAlignment(_, x.spans, x.credence, x.meta))
    }

  private def roleRender(role: Role): String = role match
    case Role.Sentence(i) => s"snt${i.value}"
    case other            => other.render

  private def targetMatches(
      ids: Map[NodeId, p.ConceptId],
      chartTarget: p.ConceptTarget,
      amrTarget: AmrValue
  ): Boolean =
    (chartTarget, amrTarget) match
      case (p.ConceptTarget.Node(c), AmrValue.Node(n))       => ids.get(n).contains(c)
      case (p.ConceptTarget.Literal(l), AmrValue.Literal(m)) =>
        (l, m) match
          case (p.LiteralValue.Text(x), AmrLiteral.Text(y))         => x == y
          case (p.LiteralValue.Symbol(x), AmrLiteral.Symbol(y))     => x == y
          case (p.LiteralValue.Number(x), n @ AmrLiteral.Number(_)) => n.numeric.contains(x)
          case _                                                    => false
      case _ => false
