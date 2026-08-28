package storymodel4s.recall

import munit.FunSuite
import storymodel4s.core.StorySource

class SegmenterSuite extends FunSuite:

  private val anna =
    "A woman goes into this creepy old house. It felt kind of like a Stephen King story. " +
      "She eventually finds somebody downstairs. Before that there was some kind of noise, I think."

  private def graph(text: String): RecallGraph =
    RecallSegmenter.segment(StorySource.fromText(text).toOption.get)

  test("the §13 recall yields four idea units that validate") {
    val g = graph(anna)
    assertEquals(g.size, 4)
    assert(RecallGraph.validated(g).isValid, RecallGraph.validated(g).toString)
    assertEquals(g.ordered.map(_.ordinal), Vector(0, 1, 2, 3))
  }

  test("'kind of like a Stephen King story' is an association, not an episodic assertion") {
    val g = graph(anna)
    assertEquals(g.ordered(1).function, DiscourseFunction.Association)
    assertEquals(g.ordered(0).function, DiscourseFunction.EpisodicAssertion)
  }

  test("'before that … I think' is hedged and states a Before relation to the previous unit") {
    val g = graph(anna)
    val u3 = g.ordered(3)
    val u2 = g.ordered(2)
    assert(u3.expressedUncertainty.isMarked)
    u3.expressedUncertainty match
      case ExpressedUncertainty.Hedged(cues) =>
        val texts = cues.refs.toVector.map(_.span.slice(g.transcript.canonicalText).toOption.get)
        assert(texts.exists(_.equalsIgnoreCase("I think")), texts.toString)
        assert(texts.exists(_.equalsIgnoreCase("some kind of")), texts.toString)
      case other => fail(s"expected Hedged, got $other")
    assertEquals(
      g.relations.temporal,
      g.relations.temporal.filter(e =>
        e.from == u3.id && e.to == u2.id &&
          e.relation == RecallTemporalRelation.Before
      )
    )
    assertEquals(g.relations.temporal.size, 1)
    assertEquals(u3.proposition.modality, ModalityTag.Possible)
    assert(u3.proposition.sensoryTerms.contains("noise"))
  }

  test("'finds somebody downstairs' has predicate find, agent she, unspecified patient, location") {
    val g = graph(anna)
    val u2 = g.ordered(2)
    assertEquals(u2.proposition.predicate, Some("find"))
    assertEquals(u2.proposition.agent.map(_.label), Some("she"))
    assertEquals(u2.proposition.patient.map(p => (p.label, p.specified)), Some(("somebody", false)))
    assertEquals(u2.proposition.locations, Vector("downstairs"))
    assertEquals(u2.proposition.polarity, PolarityTag.Positive)
    assertEquals(u2.proposition.modality, ModalityTag.Asserted)
  }

  test("'a woman goes into this creepy old house' has agent woman, predicate go, sensory creepy") {
    val u0 = graph(anna).ordered(0)
    assertEquals(u0.proposition.predicate, Some("go"))
    assertEquals(u0.proposition.agent.map(_.label), Some("woman"))
    assert(u0.proposition.sensoryTerms.contains("creepy"))
    assertEquals(u0.proposition.locations, Vector("house"))
  }

  test("a compound sentence is split on connectives with causal edges in the right direction") {
    val g = graph("She heard a noise, so she went downstairs because she was scared.")
    assertEquals(g.size, 3, g.units.map(_.text).toString)
    val Vector(a, b, c) = g.ordered
    assert(b.text.toLowerCase.startsWith("so "))
    assert(c.text.toLowerCase.startsWith("because"))
    assert(g.relations.causal.exists(e => e.cause == a.id && e.effect == b.id))
    assert(g.relations.causal.exists(e => e.cause == c.id && e.effect == b.id))
    assert(RecallGraph.validated(g).isValid)
  }

  test("'so much' is not mistaken for a causal connective") {
    val g = graph("There was so much noise in the house.")
    assertEquals(g.size, 1, g.units.map(_.text).toString)
    assertEquals(g.relations.causal, Vector.empty)
  }

  test("'and then' produces a Before edge; negation flips polarity; 'said' marks reported") {
    val g = graph("He went home and then he said that they were ghosts. She did not feel sick.")
    val units = g.ordered
    assert(units.size >= 3, units.map(_.text).toString)
    assert(
      g.relations.temporal.exists(e =>
        e.from == units(0).id && e.to == units(1).id && e.relation == RecallTemporalRelation.Before
      )
    )
    assertEquals(units(1).proposition.modality, ModalityTag.Reported)
    assertEquals(units.last.proposition.polarity, PolarityTag.Negative)
  }

  test("unit spans reproduce the unit text exactly") {
    val g = graph(anna)
    g.units.foreach { u =>
      assertEquals(u.minSpan.slice(g.transcript.canonicalText).toOption.get, u.text)
    }
  }

  test("source monitoring, task commentary, evaluation, and summary are classified") {
    assertEquals(
      RecallSegmenter.classify("i don't remember what happened next"),
      DiscourseFunction.SourceMonitoring
    )
    assertEquals(
      RecallSegmenter.classify("that's all i remember"),
      DiscourseFunction.TaskCommentary
    )
    assertEquals(RecallSegmenter.classify("it was a good story"), DiscourseFunction.Evaluation)
    assertEquals(
      RecallSegmenter.classify("basically the whole story was about a war"),
      DiscourseFunction.Summary
    )
    assertEquals(
      RecallSegmenter.classify("he must have died from the wound"),
      DiscourseFunction.Inference
    )
    assertEquals(RecallSegmenter.classify(""), DiscourseFunction.Uninterpretable)
  }

  test("recall entities are collected from names and role nouns; multiword names merge") {
    val g = graph(anna)
    assert(g.relations.entities.exists(_.label == "woman"))
    assert(g.relations.entities.exists(_.label == "stephen king"), g.relations.entities.toString)
    assert(!g.relations.entities.exists(_.label == "stephen"))
  }

  test("a clause-initial name counts when it is capitalized mid-clause elsewhere") {
    val g = graph("Anna went home. Then she met Anna again.")
    assert(g.relations.entities.exists(_.label == "anna"), g.relations.entities.toString)
    val g2 = graph("Somebody went home. Then the ghosts came.")
    assert(!g2.relations.entities.exists(_.label == "somebody"), g2.relations.entities.toString)
  }

  test("task commentary fires only for whole-unit commentary; leading fillers are stripped") {
    assertEquals(RecallSegmenter.classify("um, okay."), DiscourseFunction.TaskCommentary)
    assertEquals(
      RecallSegmenter.classify("that's all i remember."),
      DiscourseFunction.TaskCommentary
    )
    assertEquals(
      RecallSegmenter.classify("um, they went up the river"),
      DiscourseFunction.EpisodicAssertion
    )
    assertEquals(
      RecallSegmenter.classify("okay so the men went hunting"),
      DiscourseFunction.EpisodicAssertion
    )
  }

  test("'like a' after a perception verb is content, not an association") {
    assertEquals(
      RecallSegmenter.classify("it sounded like a war party"),
      DiscourseFunction.EpisodicAssertion
    )
    assertEquals(
      RecallSegmenter.classify("it was like a horror movie"),
      DiscourseFunction.Association
    )
    assertEquals(
      RecallSegmenter.classify("it felt kind of like a stephen king story"),
      DiscourseFunction.Association
    )
  }

  test("evaluation needs a speaker-evaluative frame") {
    assertEquals(RecallSegmenter.classify("it was scary"), DiscourseFunction.Evaluation)
    assertEquals(
      RecallSegmenter.classify("his family was sad"),
      DiscourseFunction.EpisodicAssertion
    )
  }

  test("hedges inside quoted speech are not the rememberer's hedges") {
    val g = graph("She said \"I think they were ghosts\" and then she left.")
    assert(!g.ordered.head.expressedUncertainty.isMarked, g.ordered.head.toString)
    val g2 = graph("I think she left after that.")
    assert(g2.ordered.head.expressedUncertainty.isMarked)
  }

  test("a discourse 'No,' and 'I don't remember' are not negations") {
    val g = graph("No, he went home after that.")
    assertEquals(g.ordered.head.proposition.polarity, PolarityTag.Positive)
    val g2 = graph("I don't remember if he went home.")
    assertEquals(g2.ordered.head.function, DiscourseFunction.SourceMonitoring)
    assertNotEquals(g2.ordered.head.proposition.polarity, PolarityTag.Negative)
  }

  test("sentence-initial 'So' is not a causal connective; mid-sentence 'so' is") {
    val g = graph("So he went home. She screamed very loudly, so he ran away fast.")
    assert(
      g.relations.causal.forall(e => g.unit(e.effect).exists(_.text.startsWith("so he ran"))),
      g.relations.causal.toString
    )
    assert(g.relations.causal.nonEmpty, g.units.map(_.text).toString)
  }

  test("'earlier' and 'prior to that' state a Before relation backwards") {
    val g = graph("He found his brother. Earlier he had heard a scream.")
    val Vector(a, b) = g.ordered.take(2)
    assert(
      g.relations.temporal.exists(e =>
        e.from == b.id && e.to == a.id && e.relation == RecallTemporalRelation.Before
      ),
      g.relations.temporal.toString
    )
  }

  test("case-insensitive splitting: '…, But then' splits") {
    val g = graph("He went home, But then he came back.")
    assertEquals(g.size, 2, g.units.map(_.text).toString)
  }
