package storymodel4s.story

import storymodel4s.core.*

enum Severity:
  case Error, Warning

/** One structural-law failure, addressed by law name and component path, plus the typed address of
  * the offending object when the path names one (ADR 0002 §4). Positional paths (`temporal/3`)
  * resolve to the edge stored at that index; unknown ids and non-object paths (`atlas`) carry no
  * address rather than a fabricated one.
  */
final case class Violation(
    law: String,
    severity: Severity,
    path: String,
    reason: String,
    address: Option[Address] = None
)

object Violation:
  private val ev = Addressable[StoryRef]

  /** Deterministic path -> address resolution against the graph the violation was raised on. */
  def addressOf(path: String, g: NarrativeGraph): Option[Address] =
    val parts = path.split("/", -1).toVector
    def idx(s: String): Option[Int] = s.toIntOption.filter(_ >= 0)
    def edge[E](edges: Vector[E], i: String)(mk: E => StoryRef): Option[Address] =
      idx(i).flatMap(edges.lift).map(e => ev.address(mk(e)))
    parts match
      case "entities" +: id +: _ =>
        EntityId
          .from(id)
          .toOption
          .filter(g.entities.contains)
          .map(e => ev.address(StoryRef.Entity(e)))
      case "situations" +: id +: _ =>
        SituationId
          .from(id)
          .toOption
          .filter(g.situations.contains)
          .map(s => ev.address(StoryRef.Situation(s)))
      case "segments" +: id +: _ =>
        SegmentId
          .from(id)
          .toOption
          .filter(g.segments.contains)
          .map(s => ev.address(StoryRef.Segment(s)))
      case "contexts" +: id +: _ =>
        ContextId
          .from(id)
          .toOption
          .filter(g.contexts.contains)
          .map(c => ev.address(StoryRef.Context(c)))
      case "claims" +: id +: _ =>
        ClaimId.from(id).toOption.map(c => Addressable[CoreRef].address(CoreRef.Claim(c)))
      case Vector("participants", i) =>
        edge(g.relations.participants, i)(p => StoryRef.Participant(p.situation, p.role, p.entity))
      case Vector("entityRelations", i) =>
        edge(g.relations.entityRelations, i)(e => StoryRef.EntityLink(e.from, e.relation, e.to))
      case Vector("temporal", i) =>
        edge(g.relations.temporal, i)(t => StoryRef.Temporal(t.from, t.relation, t.to, t.context))
      case Vector("causal", i) =>
        edge(g.relations.causal, i)(c => StoryRef.Causal(c.cause, c.relation, c.effect))
      case Vector("goals", i) =>
        edge(g.relations.goals, i)(e => StoryRef.Goal(e.from, e.relation, e.to))
      case Vector("stateChanges", i) =>
        edge(g.relations.stateChanges, i)(e => StoryRef.StateChange(e.event, e.change, e.state))
      case Vector("references", i) =>
        edge(g.relations.references, i)(e => StoryRef.Reference(e.from, e.mode, e.to))
      case _ => None

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

/** Validation outcome retaining the admitted text capability. */
final case class TextValidationOutcome(
    report: ValidationReport,
    validated: Option[TextModel[ModelStatus.Validated]]
)

/** Structural laws of a story model (design record §32.6). Each law has a stable name so gate
  * reports and tests can address it. Narrative-consistency rules ([[NarrativeConsistency]]) are
  * included in the report so a validation policy can block on them.
  */
