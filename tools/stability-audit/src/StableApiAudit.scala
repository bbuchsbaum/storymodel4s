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
  private var requestedRoots = Set.empty[String]

  private def q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private def json(values: Iterable[String]): String = values.mkString("[", ",", "]")
  private def id(using Quotes)(symbol: quotes.reflect.Symbol): String =
    symbol.fullName.stripSuffix("$").replace("$.", ".")

  def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
    import quotes.reflect.*
    val declaration = mutable.LinkedHashMap.empty[String, Symbol]
    val edges = mutable.LinkedHashSet.empty[Edge]
    val unresolved = mutable.LinkedHashSet.empty[String]
    val external = mutable.LinkedHashSet.empty[String]
    object collect extends TreeTraverser:
      override def traverseTree(tree: Tree)(owner: Symbol): Unit =
        tree match
          case d: Definition if d.symbol != Symbol.noSymbol && id(d.symbol).startsWith("storymodel4s.") =>
            declaration.getOrElseUpdate(id(d.symbol), d.symbol)
          case _ => ()
        super.traverseTree(tree)(owner)
    tastys.foreach(t => collect.traverseTree(t.ast)(Symbol.spliceOwner))
    def nested(parent: Symbol, name: String): Option[Symbol] =
      val scopes = Vector(parent, parent.moduleClass, parent.companionModule, parent.companionClass)
      scopes.iterator
        .flatMap(s => s.declaredTypes ++ s.methodMembers)
        .find(s => s.name == name || id(s).endsWith("." + name))
    def resolve(r: String): Option[Symbol] =
      declaration.get(r).orElse(declaration.collectFirst { case (n, s) if n == r + ".type" => s }).orElse {
        declaration.keys.toVector.sortBy(-_.length).collectFirst {
          case prefix if r.startsWith(prefix + ".") =>
            r.stripPrefix(prefix + ".").split("\\.").foldLeft(Option(declaration(prefix))) {
              case (Some(owner), part) => nested(owner, part)
              case (None, _)           => None
            }
        }.flatten
      }
    val roots = requestedRoots.flatMap { r =>
      resolve(r) match
        case Some(s) => Some(s)
        case None => unresolved += s"missing-root:$r"; None
    }
    val seen = mutable.HashSet.empty[String]
    def public(symbol: Symbol): Boolean =
      !symbol.flags.is(Flags.Private) && !symbol.flags.is(Flags.PrivateLocal)
    def visit(symbol: Symbol): Unit =
      val name = id(symbol)
      if seen.add(name) then
        typeEdge(symbol, symbol.info, "signature")
        symbol.tree match
          case d: ClassDef =>
            d.parents.collect { case p: TypeTree => p }.foreach(p => typeEdge(symbol, p.tpe, "parent"))
            memberEdges(symbol)
          case d: DefDef => typeEdge(symbol, d.returnTpt.tpe, "return")
          case d: ValDef => typeEdge(symbol, d.tpt.tpe, "accessor")
          case _ => ()
    def typeEdge(from: Symbol, t: TypeRepr, kind: String): Unit =
      val normalized = t.dealias
      val s = normalized.typeSymbol
      if s != Symbol.noSymbol then
        val target = id(s)
        if target.startsWith("storymodel4s.") then
          edges += Edge(id(from), target, kind, normalized.show)
          visit(s)
        else if target.nonEmpty && !target.startsWith("scala.") && !target.startsWith("java.") then external += target
      normalized.typeArgs.foreach(typeEdge(from, _, kind + ":argument"))
    def memberEdges(owner: Symbol): Unit =
      val members = (owner.methodMembers ++ owner.fieldMembers).distinct
      members.filter(public).foreach { m =>
        typeEdge(owner, m.info, "member")
        if m.flags.is(Flags.Protected) then edges += Edge(id(owner), id(m), "protected-member", m.name)
      }
    roots.foreach(visit)
    val out = sys.props("audit.output")
    val inventory = seen.toVector.sorted.map(q).mkString("[", ",", "]")
    val renderedEdges = edges.toVector.filter(e => seen(e.from)).sortBy(e => (e.from, e.to, e.kind)).map { e =>
      s"{\"from\":${q(e.from)},\"to\":${q(e.to)},\"kind\":${q(e.kind)},\"detail\":${q(e.detail)}}"
    }
    val content = s"{\"sourceSha\":\"2c4b74f9ef069e1bc1f6678f8dababe2421ca929\",\"roots\":${json(requestedRoots.toVector.sorted.map(q))},\"inventory\":$inventory,\"edges\":${json(renderedEdges)},\"unresolved\":${json(unresolved.toVector.sorted.map(q))},\"external\":${json(external.toVector.sorted.map(q))}}"
    java.nio.file.Files.writeString(java.nio.file.Path.of(out), content + "\n")

  def main(args: Array[String]): Unit =
    require(args.length >= 2, "usage: StableApiAudit ROOTS OUTPUT TASTY...")
    requestedRoots = java.nio.file.Files.readAllLines(java.nio.file.Path.of(args(0))).toArray.toVector.map(_.toString.trim).filter(s => s.nonEmpty && !s.startsWith("#")).toSet
    System.setProperty("audit.output", args(1))
    TastyInspector.inspectTastyFiles(args.drop(2).toList)(this)
