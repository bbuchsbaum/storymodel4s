package storymodel4s.story

import storymodel4s.core.*

/** Deterministic human-readable and DOT renderings of a story model. Output depends only on the
  * model contents, never on map iteration order.
  */
object Renderer:

  def text(m: StoryModel[?]): String =
    val g = m.graph
    val h = m.hierarchy
    val sb = new StringBuilder
    sb.append(s"# ${m.source.title.getOrElse(m.source.id.value)} (${m.source.id.value})\n")
    sb.append(
      s"schema ${m.schemaVersion}; ${g.entities.size} entities, ${g.situations.size} situations, "
    )
    sb.append(s"${g.segments.size} segments, ${g.contexts.size} contexts\n\n")

    sb.append("## Entities\n")
    g.entities.values.toVector.sortBy(_.id).foreach { e =>
      sb.append(s"- ${e.id.value}: ${e.label.value} [${e.entityType}]")
      if e.attributes.nonEmpty then
        sb.append(" attrs: ")
        sb.append(
          e.attributes
            .sortBy(a => (a.context.value, a.key))
            .map(a => s"${a.key}=${a.value}@${ctxLabel(g, a.context)}")
            .mkString(", ")
        )
      sb.append('\n')
    }

    sb.append("\n## Situations (discourse order)\n")
    g.discourseOrder.zipWithIndex.foreach { (id, i) =>
      val s = g.situations(id)
      val parts = g.participantsOf
        .getOrElse(id, Vector.empty)
        .sortBy((r, e) => (r.render, e.value))
        .map((r, e) => s"${r.render}=${g.entities.get(e).map(_.label.value).getOrElse(e.value)}")
        .mkString(", ")
      sb.append(s"$i. ${id.value} ${s.kindName} ${s.predicate.lemma}: ${s.description}")
      sb.append(s" | ctx=${ctxLabel(g, s.context)} pol=${s.polarity} mod=${s.modality}")
      sb.append(s" status=${s.meta.status}")
      if parts.nonEmpty then sb.append(s" | $parts")
      sb.append(s" | ${s.support.minSpan}\n")
    }

    sb.append("\n## Contexts\n")
    def renderCtx(id: ContextId, depth: Int): Unit =
      g.contexts.get(id).foreach { c =>
        sb.append("  " * depth).append(s"- ${c.id.value}: ${c.kind.label}\n")
        g.contextChildren.getOrElse(id, Vector.empty).sorted.foreach(renderCtx(_, depth + 1))
      }
    g.contextRoots.foreach(renderCtx(_, 0))

    sb.append("\n## Timeline (stored temporal edges, per context)\n")
    g.relations.temporal
      .sortBy(t => (t.context.value, t.from.value, t.to.value, t.relation.toString))
      .foreach(t =>
        sb.append(
          s"- [${ctxLabel(g, t.context)}] ${t.from.value} ${t.relation} ${t.to.value} (${t.meta.status})\n"
        )
      )

    sb.append("\n## Causal edges\n")
    g.relations.causal
      .sortBy(c => (c.cause.value, c.effect.value, c.relation.toString))
      .foreach(c =>
        sb.append(s"- ${c.cause.value} ${c.relation} ${c.effect.value} (${c.meta.status})\n")
      )

    sb.append("\n## References\n")
    g.relations.references
      .sortBy(r => (r.from.value, r.to.value, r.mode.toString))
      .foreach(r => sb.append(s"- ${r.from.value} ${r.mode} ${r.to.value}\n"))

    sb.append("\n## State changes\n")
    g.relations.stateChanges
      .sortBy(r => (r.event.value, r.state.value, r.change.toString))
      .foreach(r => sb.append(s"- ${r.event.value} ${r.change} ${r.state.value}\n"))

    sb.append("\n## Hierarchy\n")
    def renderSeg(id: SegmentId, depth: Int): Unit =
      g.segments.get(id).foreach { s =>
        sb.append("  " * depth)
          .append(s"- ${s.id.value} [${s.kind} L${s.level}] ${s.summary.value}\n")
        h.childrenOf.getOrElse(id, Vector.empty).foreach {
          case NarrativeMember.Segment(c)   => renderSeg(c, depth + 1)
          case NarrativeMember.Situation(c) =>
            sb.append("  " * (depth + 1)).append(s"- ${c.value}\n")
        }
      }
    h.primaryRoots(g.segments.keys).foreach(renderSeg(_, 0))

    if m.descriptors.nonEmpty then
      sb.append("\n## Descriptors\n")
      m.descriptors
        .sortBy(d => (d.target.value, d.kind.toString, d.text))
        .foreach(d => sb.append(s"- ${d.target.value} ${d.kind}: ${d.text}\n"))
    sb.toString

  private def ctxLabel(g: NarrativeGraph, id: ContextId): String =
    g.contexts.get(id).map(_.kind.label).getOrElse(id.value)

  private def q(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

  /** Graphviz DOT: situations as boxes clustered by primary segment, entities as ellipses, typed
    * edges with labels.
    */
  def dot(m: StoryModel[?]): String =
    val g = m.graph
    val h = m.hierarchy
    val sb = new StringBuilder
    sb.append("digraph story {\n  rankdir=LR;\n  node [fontsize=10];\n")
    def cluster(id: SegmentId, depth: Int): Unit =
      g.segments.get(id).foreach { s =>
        val pad = "  " * (depth + 1)
        sb.append(pad).append(s"subgraph ${q("cluster_" + s.id.value)} {\n")
        sb.append(pad).append(s"  label=${q(s"${s.kind} ${s.id.value}: ${s.summary.value}")};\n")
        h.childrenOf.getOrElse(id, Vector.empty).foreach {
          case NarrativeMember.Segment(c)   => cluster(c, depth + 1)
          case NarrativeMember.Situation(c) =>
            g.situations.get(c).foreach { sit =>
              sb.append(pad)
                .append(
                  s"  ${q(sit.id.value)} [shape=box label=${q(s"${sit.predicate.lemma}\\n${sit.description}")}];\n"
                )
            }
        }
        sb.append(pad).append("}\n")
      }
    h.primaryRoots(g.segments.keys).foreach(cluster(_, 0))
    g.entities.values.toVector
      .sortBy(_.id)
      .foreach(e => sb.append(s"  ${q(e.id.value)} [shape=ellipse label=${q(e.label.value)}];\n"))
    g.relations.participants
      .sortBy(p => (p.situation.value, p.entity.value, p.role.render))
      .foreach(p =>
        sb.append(
          s"  ${q(p.situation.value)} -> ${q(p.entity.value)} [style=dotted label=${q(p.role.render)}];\n"
        )
      )
    g.relations.temporal
      .sortBy(t => (t.from.value, t.to.value, t.relation.toString))
      .foreach(t =>
        sb.append(
          s"  ${q(t.from.value)} -> ${q(t.to.value)} [color=blue label=${q(t.relation.toString)}];\n"
        )
      )
    g.relations.causal
      .sortBy(c => (c.cause.value, c.effect.value, c.relation.toString))
      .foreach(c =>
        sb.append(
          s"  ${q(c.cause.value)} -> ${q(c.effect.value)} [color=red label=${q(s"${c.relation} (${c.meta.status})")}];\n"
        )
      )
    g.relations.references
      .sortBy(r => (r.from.value, r.to.value, r.mode.toString))
      .foreach(r =>
        sb.append(
          s"  ${q(r.from.value)} -> ${q(r.to.value)} [color=gray style=dashed label=${q(r.mode.toString)}];\n"
        )
      )
    sb.append("}\n")
    sb.toString
