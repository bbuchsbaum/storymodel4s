package storymodel4s.story

import storymodel4s.core.*

enum Severity:
  case Error, Warning

/** One structural-law failure, addressed by law name and component path. */
final case class Violation(law: String, severity: Severity, path: String, reason: String)

/** Which severities block promotion to `Validated`. */
final case class ValidationPolicy(blocking: Set[Severity])

object ValidationPolicy:
  /** Errors block; warnings are reported. */
  val default: ValidationPolicy = ValidationPolicy(Set(Severity.Error))

  /** Errors and warnings both block. */
  val strict: ValidationPolicy = ValidationPolicy(Set(Severity.Error, Severity.Warning))

final case class ValidationReport(violations: Vector[Violation]):
  def errors: Vector[Violation] = violations.filter(_.severity == Severity.Error)
  def warnings: Vector[Violation] = violations.filter(_.severity == Severity.Warning)
  def isClean: Boolean = violations.isEmpty
  def byLaw: Map[String, Vector[Violation]] = violations.groupBy(_.law)
  def render: String =
    if violations.isEmpty then "no violations"
    else violations.map(v => s"[${v.severity}] ${v.law} @ ${v.path}: ${v.reason}").mkString("\n")

final case class ValidationOutcome(
    report: ValidationReport,
    validated: Option[StoryModel[ModelStatus.Validated]]
)

/** Structural laws of a story model (design record §32.6). Each law has a stable name so gate
  * reports and tests can address it.
  */
