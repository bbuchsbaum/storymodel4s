package docsprobe

import storymodel4s.amr.graph.*
import storymodel4s.amr.interop.*
import storymodel4s.amr.schema.StarterLexicon
import storymodel4s.core.*
import storymodel4s.proposition as p

@main def penmanToChart(): Unit =
  val penman =
    """(a / and
      |  :op1 (s / say-01
      |    :ARG0 (t / they)
      |    :ARG1 (h / hit-01 :ARG1 (m / man)))
      |  :op2 (f / feel-01
      |    :ARG0 m
      |    :ARG1 (k / sick-05 :ARG1 m)
      |    :polarity -))""".stripMargin

  val unchecked = Decoder
    .graphFromPenman(penman)
    .fold(error => throw new IllegalArgumentException(error), identity)
  val graph = RoleCanonicalizer.fromUnchecked(unchecked).fold(
    errors =>
      throw new IllegalArgumentException(AmrValidator.messages(errors).mkString("; ")),
    identity
  )
  val sentence = SurfaceUnitId
    .from("sentence-1")
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val chart = ToChart
    .convert(graph, None, StarterLexicon.lexicon, Some(sentence))
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val canonical = p.Canonical.form(chart)

  def target(value: p.ConceptTarget): String = value match
    case p.ConceptTarget.Node(id)       => id.value
    case p.ConceptTarget.Literal(value) => value.render
    case p.ConceptTarget.Unknown        => "?"

  println(s"checked AMR nodes: ${graph.nodes.size}")
  println(s"chart concepts: ${canonical.concepts.size}")
  println(s"chart relations: ${canonical.relations.size}")
  println(s"focus: ${canonical.focus.map(canonical.concepts(_).lemma.value).getOrElse("none")}")
  canonical.concepts.toVector.sortBy(_._1.value).foreach { case (id, concept) =>
    val frame = concept.frame.map(_.id).getOrElse("unframed")
    println(s"concept ${id.value}: ${concept.lemma.value}; ${concept.kind}; $frame; ${canonical.polarityOf(id)}")
  }
  canonical.relations.sortBy(r => (r.from.value, r.role.source.render, target(r.to))).foreach { r =>
    val normalized = r.role.normalizedRole.map(_.toString).getOrElse("unmapped")
    println(s"relation ${r.from.value} -${r.role.source.render}/$normalized-> ${target(r.to)}")
  }
  canonical.embedded.foreach { embedded =>
    println(s"embedded ${embedded.content.value} under ${embedded.container.value}: ${embedded.kind}")
  }
  val reentrant = canonical.conceptIds.filter(canonical.isReentrant).map(_.value).sorted
  println(s"reentrant concepts: ${reentrant.mkString(", ")}")
  println(s"source digest: ${ToChart.sourceDigest(canonical).map(_.hex).getOrElse("missing")}")
  println(s"chart checksum: ${p.Canonical.checksum(canonical).hex}")
