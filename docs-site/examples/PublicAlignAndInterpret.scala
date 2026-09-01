package docsprobe

import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.StorySource
import storymodel4s.fixtures.wog.WarOfTheGhostsExpectations.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.recall.RecallSegmenter

/** Public-API-only measurement for the documentation's Align and Interpret step. */
object PublicAlignAndInterpret:

  @main def alignAndInterpret(): Unit =
    // Initialize the story model before selecting its public recall fixture.
    val model = WarOfTheGhostsModel.model
    val paraphrase = recallParaphrases.find(_.kind == ParaphraseKind.Precise).getOrElse {
      throw new IllegalStateException("the public precise-recall fixture is missing")
    }
    val transcript = StorySource
      .fromText(paraphrase.text, Some("War of the Ghosts recall"))
      .fold(error => throw new IllegalArgumentException(error.message), identity)
    val recall = RecallSegmenter.segment(transcript)
    val view = StorySourceView.validated(model)
    val semantic = SemanticDistance.lexicalJaccard
    val candidates = CandidateGenerator(semantic).generate(recall.ordered, view)
    val result = GraphHsmm
      .infer(recall, view, candidates, DefaultLocalCostModel(semantic = semantic))
      .fold(error => throw new IllegalStateException(error.message), identity)
    val signature = RecallSignature
      .compute(result, recall, view)
      .fold(error => throw new IllegalStateException(error.message), identity)

    println(s"story situations: ${model.graph.situations.size}")
    println(s"alignable nodes: ${view.nodes.size} (${view.leaves.size} situations)")
    println(s"recall units: ${recall.ordered.size}")
    println(s"sparse candidates: ${candidates.totalSize}")
    recall.ordered.zip(result.posterior.rows).foreach { case (unit, row) =>
      val anchor = row.mapSource.map(_.key).getOrElse("external")
      println(f"unit ${unit.ordinal}%d -> $anchor; source=${row.sourceMass}%.4f external=${row.externalMass}%.4f")
    }
    println(s"estimand: ${signature.estimandVersion}")
    println(f"uniform coverage: ${signature.uniformCoverage}%.4f")
    println(s"importance-weighted coverage: ${signature.importanceWeightedCoverage.render}")
    println(s"specificity: ${signature.specificityMass.render}")
    println(s"compression: ${signature.compression.render}")
    println(s"external mass: ${signature.externalMass.render}")
