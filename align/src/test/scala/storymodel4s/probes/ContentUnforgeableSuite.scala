package contentprobe

import munit.FunSuite
import scala.compiletime.testing.typeChecks

/** Construction and read boundaries of the S2a-2 content types, probed from outside package `align`
  * (mote bd-01M379MH86VMN6SNVNRH32G3G6; ADR 0019 S2).
  *
  * Two properties. The types cannot be built except by `ContentProjection`, so a caller cannot hand
  * a strict scorer content that did not come from a projection. And they expose no coordinate: the
  * reads a strict scorer must not make fail to typecheck here, each beside the same read on the
  * coordinate-bearing original, which does typecheck.
  */
class ContentUnforgeableSuite extends FunSuite:

  test("ParticipantContent: new is refused beside a same-shape control") {
    assert(
      typeChecks("import storymodel4s.align.*; new OpenParticipantContent(???, ???, ???)"),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; new ParticipantContent(???, ???, ???)"),
      "ParticipantContent new opened"
    )
  }

  test("ParticipantContent: apply is refused beside a same-shape control") {
    assert(
      typeChecks("import storymodel4s.align.*; OpenParticipantContent(???, ???, ???)"),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; ParticipantContent(???, ???, ???)"),
      "ParticipantContent apply opened"
    )
  }

  test("ParticipantContent: copy is refused beside a same-shape control") {
    assert(
      typeChecks("import storymodel4s.align.*; (??? : OpenParticipantContent).copy()"),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : ParticipantContent).copy()"),
      "ParticipantContent copy opened"
    )
  }

  // Mirror methods for controls defined in this compilation run are synthesized after
  // typeChecks. Use their summoned same-field mirror, and pin direct companion syntax on a
  // precompiled public case class separately (see FlowUnforgeableSuite).
  test("direct fromProduct companion control compiles") {
    assert(typeChecks("storymodel4s.align.FunctionPrior.fromProduct(???)"))
  }

  test("ParticipantContent: fromProduct is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenParticipantContent]].fromProduct(???)"
      ),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; ParticipantContent.fromProduct(???)"),
      "ParticipantContent fromProduct opened"
    )
  }

  test("ParticipantContent: Mirror is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenParticipantContent]]"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[ParticipantContent]]"
      ),
      "ParticipantContent Mirror opened"
    )
  }

  test("UnitContent: new is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; new OpenUnitContent(???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; new UnitContent[storymodel4s.proposition.GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "UnitContent new opened"
    )
  }

  test("UnitContent: apply is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; OpenUnitContent(???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; UnitContent[storymodel4s.proposition.GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "UnitContent apply opened"
    )
  }

  test("UnitContent: copy is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; (??? : OpenUnitContent).copy()"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; (??? : UnitContent[storymodel4s.proposition.GraphOrder.Canonical]).copy()"
      ),
      "UnitContent copy opened"
    )
  }

  test("UnitContent: fromProduct is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenUnitContent]].fromProduct(???)"
      ),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; UnitContent.fromProduct(???)"),
      "UnitContent fromProduct opened"
    )
  }

  test("UnitContent: Mirror is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenUnitContent]]"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[UnitContent[storymodel4s.proposition.GraphOrder.Canonical]]]"
      ),
      "UnitContent Mirror opened"
    )
  }

  test("TargetContent: new is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; new OpenTargetContent(???, ???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; new TargetContent[storymodel4s.proposition.GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "TargetContent new opened"
    )
  }

  test("TargetContent: apply is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; OpenTargetContent(???, ???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; TargetContent[storymodel4s.proposition.GraphOrder.Canonical](???, ???, ???, ???, ???, ???, ???, ???, ???, ???, ???)"
      ),
      "TargetContent apply opened"
    )
  }

  test("TargetContent: copy is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; (??? : OpenTargetContent).copy()"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; (??? : TargetContent[storymodel4s.proposition.GraphOrder.Canonical]).copy()"
      ),
      "TargetContent copy opened"
    )
  }

  test("TargetContent: fromProduct is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenTargetContent]].fromProduct(???)"
      ),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; TargetContent.fromProduct(???)"),
      "TargetContent fromProduct opened"
    )
  }

  test("TargetContent: Mirror is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenTargetContent]]"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[TargetContent[storymodel4s.proposition.GraphOrder.Canonical]]]"
      ),
      "TargetContent Mirror opened"
    )
  }

  test("Members: new is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; new OpenMembers(???, ???)"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; new Members[storymodel4s.proposition.GraphOrder.Canonical](???, ???)"
      ),
      "Members new opened"
    )
  }

  test("Members: apply is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; OpenMembers(???, ???)"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; Members[storymodel4s.proposition.GraphOrder.Canonical](???, ???)"
      ),
      "Members apply opened"
    )
  }

  test("Members: copy is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; (??? : OpenMembers).copy()"), "control")
    assert(
      !typeChecks(
        "import storymodel4s.align.*; (??? : Members[storymodel4s.proposition.GraphOrder.Canonical]).copy()"
      ),
      "Members copy opened"
    )
  }

  test("Members: fromProduct is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenMembers]].fromProduct(???)"
      ),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; Members.fromProduct(???)"),
      "Members fromProduct opened"
    )
  }

  test("Members: Mirror is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenMembers]]"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[Members[storymodel4s.proposition.GraphOrder.Canonical]]]"
      ),
      "Members Mirror opened"
    )
  }

  test("ContentGrain: new is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; new OpenContentGrain(???)"), "control")
    assert(
      !typeChecks("import storymodel4s.align.*; new ContentGrain(???)"),
      "ContentGrain new opened"
    )
  }

  test("ContentGrain: apply is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; OpenContentGrain(???)"), "control")
    assert(
      !typeChecks("import storymodel4s.align.*; ContentGrain(???)"),
      "ContentGrain apply opened"
    )
  }

  test("ContentGrain: copy is refused beside a same-shape control") {
    assert(typeChecks("import storymodel4s.align.*; (??? : OpenContentGrain).copy()"), "control")
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : ContentGrain).copy()"),
      "ContentGrain copy opened"
    )
  }

  test("ContentGrain: fromProduct is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenContentGrain]].fromProduct(???)"
      ),
      "control"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; ContentGrain.fromProduct(???)"),
      "ContentGrain fromProduct opened"
    )
  }

  test("ContentGrain: Mirror is refused beside a same-shape control") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[OpenContentGrain]]"
      ),
      "control"
    )
    assert(
      !typeChecks(
        "import storymodel4s.align.*; summon[scala.deriving.Mirror.ProductOf[ContentGrain]]"
      ),
      "ContentGrain Mirror opened"
    )
  }

  test("a unit's coordinates are readable on RecallUnit and not on UnitContent") {
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).id"), "control id")
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).span"), "control span")
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).text"), "control text")
    assert(
      typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).ordinal"),
      "control ordinal"
    )
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).id"), "id")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).span"), "span")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).text"), "text")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).ordinal"), "ordinal")
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).evidence"), "evidence")
  }

  test("a node's coordinates are readable on NodeSummary and not on TargetContent") {
    assert(typeChecks("import storymodel4s.align.*; (??? : NodeSummary).ref"), "control ref")
    assert(
      typeChecks("import storymodel4s.align.*; (??? : NodeSummary).support"),
      "control support"
    )
    assert(
      typeChecks("import storymodel4s.align.*; (??? : NodeSummary).discoursePosition"),
      "control position"
    )
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).ref"), "ref")
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).parent"), "parent")
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).support"), "support")
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).discoursePosition"),
      "discoursePosition"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).scoringPosition"),
      "scoringPosition"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).importance"),
      "importance"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).evidence"),
      "evidence"
    )
  }

  test("members expose reductions but no named iteration methods outside align") {
    assert(typeChecks("import storymodel4s.align.*; (??? : Members[?]).count"), "control count")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).structural"), "structural")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).leaves"), "leaves")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).iterator"), "iterator")
    assert(!typeChecks("import storymodel4s.align.*; (??? : Members[?]).toVector"), "toVector")
  }

  test("the source-order projection is not public") {
    assert(
      typeChecks("import storymodel4s.align.*; ContentProjection.canonical(???, ???, ???)"),
      "control: the canonical projection"
    )
    assert(
      !typeChecks("import storymodel4s.align.*; ContentProjection.source(???, ???, ???)"),
      "source projection is visible outside align"
    )
  }

  test("the sourceUnit historical shortcut is not public") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; object Open { def sourceUnit(input: storymodel4s.recall.RecallUnit): UnitContent[storymodel4s.proposition.GraphOrder.Source] = ??? }; Open.sourceUnit(???)"
      )
    )
    assert(!typeChecks("import storymodel4s.align.*; ContentProjection.sourceUnit(???)"))
  }

  test("the sourceNode historical shortcut is not public") {
    assert(
      typeChecks(
        "import storymodel4s.align.*; object Open { def sourceNode(input: NodeSummary): TargetContent[storymodel4s.proposition.GraphOrder.Source] = ??? }; Open.sourceNode(???)"
      )
    )
    assert(!typeChecks("import storymodel4s.align.*; ContentProjection.sourceNode(???)"))
  }

  test("nested graph and participant reads expose no original coordinates") {
    assert(
      typeChecks(
        "import storymodel4s.proposition.*; (??? : PropositionChart[CheckState.Checked]).alignments"
      )
    )
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).graph.get.alignments"))
    assert(typeChecks("import storymodel4s.recall.*; (??? : RecallUnit).expressedUncertainty"))
    assert(!typeChecks("import storymodel4s.align.*; (??? : UnitContent[?]).expressedUncertainty"))
    assert(typeChecks("import storymodel4s.align.*; (??? : SourceView).worldOrder"))
    assert(!typeChecks("import storymodel4s.align.*; (??? : ContentGrain).worldOrder"))
    assert(typeChecks("import storymodel4s.align.*; (??? : ParticipantContent).names"))
    assert(!typeChecks("import storymodel4s.align.*; (??? : ParticipantContent).entity"))
    assert(!typeChecks("import storymodel4s.align.*; (??? : TargetContent[?]).graph.get.chart"))
  }

