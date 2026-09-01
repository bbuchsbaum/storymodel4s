package docsprobe

import storymodel4s.align.*
import storymodel4s.align.bridge.StorySourceView
import storymodel4s.core.StorySource
import storymodel4s.fixtures.wog.WarOfTheGhostsExpectations.*
import storymodel4s.fixtures.wog.WarOfTheGhostsModel
import storymodel4s.recall.RecallSegmenter

/** Public-API-only support-aware summary of the alignment guide's recall. */
@main def summarizeRecall(): Unit =
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

  def step(value: Option[StepMass]): String = value.map(_.render).getOrElse("n/a (no steps)")

  println(s"estimand: ${signature.estimandVersion}")
  println(f"uniform coverage: ${signature.uniformCoverage}%.4f")
  println(s"importance-weighted coverage: ${signature.importanceWeightedCoverage.render}")
  println(s"fidelity: ${signature.fidelityMass.render}")
  println(s"specificity: ${signature.specificityMass.render}")
  println(s"compression: ${signature.compression.render}")
  println(s"discourse chronology: ${signature.discourseChronology.render}")
  println(s"story-world chronology: ${signature.worldChronology.render}")
  println(s"causal preservation: ${signature.causalPreservation.render}")
  println(s"semantic-flow coherence: ${signature.semanticFlowCoherence.render}")
  println(s"backward discourse mass: ${step(signature.backwardMass)}")
  println(s"backward story-world mass: ${step(signature.worldBackwardMass)}")
  println(f"distorted mass: ${signature.distortedMass}%.4f")
  println(s"external mass: ${signature.externalMass.render}")
