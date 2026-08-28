package storymodel4s.embed.grakern

import cats.kernel.{Eq, Hash}

import grakern.core.{NeighbourhoodError, NeighbourhoodSample, SampleKey}

import storymodel4s.proposition.*
import storymodel4s.proposition.CheckState.Checked

/** Node key of a reified chart vertex.
  *
  * Why a composite key: WL refinement only sees vertex keys and oriented incidences, so every
  * distinction the structural channel must preserve (frame vs. lemma, kind, predicate polarity,
  * role spelling, embedding kind) has to be in the key. The rendering is the durable codec.
  */
final case class ReifiedKey(kind: ReifiedKind, parts: Vector[String]):
  def render: String = (kind.render +: parts).mkString("|")

object ReifiedKey:
  given Hash[ReifiedKey] = Hash.fromUniversalHashCode
  given Eq[ReifiedKey] = Eq.fromUniversalEquals

/** What a reified vertex stands for. */
enum ReifiedKind:
  case Concept, Relation, Embedding, Literal, Unknown

  def render: String = this match
    case Concept   => "concept"
    case Relation  => "relation"
    case Embedding => "embedding"
    case Literal   => "literal"
    case Unknown   => "unknown"

/** Edge value of a reified arc: predicate/container → reified vertex is `src`, reified vertex →
  * filler/content is `tgt`. Direction plus this label is what makes ARG0-source and ARG0-target
  * incidences distinct under grakern's orientation-aware WL (law G1).
  */
enum ArcKind:
  case Src, Tgt

  def render: String = this match
    case Src => "src"
    case Tgt => "tgt"

object ArcKind:
  given Eq[ArcKind] = Eq.fromUniversalEquals
  given Hash[ArcKind] = Hash.fromUniversalHashCode

/** A reified chart as a grakern directed labelled neighbourhood, with the deterministic vertex
  * order used to build it (canonical concept order first, then relations, embeddings, leaves).
  */
final case class ReifiedChart(
    sample: NeighbourhoodSample[String, ReifiedKey, ArcKind],
    vertices: Vector[(String, ReifiedKey)],
    arcs: Vector[(String, String, ArcKind)]
)

/** Relation reification (ADR 0001 rev 3 §D4, §D4c G1).
  *
  * Every relation becomes its own vertex keyed by `(source role spelling, normalized role,
  * embedding kind)`, joined to the predicate by a `src` arc and to the filler by a `tgt` arc. Every
  * embedded proposition becomes an `Embedding` vertex keyed by its kind, joined the same way. This
  * keeps parallel relations between one pair distinct (grakern has no parallel-edge merging under
  * the protocol, but the reified form does not rely on it either) and makes a filler swap between
  * two differently-keyed relations non-isomorphic.
  *
  * The chart is first put in `Canonical.form`, so vertex identifiers — and therefore the sample's
  * enumeration order — are a function of structure alone (law G2 holds by construction on top of
  * grakern's own invariance).
  */
object ChartNeighbourhood:

  def of(
      chart: PropositionChart[Checked],
      key: SampleKey
  ): Either[NeighbourhoodError[String], ReifiedChart] =
    val canonical = Canonical.form(chart)
    val conceptIds = canonical.conceptIds
    val relations = canonical.relations.sortBy(relationSortKey)
    val embeddings =
      canonical.embedded.sortBy(e => (e.container.value, e.kind.ordinal, e.content.value))

    val conceptVertices: Vector[(String, ReifiedKey)] =
      conceptIds.map { id =>
        val c = canonical.concepts(id)
        (conceptVertex(id), conceptKey(c, canonical.polarityOf(id)))
      }

    val relationVertices = Vector.newBuilder[(String, ReifiedKey)]
    val leafVertices = Vector.newBuilder[(String, ReifiedKey)]
    val arcs = Vector.newBuilder[(String, String, ArcKind)]

    relations.zipWithIndex.foreach { case (r, i) =>
      val rv = s"r:$i"
      relationVertices += ((rv, relationKey(r.role)))
      arcs += ((conceptVertex(r.from), rv, ArcKind.Src))
      val target = r.to match
        case ConceptTarget.Node(id)   => conceptVertex(id)
        case ConceptTarget.Literal(v) =>
          val lv = s"l:$i"
          leafVertices += ((lv, ReifiedKey(ReifiedKind.Literal, Vector(literalKind(v), v.render))))
          lv
        case ConceptTarget.Unknown =>
          val uv = s"u:$i"
          leafVertices += ((uv, ReifiedKey(ReifiedKind.Unknown, Vector.empty)))
          uv
      arcs += ((rv, target, ArcKind.Tgt))
    }

    embeddings.zipWithIndex.foreach { case (e, j) =>
      val ev = s"e:$j"
      relationVertices += ((ev, ReifiedKey(ReifiedKind.Embedding, Vector(e.kind.toString))))
      arcs += ((conceptVertex(e.container), ev, ArcKind.Src))
      arcs += ((ev, conceptVertex(e.content), ArcKind.Tgt))
    }

    val vertices = conceptVertices ++ relationVertices.result() ++ leafVertices.result()
    val keys = vertices.toMap
    val arcVector = arcs.result()
    NeighbourhoodSample
      .directed[String, ReifiedKey, ArcKind](
        key,
        vertices.map(_._1),
        v => keys(v),
        arcVector
      )
      .map(sample => ReifiedChart(sample, vertices, arcVector))

  private def conceptVertex(id: ConceptId): String = s"c:${id.value}"

  private def relationSortKey(r: PropositionRelation): (String, String, String) =
    (r.from.value, r.role.source.render, targetRender(r.to))

  private def targetRender(t: ConceptTarget): String = t match
    case ConceptTarget.Node(id)   => s"n:${id.value}"
    case ConceptTarget.Literal(v) => s"l:${v.render}"
    case ConceptTarget.Unknown    => "u"

  private def conceptKey(c: Concept, polarity: Polarity): ReifiedKey =
    val head = c.frame.map(f => s"${f.namespace}:${f.id}").getOrElse(s"lemma:${c.lemma.value}")
    val pol = if c.isPredicate then polarity.toString else "-"
    ReifiedKey(ReifiedKind.Concept, Vector(head, c.kind.toString, pol))

  private def relationKey(role: RoleAssignment): ReifiedKey =
    ReifiedKey(
      ReifiedKind.Relation,
      Vector(role.source.render, role.normalizedRole.map(_.toString).getOrElse("-"))
    )

  private def literalKind(v: LiteralValue): String = v match
    case LiteralValue.Text(_)   => "text"
    case LiteralValue.Number(_) => "number"
    case LiteralValue.Symbol(_) => "symbol"