object StoryValidator:

  def validate(
      draft: StoryModel[ModelStatus.Draft],
      policy: ValidationPolicy = ValidationPolicy.default
  ): ValidationOutcome =
    val result = report(draft)
    ValidationOutcome(result, if blocked(result, policy) then None else Some(draft.withStatus))

  def validate(draft: TextModel[ModelStatus.Draft]): TextValidationOutcome =
    validate(draft, ValidationPolicy.default)

  def validate(
      draft: TextModel[ModelStatus.Draft],
      policy: ValidationPolicy
  ): TextValidationOutcome =
    val result = report(draft.model)
    TextValidationOutcome(result, if blocked(result, policy) then None else Some(draft.promoted))

  private def blocked(report: ValidationReport, policy: ValidationPolicy): Boolean =
    report.violations.exists(v => policy.blocking.contains(v.severity))

  private def report(model: StoryModel[?]): ValidationReport =
    val consistency = StoryModel.asText(model) match
      case Some(text) => NarrativeConsistency.check(text)
      case None       => NarrativeConsistency.checkGeneral(model)
    ValidationReport((check(model) ++ consistency).sortBy(v => (v.law, v.path, v.reason)))

  def check(model: TextModel[?]): Vector[Violation] = check(model.model)

  /** All violations of all structural laws, deterministic order. */
  def check(m: StoryModel[?]): Vector[Violation] =
    val g = m.graph
    val h = m.hierarchy
    val out = Vector.newBuilder[Violation]
    def err(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Error, path, reason, Violation.addressOf(path, g))
    def warn(law: String, path: String, reason: String): Unit =
      out += Violation(law, Severity.Warning, path, reason, Violation.addressOf(path, g))

    val text = StoryModel.asText(m)
    val textLen = text.map(_.source.canonicalText.length)
    val surface = m.atlas match
      case a: TextNarrativeAtlas     => Some(a.atlas)
      case a: AnchoredNarrativeAtlas => a.surface.map(_.surface)

    // Canonical text laws require the model's own text witness.
    text.foreach(t =>
      SurfaceAtlas.validated(t.atlas).left.foreach(e => err("atlas.valid", "atlas", e.message))
    )
    def anchorsInBundle(path: String, evidence: Evidence): Unit =
      evidence.anchors.foreach(a =>
        EvidenceSupport
          .of(m.bundle, a.anchors.toVector)
          .left
          .foreach(e => err("evidence.anchors-in-bundle", path, e.message))
      )

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

    // claims: unique ids, spans inside the text. (Surface-explicit-without-spans is
    // unrepresentable: ClaimMeta's constructor enforces it.)
    val allClaims = m.claims
    allClaims
      .groupBy(_.id)
      .foreach((id, cs) =>
        if cs.size > 1 then
          err("claims.unique-ids", s"claims/${id.value}", s"${cs.size} claims share id")
      )
    allClaims.foreach { c =>
      c.evidence.toVector.flatMap(_.spans).foreach { ss =>
        ss.spans.toVector.foreach { sp =>
          textLen.foreach { length =>
            if sp.endExclusive > length then
              err("claims.spans-in-text", s"claims/${c.id.value}", s"span $sp exceeds text $length")
          }
        }
      }
    }
    allClaims.foreach(c =>
      c.evidence.toVector.foreach(e => anchorsInBundle(s"claims/${c.id.value}", e))
    )
    (h.boundaryBeliefs ++ m.trajectory.steps.flatMap(_.boundaryBeliefs)).zipWithIndex.foreach {
      (belief, i) =>
        belief.evidence.toVector.foreach(e => anchorsInBundle(s"boundaryBeliefs/$i", e))
    }
    // resolved values: no duplicate or self alternative
    def alternativesLaw[A](path: String, r: Resolved[A]): Unit =
      val alts = r.alternatives.map(_._1)
      if alts.distinct.size != alts.size then
        err("claims.alternatives-distinct", path, "duplicate alternative")
      if alts.contains(r.value) then
        err("claims.alternatives-distinct", path, "selected value repeated as an alternative")
    g.entities.values.foreach(e => alternativesLaw(s"entities/${e.id.value}/label", e.label))
    g.segments.values.foreach(s =>
      s.summary.stated.foreach(r => alternativesLaw(s"segments/${s.id.value}/summary", r))
    )
    m.trajectory.steps.zipWithIndex.foreach((s, i) =>
      alternativesLaw(s"trajectory/$i/worldTime", s.worldTime)
    )
    m.hypotheses.zipWithIndex.foreach { (hyp, i) =>
      val path = s"hypotheses/$i"
      alternativesLaw(path, hyp.reading)
      if !g.situations.contains(hyp.subject) then
        err("hypothesis.subject-exists", path, s"missing situation ${hyp.subject.value}")
      if hyp.meta.status != EpistemicStatus.Hypothesized then
        err("hypothesis.status", path, s"status ${hyp.meta.status} is not Hypothesized")
      if hyp.reading.alternatives.isEmpty then
        err("hypothesis.has-alternatives", path, "a hypothesis needs at least one rival reading")
    }

    // Every support family is checked, including contexts and circumstances.
    g.supportEntries.foreach { (path, support) =>
      support match
        case TypedSupport.Text(spans) =>
          textLen.foreach { length =>
            if spans.minSpan.endExclusive > length then
              err("support.in-text", path, "support exceeds text")
          }
        case TypedSupport.Anchored(anchors) =>
          EvidenceSupport
            .of(m.bundle, anchors.anchors.toVector)
            .left
            .foreach(e => err("support.anchors-in-bundle", path, e.message))
    }

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
    g.relations.entityRelations.zipWithIndex.foreach { (e, i) =>
      val path = s"entityRelations/$i"
      ent("endpoints.entity-relation", path, e.from)
      ent("endpoints.entity-relation", path, e.to)
      if e.from == e.to then err("entity-relation.no-self", path, "self relation")
    }
    // membership acyclic
    g.entities.keys.foreach { e =>
      if g.groupsOf(e).contains(e) then
        err("entity-relation.membership-acyclic", s"entities/${e.value}", "cyclic membership")
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
        // An edge may be scoped at or below both endpoints' contexts. An edge scoped to a child
        // context (a belief, a speech) between two narrated-world situations is legal — it says
        // how that context orders them — but never contributes to narrated-world chronology.
        if !(g.contextWithin(t.context, ca) && g.contextWithin(t.context, cb)) then
          err(
            "temporal.context-scope",
            path,
            s"edge context ${t.context.value} is not within both endpoint contexts (${ca.value}, ${cb.value})"
          )
    }
    // duplicate and pairwise-inconsistent temporal edges (per context)
    g.relations.temporal
      .groupBy(t => (t.from, t.relation, t.to, t.context))
      .foreach((k, es) =>
        if es.size > 1 then
          err(
            "temporal.no-duplicate",
            s"temporal/${k._1.value}-${k._3.value}",
            s"${es.size} identical ${k._2} edges in ${k._4.value}"
          )
      )
    g.relations.temporal
      .filter(_.relation.isCanonical)
      .groupBy(t => (Set(t.from, t.to), t.context))
      .foreach { (k, es) =>
        val path = s"temporal/${k._1.map(_.value).toVector.sorted.mkString("-")}"
        val rels = es.map(_.relation).toSet
        val definite = rels - TemporalRelation.Unclear
        if rels.contains(TemporalRelation.Unclear) && definite.nonEmpty then
          err("temporal.pair-consistent", path, s"Unclear together with ${definite.mkString(",")}")
        // the same asymmetric relation asserted in both orientations
        val asymmetric = Set(
          TemporalRelation.Before,
          TemporalRelation.Meets,
          TemporalRelation.Overlaps,
          TemporalRelation.Starts,
          TemporalRelation.Finishes
        )
        asymmetric.foreach { r =>
          val oriented = es.filter(_.relation == r).map(e => (e.from, e.to)).toSet
          if oriented.size > 1 then
            err("temporal.pair-consistent", path, s"$r asserted in both orientations")
        }
        // During/Contains are converses: both orientations must agree
        val d = es.filter(_.relation == TemporalRelation.During).map(e => (e.from, e.to)).toSet
        val c = es.filter(_.relation == TemporalRelation.Contains).map(e => (e.to, e.from)).toSet
        if (d ++ c).size > 1 then
          err("temporal.pair-consistent", path, "During/Contains asserted in both orientations")
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
      val okMember = e.member match
        case NarrativeMember.Situation(id) => sit("endpoints.containment", path, id)
        case NarrativeMember.Segment(id)   => seg("endpoints.containment", path, id)
      val okParent = seg("endpoints.containment", path, e.parent)
      if e.weight < 0.0 || e.weight > 1.0 || e.weight.isNaN then
        err("containment.weight-in-unit", path, s"weight ${e.weight}")
      if e.member == NarrativeMember.Segment(e.parent) then
        err("containment.acyclic", path, "segment contains itself")
      if okMember && okParent && e.isPrimary then
        val memberSupport = e.member match
          case NarrativeMember.Situation(id) => g.situations(id).support
          case NarrativeMember.Segment(id)   => g.segments(id).support
        val member = m.projectionOf(memberSupport)
        val parent = m.projectionOf(g.segments(e.parent).support)
        (member, parent) match
          case (PrimaryProjection.TextSpans(_, child), PrimaryProjection.TextSpans(_, enclosing)) =>
            val memberSpan = child.minSpan
            val parentSpan = enclosing.minSpan
            if !parentSpan.contains(memberSpan) then
              err(
                "hierarchy.member-within-parent",
                path,
                s"member span $memberSpan escapes parent span $parentSpan"
              )
          case (
                PrimaryProjection.Playback(axis, child),
                PrimaryProjection.Playback(parentAxis, enclosing)
              ) =>
            if axis != parentAxis || !child.intervals.toVector.forall(c =>
                enclosing.intervals.toVector.exists(p =>
                  p.start <= c.start && p.endExclusive >= c.endExclusive
                )
              )
            then
              err(
                "hierarchy.member-within-parent",
                path,
                "member playback support escapes parent interval union"
              )
          case _ =>
            err("hierarchy.member-within-parent", path, "incompatible primary projection kinds")
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
      if !surface.exists(_.byId.contains(b.afterUnit)) then
        err("boundary.unit-exists", s"boundaryBeliefs/$i", s"unknown unit ${b.afterUnit.value}")
      if b.level < 1 then err("boundary.level", s"boundaryBeliefs/$i", s"level ${b.level} < 1")
    }

    // temporal consistency per context
    val temporalByCtx = g.relations.temporal
      .filter(t =>
        g.situations.contains(t.from) && g.situations.contains(t.to) && t.relation.isCanonical
      )
      .groupBy(_.context)
    temporalByCtx.foreach { (c, edges) =>
      var caught = false
      val strict = edges.filter(_.relation.isStrictPrecedence)
      val equal = edges.filter(_.relation == TemporalRelation.Equal)
      // merge Equal endpoints for the strict cycle check
      val rep = unionFind(equal.map(e => (e.from, e.to)))
      val adj = strict.groupMap(e => rep(e.from))(e => rep(e.to))
      findCycle(adj).foreach { cycle =>
        caught = true
        err(
          "temporal.strict-acyclic",
          s"contexts/${c.value}",
          s"strict precedence cycle: ${cycle.map(_.value).mkString(" -> ")}"
        )
      }
      val containsEdges = edges.flatMap(e =>
        if e.relation == TemporalRelation.During then Some((e.to, e.from))
        else if e.relation == TemporalRelation.Contains then Some((e.from, e.to))
        else None
      )
      findCycle(containsEdges.groupMap(_._1)(_._2)).foreach { cycle =>
        caught = true
        err(
          "temporal.containment-acyclic",
          s"contexts/${c.value}",
          s"interval containment cycle: ${cycle.map(_.value).mkString(" -> ")}"
        )
      }
      // strict edge contradicting an Equal
      val strictPairs = strict.map(e => Set(e.from, e.to)).toSet
      equal.foreach(e =>
        if strictPairs.contains(Set(e.from, e.to)) then
          caught = true
          err(
            "temporal.equal-consistent",
            s"contexts/${c.value}",
            s"${e.from.value} Equal ${e.to.value} but also strictly ordered"
          )
      )
      // full interval-endpoint consistency (Allen composition through point constraints)
      if !caught then
        IntervalConsistency
          .cycle(edges)
          .foreach(cycle =>
            err(
              "temporal.interval-consistent",
              s"contexts/${c.value}",
              s"contradictory interval relations: ${cycle.mkString(" < ")}"
            )
          )
    }

    // features: declared spaces, sidecar manifests, and row references
    m.featureRefs.zipWithIndex.foreach { (f, i) =>
      val path = s"featureRefs/$i"
      if !m.featureSpaces.contains(f.space) then
        err("feature.space-exists", path, s"unknown space ${f.space.value}")
      if f.row < 0 then err("feature.row-nonnegative", path, s"row ${f.row}")
      m.sidecars.get(f.space).foreach { manifest =>
        if f.row >= manifest.rowCount then
          err("feature.row-in-range", path, s"row ${f.row} outside [0, ${manifest.rowCount})")
      }
      f.target match
        case FeatureTarget.Situation(id) => sit("feature.target-exists", path, id)
        case FeatureTarget.Segment(id)   => seg("feature.target-exists", path, id)
        case FeatureTarget.Sentence(u)   =>
          if !surface.exists(_.byId.contains(u)) then
            err("feature.target-exists", path, s"unknown surface unit ${u.value}")
        case FeatureTarget.Boundary(u) =>
          if !surface.exists(_.byId.contains(u)) then
            err("feature.target-exists", path, s"unknown surface unit ${u.value}")
        case FeatureTarget.SurfaceUnit(u) =>
          if !surface.exists(_.byId.contains(u)) then
            err("feature.target-exists", path, s"unknown surface unit ${u.value}")
        case FeatureTarget.Token(_) | FeatureTarget.Window(_) | FeatureTarget.Turn(_) => ()
    }
    m.sidecars.foreach { (id, manifest) =>
      val path = s"sidecars/${id.value}"
      if manifest.space != id then err("feature.sidecar-space", path, "manifest space mismatch")
      m.featureSpaces.get(id) match
        case None        => err("feature.space-exists", path, s"undeclared space ${id.value}")
        case Some(space) =>
          val expected = space.valueSchema match
            case FeatureValueSchema.Vector(d)       => Some(d)
            case FeatureValueSchema.Scalar(_)       => Some(1)
            case FeatureValueSchema.Categorical(_)  => None
            case FeatureValueSchema.Distribution(s) => Some(s.size)
          expected.foreach(d =>
            if manifest.dimension != d then
              err(
                "feature.dimension-consistent",
                path,
                s"sidecar dimension ${manifest.dimension} differs from declared $d"
              )
          )
      SidecarManifest
        .validated(manifest)
        .left
        .foreach(e => err("feature.sidecar-valid", path, e.toString))
    }
    m.sensoryProfiles.keys.foreach(id =>
      sit("sensory.target-exists", s"sensoryProfiles/${id.value}", id)
    )

    // trajectory: exactly the adjacent pairs of discourse order, in order; turnover in [0,1]
    val expectedSteps = m.discourseOrder.zip(m.discourseOrder.drop(1))
    val actualSteps = m.trajectory.steps.map(s => (s.from, s.to))
    if actualSteps != expectedSteps then
      err(
        "trajectory.complete",
        "trajectory",
        s"${actualSteps.size} steps do not match the ${expectedSteps.size} adjacent discourse pairs"
      )
    m.trajectory.steps.zipWithIndex.foreach { (s, i) =>
      sit("trajectory.endpoints", s"trajectory/$i", s.from)
      sit("trajectory.endpoints", s"trajectory/$i", s.to)
      s.entityTurnover.toOption.foreach { v =>
        if v.isNaN || v < 0.0 || v > 1.0 then
          err("trajectory.turnover-in-unit", s"trajectory/$i", s"turnover $v")
      }
      s.worldTimeContext.foreach(c => ctx("trajectory.context-exists", s"trajectory/$i", c))
    }

    out.result().sortBy(v => (v.law, v.path, v.reason))

  /** Representative map for equality classes over a set of pairs. */
  private[story] def unionFind[A](pairs: Vector[(A, A)]): A => A =
    var parent = Map.empty[A, A]
    def find(x: A): A =
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
  private[story] def findCycle[A: Ordering](adj: Map[A, Vector[A]]): Option[Vector[A]] =
    var white = adj.keys.toSet ++ adj.values.flatten
    var grey = Set.empty[A]
    var black = Set.empty[A]
    var result: Option[Vector[A]] = None
    def visit(start: A): Unit =
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