// Same-field public construction controls, outside the local typeChecks compilation scope.
import storymodel4s.align.*

private[contentprobe] final case class OpenParticipantContent(
    role: storymodel4s.recall.SketchRole,
    specified: Boolean,
    names: Set[String]
)

private[contentprobe] final case class OpenUnitContent(
    function: storymodel4s.recall.DiscourseFunction,
    predicate: Option[String],
    participants: Vector[ParticipantContent],
    polarity: storymodel4s.recall.PolarityTag,
    modality: storymodel4s.recall.ModalityTag,
    outcome: Option[String],
    sensoryTerms: Vector[String],
    lemmas: Set[String],
    graph: Option[
      storymodel4s.proposition.SemanticGraph[storymodel4s.proposition.GraphOrder.Canonical]
    ]
)

private[contentprobe] final case class OpenTargetContent(
    level: Int,
    predicate: Option[String],
    participants: Vector[ParticipantContent],
    context: ContextTag,
    polarity: storymodel4s.recall.PolarityTag,
    modality: storymodel4s.recall.ModalityTag,
    outcome: Option[String],
    lemmas: Set[String],
    hasEvidence: Boolean,
    graph: Option[
      storymodel4s.proposition.SemanticGraph[storymodel4s.proposition.GraphOrder.Canonical]
    ],
    members: Members[storymodel4s.proposition.GraphOrder.Canonical]
)

private[contentprobe] final case class OpenMembers(
    structural: Vector[TargetContent[storymodel4s.proposition.GraphOrder.Canonical]],
    leaves: Vector[TargetContent[storymodel4s.proposition.GraphOrder.Canonical]]
)

private[contentprobe] final case class OpenContentGrain(maxLevel: Int)
