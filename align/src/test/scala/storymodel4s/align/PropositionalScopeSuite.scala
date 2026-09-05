package storymodel4s.align

import munit.FunSuite

import storymodel4s.core.{SituationId, SpanSet, TextSpan}
import storymodel4s.features.MissingReason
import storymodel4s.recall.{
  ModalityTag,
  PolarityTag,
  PropositionSketch,
  SketchParticipant,
  SketchRole
}

/** A source view that carries no propositional content must score no facets over it.
  *
  * The recall-to-video view supplies text, lemmas and locations and nothing else, so every
  * `NodeSummary` it builds has `predicate = None`, no participants, and a `context` that has no
  * absent value and therefore reads as `NarratedWorld`. [[FidelityFacets]] took those at face
  * value: a recall unit naming an agent and an action scored `Wrong` on Actor and Action — an error
  * attributed to the rememberer for a silence in the source — while every asserted unit scored
  * `Correct` on Context at once. Both verdicts were artifacts.
  */
class PropositionalScopeSuite extends FunSuite:

  private val span = SpanSet.one(TextSpan.unsafe(0, 10))

  /** A unit that names an agent, an action, an object, an outcome, a cause and a location: every
    * facet has something to say, so nothing here abstains for want of a recall-side value.
    */
  private val sketch = PropositionSketch(
    predicate = Some("go"),
    participants = Vector(
      SketchParticipant(SketchRole.Agent, None, "a woman", true, Set("woman")),
      SketchParticipant(SketchRole.Patient, None, "the door", true, Set("door"))
    ),
    polarity = PolarityTag.Positive,
    modality = ModalityTag.Asserted,
    locations = Vector("home"),
    times = Vector.empty,
    sensoryTerms = Vector.empty,
    lemmas = Set("go", "home", "woman"),
    outcome = Some("reached"),
    cause = Some("storm")
  )

  /** A node with no propositional content, exactly as `TimedSourceView` builds one. */
  private def node(scope: PropositionalScope): NodeSummary =
    NodeSummary(
      ref = SourceNodeRef.Situation(SituationId.unsafe("sit:1")),
      level = 0,
      parent = None,
      discoursePosition = 0,
      support = span,
      predicate = None,
      participants = Vector.empty,
      context = ContextTag.NarratedWorld,
      polarity = PolarityTag.Unknown,
      modality = ModalityTag.Unknown,
      locations = Vector("home"),
      lemmas = Set("home"),
      propositional = scope
    )

  private val propositional =
    Set(Facet.Actor, Facet.Action, Facet.Object, Facet.Outcome, Facet.Cause, Facet.Context)

  test("an undeclared view scores no verdict on the facets it cannot support") {
    val report = FidelityFacets.assess(
      sketch,
      node(PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
    )
    propositional.foreach { facet =>
      assertEquals(
        report.verdicts.get(facet),
        Some(FacetVerdict.Unspecified),
        s"$facet was decided from a source that declares nothing"
      )
    }
  }

  /** Location is read off `locations`, which this view does supply, so it stays scored. The scope
    * silences the facets that have no source, not the report.
    */
  test("an undeclared view still scores the facets it does supply") {
    val report = FidelityFacets.assess(
      sketch,
      node(PropositionalScope.Undeclared(MissingReason.ProviderAbstained))
    )
    assertEquals(report.verdicts.get(Facet.Location), Some(FacetVerdict.Correct))
  }

  /** The counterexample that names the defect: with the identical empty node, declaring the view
    * restores the old verdicts. So the fabrication was the undeclared reading, not the scorer.
    */
  test("declaring the same empty node is what produced the fabricated verdicts") {
    val report = FidelityFacets.assess(sketch, node(PropositionalScope.Declared))
    assertEquals(report.verdicts.get(Facet.Actor), Some(FacetVerdict.Wrong))
    assertEquals(report.verdicts.get(Facet.Action), Some(FacetVerdict.Wrong))
    assertEquals(report.verdicts.get(Facet.Object), Some(FacetVerdict.Wrong))
    assertEquals(report.verdicts.get(Facet.Outcome), Some(FacetVerdict.Wrong))
    assertEquals(report.verdicts.get(Facet.Cause), Some(FacetVerdict.Wrong))
    assertEquals(report.verdicts.get(Facet.Context), Some(FacetVerdict.Correct))
  }

  /** A caller that says nothing gets abstention, not assertion — the same default discipline as
    * `ImportanceWeight.unmeasured`.
    */
  test("a node whose caller declared nothing is undeclared") {
    assert(!PropositionalScope.unstated.declares)
    val built = NodeSummary(
      ref = SourceNodeRef.Situation(SituationId.unsafe("sit:2")),
      level = 0,
      parent = None,
      discoursePosition = 0,
      support = span,
      predicate = None,
      participants = Vector.empty,
      context = ContextTag.NarratedWorld,
      polarity = PolarityTag.Unknown,
      modality = ModalityTag.Unknown,
      locations = Vector.empty,
      lemmas = Set.empty
    )
    assert(!built.propositional.declares)
  }
