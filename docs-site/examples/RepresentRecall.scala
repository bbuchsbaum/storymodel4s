package docsprobe

import storymodel4s.core.StorySource
import storymodel4s.fixtures.wog.WarOfTheGhostsExpectations.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Public-API-only inspection of the recall graph used by the alignment guide. */
@main def representRecall(): Unit =
  val paraphrase = recallParaphrases.find(_.kind == ParaphraseKind.Precise).getOrElse {
    throw new IllegalStateException("the public precise-recall fixture is missing")
  }
  val transcript = StorySource
    .fromText(paraphrase.text, Some("War of the Ghosts recall"))
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val recall: RecallGraph[Checked] = RecallSegmenter.segment(transcript)

  println(s"transcript: ${transcript.canonicalText}")
  println(s"checked recall units: ${recall.size}")
  recall.ordered.foreach { unit =>
    println(s"unit ${unit.ordinal} ${unit.minSpan}: ${unit.text}")
    println(s"  function: ${unit.function}")
    println(s"  uncertainty: ${unit.expressedUncertainty}")
    println(s"  predicate: ${unit.proposition.predicate.getOrElse("unknown")}")
    val participants = unit.proposition.participants
      .map(participant => s"${participant.role}:${participant.label}")
      .mkString(", ")
    println(s"  participants: $participants")
    println(
      s"  polarity: ${unit.proposition.polarity}; modality: ${unit.proposition.modality}"
    )
  }
  println(s"temporal relations: ${recall.relations.temporal.size}")
  println(s"causal relations: ${recall.relations.causal.size}")
  println(s"recall entities: ${recall.relations.entities.map(_.label).sorted.mkString(", ")}")
  println(s"coreference links: ${recall.relations.coreference.size}")