/** Allen relations as constraints on interval endpoints. Each interval `X` contributes points
  * `X.s < X.e`; each relation adds point orderings or equalities; a cycle in the resulting strict
  * order is a contradiction that no single-relation check can see (e.g. `A Before B`, `C During B`,
  * `C Before A`).
  */
object IntervalConsistency:
  final case class Point(interval: SituationId, end: Boolean):
    def render: String = s"${interval.value}.${if end then "e" else "s"}"
  object Point:
    given Ordering[Point] = Ordering.by(p => (p.interval, p.end))

  private def s(x: SituationId) = Point(x, false)
  private def e(x: SituationId) = Point(x, true)

  /** `(strict, equal)` point constraints implied by an edge. */
  def constraints(t: TemporalEdge): (Vector[(Point, Point)], Vector[(Point, Point)]) =
    val (a, b) = (t.from, t.to)
    t.relation match
      case TemporalRelation.Before   => (Vector((e(a), s(b))), Vector.empty)
      case TemporalRelation.Meets    => (Vector.empty, Vector((e(a), s(b))))
      case TemporalRelation.Overlaps =>
        (Vector((s(a), s(b)), (s(b), e(a)), (e(a), e(b))), Vector.empty)
      case TemporalRelation.During   => (Vector((s(b), s(a)), (e(a), e(b))), Vector.empty)
      case TemporalRelation.Contains => (Vector((s(a), s(b)), (e(b), e(a))), Vector.empty)
      case TemporalRelation.Starts   => (Vector((e(a), e(b))), Vector((s(a), s(b))))
      case TemporalRelation.Finishes => (Vector((s(b), s(a))), Vector((e(a), e(b))))
      case TemporalRelation.Equal    => (Vector.empty, Vector((s(a), s(b)), (e(a), e(b))))
      case TemporalRelation.Unclear  => (Vector.empty, Vector.empty)
      case other => constraints(t.copy(relation = other.converse, from = b, to = a))

  /** A cycle of point names when the edges are jointly unsatisfiable; `None` when consistent. */
  def cycle(edges: Vector[TemporalEdge]): Option[Vector[String]] =
    val intervals = edges.flatMap(t => Vector(t.from, t.to)).distinct
    val (strictAll, equalAll) = edges.map(constraints).unzip
    val strict = intervals.map(x => (s(x), e(x))) ++ strictAll.flatten
    val rep = StoryValidator.unionFind(equalAll.flatten)
    val adj = strict.groupMap(p => rep(p._1))(p => rep(p._2))
    StoryValidator.findCycle(adj).map(_.map(_.render))
