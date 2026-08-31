package docsprobe

import storymodel4s.core.SurfaceUnitKind
import storymodel4s.fixtures.wog.WarOfTheGhostsModel

/** Public-API-only tour of the evidence-backed War of the Ghosts story model. */
@main def modelAStory(): Unit =
  val model = WarOfTheGhostsModel.model
  val graph = model.graph

  def show(id: storymodel4s.core.SituationId): Unit =
    val situation = graph.situations(id)
    val context = graph.contexts(situation.context)
    val evidence = situation.support.units.toVector
      .flatMap(model.atlas.byId.get)
      .filter(_.kind == SurfaceUnitKind.Sentence)
      .sortBy(_.ordinal)
    println(s"${id.value}: ${situation.kindName} / ${situation.predicate.lemma}")
    println(s"  context: ${context.kind.label} (${context.id.value})")
    println(s"  context parent: ${context.parent.map(_.value).getOrElse("none")}")
    println(s"  polarity: ${situation.polarity}; modality: ${situation.modality}")
    evidence.foreach { unit =>
      println(s"  evidence sentence ${unit.ordinal + 1}: ${model.atlas.text(unit)}")
    }
    println(s"  description: ${situation.description}")

  println(s"story: ${model.source.title.getOrElse("untitled")}")
  println(s"surface: ${model.atlas.sentences.size} sentences, ${model.atlas.tokens.size} tokens")
  println(
    s"narrative graph: ${graph.entities.size} entities, " +
      s"${graph.situations.size} situations, ${graph.contexts.size} contexts"
  )
  println(
    s"typed relations: ${graph.relations.temporal.size} temporal, " +
      s"${graph.relations.causal.size} causal, ${graph.relations.goals.size} goal"
  )
  println(s"auditable claims: ${model.claims.size}")
  println()
  show(WarOfTheGhostsModel.S.ym1SaysNoArrows)
  show(WarOfTheGhostsModel.S.lackArrows)