object StoryValidator:

  def validate(
      draft: StoryModel[ModelStatus.Draft],
      policy: ValidationPolicy = ValidationPolicy.default
  ): ValidationOutcome =
    val vs = check(draft)
    val report = ValidationReport(vs)
    val blocked = vs.exists(v => policy.blocking.contains(v.severity))
    ValidationOutcome(report, if blocked then None else Some(draft.withStatus))

  /** All violations of all laws, deterministic order. */
  def check(m: StoryModel[?]): Vector[Violation] =
    val g = m.graph
    val h = m.hierarchy
    val out = Vector.newBuilder[Violation]
    def err(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Error, path, reason)
    def warn(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Warning, path, reason)

    val textLen = m.source.canonicalText.length

    // ids: map keys agree with node ids
    g.entities.foreach((k, e) =>
      if k != e.id then err("ids.key-consistency", s"entities/${k.value}", "key differs from id")
    )
    g.situations.foreach((k, s) =>
      if k != s.id then err("ids.key-consistency", s"situations/${k.value}", "key differs from id")
    )
    g.segments.foreach((k, s) =>
      if k != s.id then err("ids.key-consistency", s"segments/${k.value}", "key differs from id")
    )
    g.contexts.foreach((k, c) =>
      if k != c.id then err("ids.key-consistency", s"contexts/${k.value}", "key differs from id")
    )

    // claims
    val allClaims = m.claims
    allClaims
      .groupBy(_.id)
      .foreach((id, cs) =>
        if cs.size > 1 then
          err("claims.unique-ids", s"claims/${id.value}", s"${cs.size} claims share id")
      )
    allClaims.foreach { c =>
      ClaimMeta
        .validated(c)
        .left
        .foreach(e => err("claims.explicit-has-spans", s"claims/${c.id.value}", e.message))
      c.evidence.toVector.flatMap(_.spans).foreach { ss =>
        ss.spans.toVector.foreach { sp =>
          if sp.endExclusive > textLen then
            err("claims.spans-in-text", s"claims/${c.id.value}", s"span $sp exceeds text $textLen")
        }
      }
    }
    // node supports within text
    g.situations.values.foreach(s =>
      if s.support.minSpan.endExclusive > textLen then
        err("support.in-text", s"situations/${s.id.value}", "support exceeds text")
    )
    g.entities.values.foreach(e =>
      if e.support.minSpan.endExclusive > textLen then
        err("support.in-text", s"entities/${e.id.value}", "support exceeds text")
    )
    g.segments.values.foreach(s =>
      if s.support.minSpan.endExclusive > textLen then
        err("support.in-text", s"segments/${s.id.value}", "support exceeds text")
    )

    // mentions map to one canonical node
    g.entities.values.toVector
      .flatMap(e => e.mentions.toVector.map(_ -> e.id))
      .groupBy(_._1)
      .foreach((mid, es) =>
        if es.size > 1 then
          err("entity.mentions-unique", s"mentions/${mid.value}", "mention in several entities")
      )
    g.situations.values.toVector
      .flatMap(s => s.mentions.toVector.map(_ -> s.id))
      .groupBy(_._1)
      .foreach((mid, ss) =>
        if ss.size > 1 then
          err(
            "situation.mentions-unique",
            s"mentions/${mid.value}",
            "mention in several situations"
          )
      )

    // endpoints
    def sit(law: String, path: String, id: SituationId): Boolean =
      val ok = g.situations.contains(id)
      if !ok then err(law, path, s"missing situation ${id.value}")
      ok
    def ent(law: String, path: String, id: EntityId): Boolean =
      val ok = g.entities.contains(id)
      if !ok then err(law, path, s"missing entity ${id.value}")
      ok
    def seg(law: String, path: String, id: SegmentId): Boolean =
      val ok = g.segments.contains(id)
      if !ok then err(law, path, s"missing segment ${id.value}")
      ok
    def ctx(law: String, path: String, id: ContextId): Boolean =
      val ok = g.contexts.contains(id)
      if !ok then err(law, path, s"missing context ${id.value}")
      ok

    g.relations.participants.zipWithIndex.foreach { (p, i) =>
      val path = s"participants/$i"
      sit("endpoints.participant", path, p.situation)
      ent("endpoints.participant", path, p.entity)
    }
    g.relations.temporal.zipWithIndex.foreach { (t, i) =>
      val path = s"temporal/$i"
      val okFrom = sit("endpoints.temporal", path, t.from)
      val okTo = sit("endpoints.temporal", path, t.to)
      val okCtx = ctx("endpoints.temporal", path, t.context)
      if t.from == t.to then err("temporal.no-self", path, "self relation")
      if !t.relation.isCanonical then
        err(
          "temporal.canonical-relation",
          path,
          s"${t.relation} is a converse form; store ${t.relation.converse} with swapped endpoints"
        )
      if okFrom && okTo && okCtx then
        val ca = g.situations(t.from).context
        val cb = g.situations(t.to).context
        if !(g.contextWithin(t.context, ca) && g.contextWithin(t.context, cb)) then
          err(
            "temporal.context-scope",
            path,
            s"edge context ${t.context.value} is not within both endpoint contexts (${ca.value}, ${cb.value})"
          )
    }
    g.relations.causal.zipWithIndex.foreach { (c, i) =>
      val path = s"causal/$i"
      val a = sit("endpoints.causal", path, c.cause)
      val b = sit("endpoints.causal", path, c.effect)
      if c.cause == c.effect then err("causal.no-self", path, "self relation")
      if a && b then
        val ca = g.situations(c.cause).context
        val cb = g.situations(c.effect).context
        if ca != cb && c.meta.status == EpistemicStatus.SurfaceExplicit then
          warn("causal.cross-context-explicit", path, "explicit causal edge across contexts")
    }
    g.relations.goals.zipWithIndex.foreach { (e, i) =>
      val path = s"goals/$i"
      sit("endpoints.goal", path, e.from)
      sit("endpoints.goal", path, e.to)
    }
    g.relations.stateChanges.zipWithIndex.foreach { (e, i) =>
      val path = s"stateChanges/$i"
      sit("endpoints.statechange", path, e.event)
      if sit("endpoints.statechange", path, e.state) && !g.situations(e.state).isState then
        err("statechange.target-is-state", path, s"${e.state.value} is not a state")
    }
    g.relations.references.zipWithIndex.foreach { (e, i) =>
      val path = s"references/$i"
      sit("endpoints.reference", path, e.from)
      sit("endpoints.reference", path, e.to)
      if e.from == e.to then err("reference.no-self", path, "self reference")
    }
    g.entities.values.foreach { e =>
      e.attributes.zipWithIndex.foreach { (a, i) =>
        ctx(
          "endpoints.entity-attribute-context",
          s"entities/${e.id.value}/attributes/$i",
          a.context
        )
      }
    }
    m.descriptors.zipWithIndex.foreach { (d, i) =>
      seg("descriptor.target-exists", s"descriptors/$i", d.target)
    }

    // contexts
    g.situations.values.foreach(s =>
      ctx("situation.context-exists", s"situations/${s.id.value}", s.context)
    )
    g.contexts.values.foreach { c =>
      c.parent.foreach(p => ctx("context.parent-exists", s"contexts/${c.id.value}", p))
      c.kind.holderEntity.foreach(hld =>
        ent("context.holder-exists", s"contexts/${c.id.value}", hld)
      )
      if c.parent.isEmpty && c.kind != ContextKind.NarratedWorld then
        err(
          "context.root-is-narrated-world",
          s"contexts/${c.id.value}",
          s"root context has kind ${c.kind.label}"
        )
      if c.parent.nonEmpty && c.kind == ContextKind.NarratedWorld then
        err(
          "context.narrated-world-is-root",
          s"contexts/${c.id.value}",
          "NarratedWorld must be the root"
        )
    }
    g.contextRoots match
      case Vector(_) => ()
      case roots     => err("context.single-root", "contexts", s"${roots.size} root contexts")
    g.contexts.keys.foreach { id =>
      val anc = g.contextAncestors(id)
      val last = anc.lastOption.orElse(Some(id))
      if anc.contains(id) || last.flatMap(g.contexts.get).exists(_.parent.nonEmpty) then
        err("context.acyclic", s"contexts/${id.value}", "context parent chain is cyclic")
    }

    // hierarchy
    h.containment.zipWithIndex.foreach { (e, i) =>
      val path = s"containment/$i"
      e.member match
        case NarrativeMember.Situation(id) => sit("endpoints.containment", path, id)
        case NarrativeMember.Segment(id)   => seg("endpoints.containment", path, id)
      seg("endpoints.containment", path, e.parent)
      if e.weight < 0.0 || e.weight > 1.0 || e.weight.isNaN then
        err("containment.weight-in-unit", path, s"weight ${e.weight}")
      if e.member == NarrativeMember.Segment(e.parent) then
        err("containment.acyclic", path, "segment contains itself")
    }
    h.primary
      .groupBy(_.member)
      .foreach((mbr, es) =>
        if es.size > 1 then
          err(
            "hierarchy.single-primary-parent",
            s"containment/${mbr.render}",
            s"${es.size} primary parents"
          )
      )
    // cycles in primary containment among segments
    g.segments.keys.foreach { s =>
      val anc = h.ancestors(NarrativeMember.Segment(s))
      if anc.contains(s) then
        err("containment.acyclic", s"segments/${s.value}", "cyclic containment")
      else
        anc.lastOption.foreach { top =>
          if h.primaryParent.contains(NarrativeMember.Segment(top)) then
            err("containment.acyclic", s"segments/${s.value}", "cyclic containment above")
        }
    }
    val roots = h.primaryRoots(g.segments.keys)
    if g.segments.nonEmpty then
      roots match
        case Vector(r) =>
          if g.segments(r).kind != SegmentKind.Story then
            warn(
              "hierarchy.root-kind-story",
              s"segments/${r.value}",
              s"primary root has kind ${g.segments(r).kind}"
            )
        case rs =>
          err(
            "hierarchy.single-primary-root",
            "segments",
            s"${rs.size} primary roots: ${rs.map(_.value).mkString(",")}"
          )
    else if g.situations.nonEmpty then
      err("hierarchy.single-primary-root", "segments", "no segments but situations exist")
    val rootOpt = roots.headOption.filter(_ => roots.size == 1)
    g.situations.keys.foreach { s =>
      val anc = h.ancestors(NarrativeMember.Situation(s))
      if anc.isEmpty || rootOpt.exists(r => anc.last != r) then
        err(
          "hierarchy.situation-root-reachable",
          s"situations/${s.value}",
          "not under the primary root"
        )
    }
    g.segments.values.foreach { s =>
      if h.childrenOf.getOrElse(s.id, Vector.empty).isEmpty then
        err("hierarchy.no-empty-primary-segment", s"segments/${s.id.value}", "no primary members")
      if s.level < 1 then
        err("hierarchy.level-consistent", s"segments/${s.id.value}", "segment level < 1")
    }
    h.primary.zipWithIndex.foreach { (e, i) =>
      g.segments.get(e.parent).foreach { p =>
        val childLevel = h.levelOf(e.member, g.segments)
        if childLevel >= p.level then
          err(
            "hierarchy.level-consistent",
            s"containment/$i",
            s"member level $childLevel not below parent level ${p.level}"
          )
      }
    }
    h.boundaryBeliefs.zipWithIndex.foreach { (b, i) =>
      if !m.atlas.byId.contains(b.afterUnit) then
        err("boundary.unit-exists", s"boundaryBeliefs/$i", s"unknown unit ${b.afterUnit.value}")
      if b.level < 1 then err("boundary.level", s"boundaryBeliefs/$i", s"level ${b.level} < 1")
    }

    // temporal consistency per context (strict precedence closure must be acyclic)
    val temporalByCtx = g.relations.temporal
      .filter(t => g.situations.contains(t.from) && g.situations.contains(t.to))
      .groupBy(_.context)
    temporalByCtx.foreach { (c, edges) =>
      val strict = edges.filter(_.relation.isStrictPrecedence)
      val equal = edges.filter(_.relation == TemporalRelation.Equal)
      // merge Equal endpoints for the strict cycle check
      val rep = unionFind(equal.map(e => (e.from, e.to)))
      val adj = strict.groupMap(e => rep(e.from))(e => rep(e.to))
      findCycle(adj).foreach(cycle =>
        err(
          "temporal.strict-acyclic",
          s"contexts/${c.value}",
          s"strict precedence cycle: ${cycle.map(_.value).mkString(" -> ")}"
        )
      )
      val containsEdges = edges
        .map(e =>
          if e.relation == TemporalRelation.During then (e.to, e.from)
          else if e.relation == TemporalRelation.Contains then (e.from, e.to)
          else null
        )
        .filter(_ != null)
      findCycle(containsEdges.groupMap(_._1)(_._2)).foreach(cycle =>
        err(
          "temporal.containment-acyclic",
          s"contexts/${c.value}",
          s"interval containment cycle: ${cycle.map(_.value).mkString(" -> ")}"
        )
      )
      // strict edge contradicting an Equal
      val strictPairs = strict.map(e => Set(e.from, e.to)).toSet
      equal.foreach(e =>
        if strictPairs.contains(Set(e.from, e.to)) then
          err(
            "temporal.equal-consistent",
            s"contexts/${c.value}",
            s"${e.from.value} Equal ${e.to.value} but also strictly ordered"
          )
      )
    }

    // features
    m.featureRefs.zipWithIndex.foreach { (f, i) =>
      val path = s"featureRefs/$i"
      if !m.featureSpaces.contains(f.space) then
        err("feature.space-exists", path, s"unknown space ${f.space.value}")
      if f.row < 0 then err("feature.row-nonnegative", path, s"row ${f.row}")
      f.target match
        case FeatureTarget.Situation(id) => sit("feature.target-exists", path, id)
        case FeatureTarget.Segment(id)   => seg("feature.target-exists", path, id)
        case FeatureTarget.Entity(id)    => ent("feature.target-exists", path, id)
    }
    m.featureSpaces.values.foreach(s =>
      if s.dimension <= 0 then
        err(
          "feature.dimension-positive",
          s"featureSpaces/${s.id.value}",
          s"dimension ${s.dimension}"
        )
    )
    m.sensoryProfiles.keys.foreach(id =>
      sit("sensory.target-exists", s"sensoryProfiles/${id.value}", id)
    )

    // trajectory steps reference situations in discourse order
    m.trajectory.steps.zipWithIndex.foreach { (s, i) =>
      sit("trajectory.endpoints", s"trajectory/$i", s.from)
      sit("trajectory.endpoints", s"trajectory/$i", s.to)
    }

    out.result().sortBy(v => (v.law, v.path, v.reason))

  /** Representative map for equality classes over a set of pairs. */
  private def unionFind(pairs: Vector[(SituationId, SituationId)]): SituationId => SituationId =
    var parent = Map.empty[SituationId, SituationId]
    def find(x: SituationId): SituationId =
      parent.get(x) match
        case None              => x
        case Some(p) if p == x => x
        case Some(p)           =>
          val r = find(p)
          parent = parent.updated(x, r)
          r
    pairs.foreach { (a, b) =>
      val ra = find(a)
      val rb = find(b)
      if ra != rb then parent = parent.updated(ra, rb)
    }
    find

  /** First cycle found by iterative DFS over a sparse adjacency; `None` when acyclic. */
  private[story] def findCycle(
      adj: Map[SituationId, Vector[SituationId]]
  ): Option[Vector[SituationId]] =
    var white = adj.keys.toSet ++ adj.values.flatten
    var grey = Set.empty[SituationId]
    var black = Set.empty[SituationId]
    var result: Option[Vector[SituationId]] = None
    def visit(start: SituationId): Unit =
      // explicit stack of (node, remaining neighbours)
      var stack = List((start, adj.getOrElse(start, Vector.empty).toList))
      var path = Vector(start)
      grey += start
      white -= start
      while stack.nonEmpty && result.isEmpty do
        val (node, rest) = stack.head
        rest match
          case Nil =>
            stack = stack.tail
            grey -= node
            black += node
            path = path.dropRight(1)
          case n :: tl =>
            stack = (node, tl) :: stack.tail
            if grey.contains(n) then result = Some(path.dropWhile(_ != n) :+ n)
            else if !black.contains(n) then
              grey += n
              white -= n
              path = path :+ n
              stack = (n, adj.getOrElse(n, Vector.empty).toList) :: stack
    while white.nonEmpty && result.isEmpty do visit(white.toVector.sorted.head)
    result
