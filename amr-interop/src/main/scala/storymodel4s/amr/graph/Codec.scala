package storymodel4s.amr.graph

import storymodel4s.amr.graph.CheckState.Unchecked
import storymodel4s.amr.graph.RoleForm.SurfaceRoles
import storymodel4s.amr.penman.*
import storymodel4s.core.DomainError

/** Where an alignment marker sat in the tree, keyed by decoded graph components. */
enum MarkerSite:
  case OnConcept(node: NodeId)
  case OnRole(edgeIndex: Int)
  case OnTarget(edgeIndex: Int)

final case class TokenMarker(site: MarkerSite, marker: AlignmentMarker)

/** Result of decoding a tree: the graph plus the trivia the graph deliberately excludes. */
final case class Decoded(
    graph: AmrGraph[Unchecked, SurfaceRoles],
    markers: Vector[TokenMarker],
    comments: Vector[String]
)

/** Tree → graph. Variables become node ids; branch order becomes edge order; `~` markers go to a
  * sidecar; inverse spellings are kept on edges (canonicalization is a separate, checked step).
  */
object Decoder:
  def decode(tree: PenmanTree): Either[DomainError, Decoded] =
    val definedNodes = Vector.newBuilder[NodeId]
    val concepts = Vector.newBuilder[(NodeId, Concept)]
    val edges = Vector.newBuilder[Edge]
    val markers = Vector.newBuilder[TokenMarker]
    var edgeIndex = 0

    def nodeId(v: Variable): Either[DomainError, NodeId] = NodeId.from(v.name)

    // Number spelling is surface trivia: the tree keeps `1e3`, the graph holds the canonical
    // `1000`, so graph identity and chart round trips do not depend on how a number was typed.
    def literalOf(l: PenmanLiteral): AmrLiteral = l match
      case PenmanLiteral.Str(s) => AmrLiteral.Text(s)
      case PenmanLiteral.Num(r) => AmrLiteral.Number(AmrLiteral.canonicalNumber(r).getOrElse(r))
      case PenmanLiteral.Sym(s) => AmrLiteral.Symbol(s)

    def walk(n: PenmanNode): Either[DomainError, Unit] =
      for
        id <- nodeId(n.variable)
        _ = definedNodes += id
        _ <- n.concept match
          case Some(ConceptToken(text, mark)) =>
            Concept.parse(text).map { c =>
              concepts += ((id, c))
              mark.foreach(m => markers += TokenMarker(MarkerSite.OnConcept(id), m))
            }
          case None => Right(())
        _ <- n.branches.foldLeft[Either[DomainError, Unit]](Right(())) { (acc, b) =>
          acc.flatMap(_ => branch(id, b))
        }
      yield ()

    def branch(source: NodeId, b: Branch): Either[DomainError, Unit] =
      SurfaceRole.parse(b.role.text).flatMap { role =>
        val idx = edgeIndex
        edgeIndex += 1
        b.role.alignment.foreach(m => markers += TokenMarker(MarkerSite.OnRole(idx), m))
        b.target match
          case Target.NodeTarget(child) =>
            nodeId(child.variable).flatMap { cid =>
              edges += Edge(source, role, AmrValue.Node(cid))
              walk(child)
            }
          case Target.VarRef(v, mark) =>
            nodeId(v).map { t =>
              mark.foreach(m => markers += TokenMarker(MarkerSite.OnTarget(idx), m))
              edges += Edge(source, role, AmrValue.Node(t))
            }
          case Target.LiteralTarget(lit, mark) =>
            mark.foreach(m => markers += TokenMarker(MarkerSite.OnTarget(idx), m))
            edges += Edge(source, role, AmrValue.Literal(literalOf(lit)))
            Right(())
      }

    for
      top <- nodeId(tree.root.variable)
      _ <- walk(tree.root)
    yield Decoded(
      AmrGraph.uncheckedWithNodes(
        top,
        definedNodes.result(),
        concepts.result(),
        edges.result(),
        tree.metadata
      ),
      markers.result(),
      tree.headers.collect { case Header.Comment(t) => t }
    )

  /** Parse and decode in one step. */
  def fromPenman(
      text: String,
      opts: PenmanParser.Options = PenmanParser.Options.default
  ): Either[String, Decoded] =
    PenmanParser.parse(text, opts).left.map(_.message).flatMap(decode(_).left.map(_.message))

  def graphFromPenman(text: String): Either[String, AmrGraph[Unchecked, SurfaceRoles]] =
    fromPenman(text).map(_.graph)

/** Graph → tree. Lays out a deterministic spanning tree from the top: outgoing edges in stored
  * order, then incoming edges from not-yet-defined nodes rendered with flipped orientation; further
  * references to already-defined nodes become variable references.
  */
object Encoder:
  enum EncodeError:
    case Unreachable(nodes: Set[NodeId])
    case MissingConcept(node: NodeId)

  def encode[C <: CheckState, R <: RoleForm](g: AmrGraph[C, R]): Either[EncodeError, PenmanTree] =
    var defined = Set.empty[NodeId]
    var usedEdges = Set.empty[Int]
    val indexed = g.edges.zipWithIndex

    def literal(l: AmrLiteral): PenmanLiteral = l match
      case AmrLiteral.Text(s)   => PenmanLiteral.Str(s)
      case AmrLiteral.Number(r) => PenmanLiteral.Num(r)
      case AmrLiteral.Symbol(s) => PenmanLiteral.Sym(s)

    def build(id: NodeId): PenmanNode =
      defined += id
      val branches = Vector.newBuilder[Branch]
      // outgoing edges as written
      indexed.foreach { case (e, i) =>
        if e.source == id && !usedEdges.contains(i) then
          usedEdges += i
          val target = e.target match
            case AmrValue.Literal(l) => Target.LiteralTarget(literal(l), None)
            case AmrValue.Node(t)    =>
              if defined.contains(t) then Target.VarRef(Variable(t.value), None)
              else Target.NodeTarget(build(t))
          branches += Branch(RoleToken(e.role.render, None), target)
      }
      // incoming edges, rendered with flipped orientation
      indexed.foreach { case (e, i) =>
        if e.target == AmrValue.Node(id) && !usedEdges.contains(i) then
          usedEdges += i
          val target =
            if defined.contains(e.source) then Target.VarRef(Variable(e.source.value), None)
            else Target.NodeTarget(build(e.source))
          branches += Branch(RoleToken(e.role.invert.render, None), target)
      }
      PenmanNode(
        Variable(id.value),
        g.concepts.get(id).map(c => ConceptToken(c.render, None)),
        branches.result()
      )

    val root = build(g.top)
    val unreachable = g.nodes.toSet -- defined
    if unreachable.nonEmpty then Left(EncodeError.Unreachable(unreachable))
    else Right(PenmanTree(g.metadata.map((k, v) => Header.Meta(k, v)), root))

  def toPenman[C <: CheckState, R <: RoleForm](
      g: AmrGraph[C, R],
      opts: PenmanPrinter.Options = PenmanPrinter.Options.default
  ): Either[EncodeError, String] = encode(g).map(PenmanPrinter.print(_, opts))
