package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeChecks
import storymodel4s.story.{StoryText, TextModel}

class D1aTextWitnessSuite extends FunSuite:
  private val textDependency = classOf[StoryText]
  private val modelDependency = classOf[TextModel[?]]

  test("accepting control: checked text factories, promotions and text reads compile"):
    assert(textDependency != null && modelDependency != null)
    assert(typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def construct(surface: SurfaceAtlas, graph: NarrativeGraph, hierarchy: NarrativeHierarchy,
          trajectory: DiscourseTrajectory) = StoryModel.draftText(surface, graph, hierarchy, trajectory)
      def read(model: TextModel[ModelStatus.Draft], ref: StoryRef, span: TextSpan) =
        (model.source, model.atlas, model.supporting(ref), model.covering(span),
          model.situationsCovering(span), Renderer.text(model), StoryValidator.validate(model))
      def general(model: StoryModel[ModelStatus.Draft]) = StoryModel.asText(model)
      def promote(model: TextModel[ModelStatus.Validated]) = StoryModel.adjudicated(model)
    """))

  test("StoryText constructor is not public"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(text: StoryText) = new StoryText(text.source, text.surface, text.stream)
    """))
  test("TextModel constructor cannot pair a model with foreign text"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(model: StoryModel[ModelStatus.Draft], text: StoryText) =
        new TextModel[ModelStatus.Draft](model, text)
    """))
  test("StoryText has no public apply or copy"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(text: StoryText) = StoryText(text.source, text.surface, text.stream)
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(text: StoryText) = text.copy()
    """))
  test("TextModel has no public apply or copy"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(model: StoryModel[ModelStatus.Draft], text: StoryText) = TextModel(model, text)
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      def forge(model: TextModel[ModelStatus.Draft]) = model.copy()
    """))
  test("StoryText is not a Product and has no Product Mirror"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def product(text: StoryText): Product = text
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      summon[scala.deriving.Mirror.ProductOf[StoryText]]
    """))
  test("TextModel is not a Product and has no Product Mirror"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def product(model: TextModel[ModelStatus.Draft]): Product = model
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      summon[scala.deriving.Mirror.ProductOf[TextModel[ModelStatus.Draft]]]
    """))
  test("generic model cannot stand in for a text model at a text consumer"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def render(model: StoryModel[ModelStatus.Draft]) = Renderer.text(model)
    """))
  test("text factory accepts no independent source"):
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def forge(source: StorySource, surface: SurfaceAtlas, graph: NarrativeGraph,
          hierarchy: NarrativeHierarchy, trajectory: DiscourseTrajectory) =
        StoryModel.draftText(surface, graph, hierarchy, trajectory, source = source)
    """))
  test("generic model has no source or public text support queries"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def read(model: StoryModel[ModelStatus.Draft]) = model.source
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      def read(model: StoryModel[ModelStatus.Draft], ref: StoryRef) = model.supporting(ref)
    """))
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(model: StoryModel[ModelStatus.Draft], span: TextSpan) = model.covering(span)
    """))
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(model: StoryModel[ModelStatus.Draft], span: TextSpan) = model.situationsCovering(span)
    """))
  test("unbound graph has no public text support queries"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def read(graph: NarrativeGraph, ref: StoryRef) = graph.supporting(ref)
    """))
    assert(!typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def read(graph: NarrativeGraph, span: TextSpan) = graph.covering(span)
    """))
  test("text witness does not forward internal copy or promotion"):
    assert(!typeChecks("""
      import storymodel4s.story.*
      def promote(model: TextModel[ModelStatus.Draft]) = model.withStatus[ModelStatus.Validated]
    """))
    assert(!typeChecks("""
      import storymodel4s.story.*
      def promote(model: TextModel[ModelStatus.Draft]) = model.promoted[ModelStatus.Validated]
    """))

  test("detached node carriers intentionally retain typed apply copy Product and Mirror"):
    assert(typeChecks("""
      import storymodel4s.core.*
      import storymodel4s.story.*
      def detached(node: EventNode, support: TypedSupport): EventNode = node.copy(support = support)
      def product(node: EventNode): Product = node
      summon[scala.deriving.Mirror.ProductOf[EventNode]]
    """))
