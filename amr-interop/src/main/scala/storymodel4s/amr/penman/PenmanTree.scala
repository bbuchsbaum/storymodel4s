package storymodel4s.amr.penman

/** Token alignment marker attached to a concept, role, or target (`~e.1,2` or `~3`).
  *
  * Why: alignment markers are PENMAN trivia, not graph semantics; the tree keeps them losslessly
  * and the decoder moves them into a sidecar rather than into the graph.
  */
final case class AlignmentMarker(prefix: Option[String], indices: Vector[Int]):
  def render: String = "~" + prefix.map(_ + ".").getOrElse("") + indices.mkString(",")

/** A PENMAN variable as written (e.g. `b`, `s2`). */
final case class Variable(name: String)

/** A literal in target position. `Num` keeps the raw spelling so printing is lossless. */
enum PenmanLiteral:
  case Str(value: String)
  case Num(raw: String)
  case Sym(value: String)

  def render: String = this match
    case Str(v) => "\"" + PenmanLiteral.escape(v) + "\""
    case Num(r) => r
    case Sym(v) => v

object PenmanLiteral:
  def escape(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\t' => "\\t"
      case c    => c.toString
    }

/** A concept token after `/`, with an optional alignment marker. */
final case class ConceptToken(text: String, alignment: Option[AlignmentMarker])

/** A role token as spelled (without the leading colon), e.g. `ARG0-of`, `op1`, `polarity`. */
final case class RoleToken(text: String, alignment: Option[AlignmentMarker])

/** The target of a branch. */
enum Target:
  /** A nested node definition. */
  case NodeTarget(node: PenmanNode)

  /** A reference to a variable defined elsewhere in the tree (reentrancy). */
  case VarRef(variable: Variable, alignment: Option[AlignmentMarker])

  /** A literal value. */
  case LiteralTarget(literal: PenmanLiteral, alignment: Option[AlignmentMarker])

final case class Branch(role: RoleToken, target: Target)

/** A node definition `(var / concept :role target ...)`. Concept may be absent (`(b)`). */
final case class PenmanNode(
    variable: Variable,
    concept: Option[ConceptToken],
    branches: Vector[Branch]
):
  /** All variables defined (not merely referenced) in this subtree, in document order. */
  def definedVariables: Vector[Variable] =
    variable +: branches.flatMap {
      case Branch(_, Target.NodeTarget(n)) => n.definedVariables
      case _                               => Vector.empty
    }

/** A header line preceding the graph: `# ::key value` metadata or a plain `# comment`. */
enum Header:
  case Meta(key: String, value: String)
  case Comment(text: String)

/** A complete PENMAN document: header lines plus one rooted node tree.
  *
  * Losslessness profile: variables, branch order, inverse spellings, literal spellings, metadata,
  * comments, and alignment markers are preserved; whitespace and line layout are not. A metadata
  * line carrying several `::key value` pairs is expanded into one `Meta` header per pair.
  */
final case class PenmanTree(headers: Vector[Header], root: PenmanNode):
  def metadata: Vector[(String, String)] = headers.collect { case Header.Meta(k, v) => (k, v) }

object PenmanTree:
  def apply(root: PenmanNode): PenmanTree = PenmanTree(Vector.empty, root)

/** Typed syntax failure with a zero-based character offset into the input. */
enum PenmanError:
  case Syntax(offset: Int, expected: String)
  case DuplicateVariable(variable: Variable, offset: Int)
  case UnbalancedParentheses(offset: Int)
  case UndefinedVariable(variable: Variable, offset: Int)
  case Empty

  /** Nesting deeper than the parser's bound (`PenmanParser.MaxDepth`): refused, never a stack
    * overflow.
    */
  case TooDeep(offset: Int, limit: Int)

  def message: String = this match
    case Syntax(o, e)             => s"syntax error at $o: expected $e"
    case DuplicateVariable(v, o)  => s"variable '${v.name}' defined twice (second at $o)"
    case UnbalancedParentheses(o) => s"unbalanced parentheses at $o"
    case UndefinedVariable(v, o)  => s"undefined variable '${v.name}' referenced at $o"
    case Empty                    => "empty input"
    case TooDeep(o, l)            => s"nesting deeper than $l at $o"
