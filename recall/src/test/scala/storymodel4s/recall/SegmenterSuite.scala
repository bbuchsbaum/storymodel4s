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

  // ---- W5: nominal mentions, identity keys, recall-side coreference -----------------------

  test("'the young man' and 'the five men' are distinct participants and entities") {
    val g = graph("The young man told the five men that he was tired. Then the five men left.")
    val u0 = g.ordered(0)
    val agent = u0.proposition.agent.get
    val patient = u0.proposition.patient.get
    assertEquals(agent.distinctiveKey, "young+man")
    assertEquals(patient.distinctiveKey, "five+man")
    assertEquals(agent.number, Some(MentionNumber.Singular))
    assertEquals(patient.number, Some(MentionNumber.Plural))
    assertEquals(agent.determiner, Some(Determiner.Definite))
    assert(!agent.names.contains("man"), agent.names.toString)
    assert(agent.names.contains("youngman") && patient.names.contains("fiveman"))
    assert(agent.names.intersect(patient.names).isEmpty)
    val keys = g.relations.entities.map(_.label)
    assert(keys.contains("young man") && keys.contains("five men"), keys.toString)
    assert(agent.entity != patient.entity)
  }

  test("'the other young man' and 'the young man' are distinct but share the `youngman` key") {
    val g = graph("The young man refused. The other young man went with them.")
    val a = g.ordered(0).proposition.agent.get
    val b = g.ordered(1).proposition.agent.get
    assertEquals(a.distinctiveKey, "young+man")
    assertEquals(b.distinctiveKey, "other+young+man")
    assert(a.entity != b.entity)
    assert(a.names.intersect(b.names).contains("youngman"))
    assertEquals(g.relations.entities.size, 2)
  }

  test("pronouns link to the nearest preceding nominal of compatible number") {
    val g = graph("The man went home. Then he lit a fire.")
    val man = g.relations.entities.find(_.label == "man").get
    val links = g.relations.coreference
    assert(
      links.exists(l => l.antecedent == man.id && l.kind == RecallCorefKind.Pronoun),
      links.toString
    )
    val g2 = graph("The men went home. Then they lit a fire.")
    val men = g2.relations.entities.find(_.label == "men").get
    assert(g2.relations.coreference.exists(_.antecedent == men.id))
    val g3 = graph("The warriors went home. Then they lit a fire.")
    val warriors = g3.relations.entities.find(_.label == "warriors").get
    assert(g3.relations.coreference.exists(l => l.antecedent == warriors.id))
    val g4 = graph("The warrior went home. Then they lit a fire.")
    assert(g4.relations.coreference.isEmpty, g4.relations.coreference.toString)
  }

  test("same-key nominal mentions corefer; conflicting modifiers do not") {
    val g = graph("The young man went home. Later the young man returned. The five men stayed.")
    val young = g.relations.entities.find(_.label == "young man").get
    val same = g.relations.coreference.filter(_.kind == RecallCorefKind.SameKey)
    assertEquals(same.map(_.antecedent), Vector(young.id))
    assertEquals(young.mentions.size, 2)
    assert(g.relations.entities.exists(_.label == "five men"))
  }

  test("capitalized multiword names still work alongside nominal mentions") {
    val g = graph("Stephen King met the young man. Then Stephen King left.")
    assert(g.relations.entities.exists(_.label == "stephen king"))
    assert(g.relations.entities.exists(_.label == "young man"))
    assert(!g.relations.entities.exists(_.label == "stephen"))
  }

  test("plural morphology is normalized in heads while number is retained") {
    assertEquals(
      NominalMention.parse("the canoes").map(m => (m.head, m.number)),
      Some(("cano", MentionNumber.Plural))
    )
    assertEquals(
      NominalMention.parse("the warriors").map(m => (m.head, m.number)),
      Some(("warrior", MentionNumber.Plural))
    )
    assertEquals(
      NominalMention.parse("the men").map(m => (m.head, m.number)),
      Some(("man", MentionNumber.Plural))
    )
    assertEquals(NominalMention.parse("the man who went").map(_.surface), Some("the man"))
    assertEquals(
      NominalMention.keysOf(Vector("young", "two"), "man"),
      Set("youngman", "twoman", "twoyoungman")
    )
    assertEquals(NominalMention.keysOf(Vector.empty, "man"), Set("man"))
  }
