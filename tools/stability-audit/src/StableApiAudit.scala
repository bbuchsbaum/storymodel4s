package storymodel4s.stabilityaudit

import scala.collection.mutable
import scala.quoted.*
import scala.tasty.inspector.*

/** A deliberately bounded TASTy signature walker for the pre-freeze audit.
  *
  * It records declarations reachable from named roots; it never promotes a declaration merely
  * because it can reach a root.  Bodies are not inspected.
  */
object StableApiAudit extends Inspector:
  private final case class Edge(from: String, to: String, kind: String, detail: String)
  private final case class Member(id: String, exposedBy: String, origin: String)
  private var requestedRoots = Set.empty[String]

  private def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def json(values: Iterable[String]): String = values.mkString("[", ",", "]")
  private def id(using Quotes)(symbol: quotes.reflect.Symbol): String =
    symbol.fullName.stripSuffix("$").replace("$.", ".")

  def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
    import quotes.reflect.*
    val declaration = mutable.LinkedHashMap.empty[String, Vector[Symbol]]
    val edges = mutable.LinkedHashSet.empty[Edge]
    val unresolved = mutable.LinkedHashSet.empty[String]
    val external = mutable.LinkedHashSet.empty[String]
    val nominal = mutable.LinkedHashSet.empty[String]
    val members = mutable.LinkedHashSet.empty[Member]
    object collect extends TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case d: Definition if d.symbol != Symbol.noSymbol && id(d.symbol).startsWith("storymodel4s.") =>
            val name = id(d.symbol)
            declaration.update(name, declaration.getOrElse(name, Vector.empty) :+ d.symbol)
          case _ => ()
        super.traverseTree(tree)(owner)
    tastys.foreach(t => collect.traverseTree(t.ast)(Symbol.spliceOwner))
    def nested(parent: Symbol, name: String): Vector[Symbol] =
      val scopes = Vector(parent, parent.moduleClass, parent.companionModule, parent.companionClass)
      scopes.iterator
        .flatMap(s => s.declaredTypes ++ s.methodMembers)
        .filter(s => s.name == name || id(s).endsWith("." + name))
        .toVector
    def resolve(r: String): Vector[Symbol] =
      declaration.get(r).orElse(declaration.get(r + ".type")).getOrElse {
        declaration.keys.toVector.sortBy(-_.length).collectFirst {
          case prefix if r.startsWith(prefix + ".") =>
            r.stripPrefix(prefix + ".").split("\\.").foldLeft(declaration(prefix)) {
              case (owners, part) => owners.flatMap(owner => nested(owner, part))
            }
        }.getOrElse(Vector.empty)
      }
    val roots = requestedRoots.toVector.flatMap { r =>
      val resolved = resolve(r)
      if resolved.isEmpty then unresolved += s"missing-root:$r"
      resolved
    }
    val seen = mutable.HashSet.empty[String]
    def key(symbol: Symbol): String = id(symbol) + " :: " + symbol.info.show
    def public(symbol: Symbol): Boolean =
      !symbol.flags.is(Flags.Private) && !symbol.flags.is(Flags.PrivateLocal)
    def recordMember(member: Symbol, exposedBy: Symbol, origin: String): Unit =
      if public(member) then
        members += Member(key(member), key(exposedBy), origin)
        // Qualified privacy is represented separately from bare Private in TASTy. Retain these
        // construction doors, but do not silently classify them as a caller-supported API.
        if member.privateWithin.nonEmpty then
          members += Member(key(member), key(exposedBy), "qualified-private")
    def visit(symbol: Symbol): Unit =
      val name = key(symbol)
      if seen.add(name) then
        typeEdge(symbol, symbol.info, "signature")
        symbol.tree match
          case d: ClassDef =>
            nominal += name
            // `parents` can be a TypeTree or an applied Term (`extends Parent(1)`).  The latter
            // carries both the parent type and its constructor MethodType only as a Term.
            d.parents.foreach {
              case p: TypeTree => typeEdge(symbol, p.tpe, "parent")
              case p: Term     => typeEdge(symbol, p.tpe, "parent-term")
              case _           => ()
            }
            memberEdges(symbol, d.constructor.symbol)
            companionFactoryEdges(symbol)
            // Public alternatives are part of a sealed result contract even when their
            // constructors return the narrower alternative type rather than the base.
            symbol.children.filter(public).foreach { alternative =>
              edges += Edge(key(symbol), key(alternative), "public-alternative", alternative.name)
              visit(alternative)
            }
          case d: DefDef =>
            recordMember(symbol, symbol.owner, "declaration")
            typeEdge(symbol, d.returnTpt.tpe, "return")
          case d: ValDef =>
            recordMember(symbol, symbol.owner, "declaration")
            typeEdge(symbol, d.tpt.tpe, "accessor")
          case _: TypeDef => nominal += name
          case _ => ()
    def typeEdge(from: Symbol, t: TypeRepr, kind: String): Unit =
      def symbolEdge(repr: TypeRepr, edgeKind: String): Unit =
        val s = repr.typeSymbol
        if s != Symbol.noSymbol then
          val target = id(s)
          if target.startsWith("storymodel4s.") then
            edges += Edge(key(from), key(s), edgeKind, repr.show)
            visit(s)
          else if target.nonEmpty && !target.startsWith("scala.") && !target.startsWith("java.") then external += target
      // Keep the nominal type before looking through an alias. In particular, `OpaqueId.T`
      // must remain visible even though its transparent representation is String internally.
      symbolEdge(t, kind + ":nominal")
      val normalized = t.dealias
      if normalized != t then symbolEdge(normalized, kind + ":dealias")
      normalized match
        case mt: MethodType =>
          mt.paramTypes.foreach(typeEdge(from, _, kind + ":parameter"))
          typeEdge(from, mt.resType, kind + ":result")
        case pt: PolyType =>
          pt.paramBounds.foreach { bounds =>
            typeEdge(from, bounds.low, kind + ":lower-bound")
            typeEdge(from, bounds.hi, kind + ":upper-bound")
          }
          typeEdge(from, pt.resType, kind + ":poly-result")
        case bounds: TypeBounds =>
          typeEdge(from, bounds.low, kind + ":lower-bound")
          typeEdge(from, bounds.hi, kind + ":upper-bound")
        case applied: AppliedType =>
          typeEdge(from, applied.tycon, kind + ":constructor")
          applied.args.foreach(typeEdge(from, _, kind + ":argument"))
        case refinement: Refinement =>
          typeEdge(from, refinement.parent, kind + ":refinement-parent")
          typeEdge(from, refinement.info, kind + ":refinement-info")
        case andOr: AndOrType =>
          typeEdge(from, andOr.left, kind + ":left")
          typeEdge(from, andOr.right, kind + ":right")
        case byName: ByNameType => typeEdge(from, byName.underlying, kind + ":by-name")
        case annotated: AnnotatedType => typeEdge(from, annotated.underlying, kind + ":annotated")
        case _ => normalized.typeArgs.foreach(typeEdge(from, _, kind + ":argument"))
    /** Each member is an inventory vertex because a stable nominal can expose only selected
      * members.  In particular, attribute every dependency to the member that carries the
      * signature rather than to its receiver class.
      */
    def memberEdges(owner: Symbol, constructor: Symbol): Unit =
      val exposed = (owner.methodMembers ++ owner.fieldMembers :+ constructor).distinct
      exposed.filter(public).foreach { member =>
        val origin =
          if member == constructor then "constructor"
          else if member.flags.is(Flags.ParamAccessor) then "parameter-accessor"
          else if member.flags.is(Flags.CaseAccessor) then "case-accessor"
          else if member.flags.is(Flags.Synthetic) then "generated"
          else if member.flags.is(Flags.Protected) then "protected"
          else if member.owner != owner then "inherited"
          else "member"
        recordMember(member, owner, origin)
        edges += Edge(key(owner), key(member), "member-exposure", origin)
        typeEdge(member, member.info, if origin == "constructor" then "constructor" else "member")
        visit(member)
      }
    /** Public companion methods are construction doors only when their result structurally
      * contains this reached class. This admits `build` and wrappers such as `Option[A]` without
      * turning unrelated companion behavior into a root.
      */
    def companionFactoryEdges(owner: Symbol): Unit =
      owner.companionModule.methodMembers.filter(m => public(m) && resultMentions(m.info, owner)).foreach { m =>
        recordMember(m, owner, "companion-factory")
        edges += Edge(key(owner), key(m), "member-exposure", "companion-factory")
        edges += Edge(key(owner), key(m), "companion-factory", m.name)
        typeEdge(m, m.info, "companion-factory")
        visit(m)
      }
    def resultMentions(t: TypeRepr, target: Symbol): Boolean =
      def loop(repr: TypeRepr): Boolean =
        val normalized = repr.dealias
        if repr.typeSymbol == target || normalized.typeSymbol == target then true
        else
          normalized match
            case mt: MethodType => loop(mt.resType)
            case pt: PolyType   => loop(pt.resType)
            case applied: AppliedType => loop(applied.tycon) || applied.args.exists(loop)
            case refinement: Refinement => loop(refinement.parent) || loop(refinement.info)
            case andOr: AndOrType       => loop(andOr.left) || loop(andOr.right)
            case byName: ByNameType     => loop(byName.underlying)
            case annotated: AnnotatedType => loop(annotated.underlying)
            case other => other.typeArgs.exists(loop)
      loop(t)
    roots.foreach(visit)
    val out = sys.props("audit.output")
    val inventory = seen.toVector.sorted.map(q).mkString("[", ",", "]")
    val nominals = nominal.toVector.sorted.map(q).mkString("[", ",", "]")
    val memberInventory = members.toVector.groupBy(_.id).toVector.sortBy(_._1).map { case (id, records) =>
      val exposedBy = records.map(_.exposedBy).distinct.sorted.map(q).mkString("[", ",", "]")
      val origins = records.map(_.origin).distinct.sorted.map(q).mkString("[", ",", "]")
      s"{\"id\":${q(id)},\"exposedBy\":$exposedBy,\"origins\":$origins}"
    }
    val renderedEdges = edges.toVector.filter(e => seen(e.from)).sortBy(e => (e.from, e.to, e.kind)).map { e =>
      s"{\"from\":${q(e.from)},\"to\":${q(e.to)},\"kind\":${q(e.kind)},\"detail\":${q(e.detail)}}"
    }
    val content = s"{\"sourceSha\":${q(sys.props("audit.source"))},\"roots\":${json(requestedRoots.toVector.sorted.map(q))},\"inventory\":$inventory,\"nominals\":$nominals,\"members\":${json(memberInventory)},\"edges\":${json(renderedEdges)},\"unresolved\":${json(unresolved.toVector.sorted.map(q))},\"external\":${json(external.toVector.sorted.map(q))}}"
    java.nio.file.Files.writeString(java.nio.file.Path.of(out), content + "\n")

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "usage: StableApiAudit ROOTS OUTPUT TASTY...")
    requestedRoots = java.nio.file.Files.readAllLines(java.nio.file.Path.of(args(0))).toArray.toVector.map(_.toString.trim).filter(s => s.nonEmpty && !s.startsWith("#")).toSet
    System.setProperty("audit.output", args(1))
    TastyInspector.inspectTastyFiles(args.drop(2).toList)(this)
