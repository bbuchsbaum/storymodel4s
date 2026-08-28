package storymodel4s.proposition

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storymodel4s.core.*

class ValidateSuite extends ScalaCheckSuite:
  import ChartGens.given

  private def c(i: Int) = ConceptId.unsafe(s"c$i")

  test("partial charts are valid: unknown filler, no frame, no polarity, unknown concept") {
    val chart = PropositionChart.unchecked(
      Some(c(0)),
      Map(c(0) -> Concept.predicate("strike"), c(1) -> Concept.unknown),
      Vector(
        PropositionRelation(c(0), RoleAssignment.arg(0), ConceptTarget.Unknown),
        PropositionRelation(c(0), RoleAssignment.arg(1), ConceptTarget.Node(c(1)))
      )
    )
    assert(ChartValidator.validate(chart).isValid)
  }

  test("numbered role on a frameless predicate must not be normalized") {
    val chart = PropositionChart.unchecked(
      None,
      Map(c(0) -> Concept.predicate("strike"), c(1) -> Concept.entity("warrior")),
      Vector(
        PropositionRelation(
          c(0),
          RoleAssignment(
            SourceRole.Numbered(0),
            Some((ParticipantRole.Agent, Credence.unsafeRaw(1)))
          ),
          ConceptTarget.Node(c(1))
        )
      )
    )
    val vs = ChartValidator.check(chart).left.getOrElse(Vector.empty)
    assert(vs.exists { case ChartViolation.UnlicensedNormalization(_, _) => true; case _ => false })
  }

  test("numbered role with a frame may be normalized") {
    val frame = Some(FrameRef("propbank", "strike-01", None))
    val chart = PropositionChart.unchecked(
      None,
      Map(c(0) -> Concept.predicate("strike", frame), c(1) -> Concept.entity("warrior")),
      Vector(
        PropositionRelation(
          c(0),
          RoleAssignment(
            SourceRole.Numbered(0),
            Some((ParticipantRole.Agent, Credence.unsafeRaw(1)))
          ),
          ConceptTarget.Node(c(1))
        )
      )
    )
    assert(ChartValidator.validate(chart).isValid)
  }

  property("generated valid charts validate") {
    forAll { (chart: PropositionChart[Checked]) =>
      ChartValidator.validate(chart).isValid
    }
  }

  property("validation is idempotent") {
    forAll { (chart: PropositionChart[Checked]) =>
      val again = ChartValidator.validate(chart).toOption.get
      again == chart && ChartValidator.validate(again.unchecked).toOption.get == chart
    }
  }

  property("each minimally invalid chart reports its injected violation class") {
    forAll(ChartGens.invalidChart) { (chart, expected) =>
      ChartValidator.check(chart) match
        case Left(vs) =>
          val names = vs.map(_.productPrefix)
          (names.contains(expected) :| s"expected $expected in $names") &&
          vs.forall(_.path.startsWith("chart/"))
        case Right(_) => falsified :| s"expected $expected but chart validated"
    }
  }

  test("violations carry paths and messages") {
    val chart = PropositionChart.unchecked(Some(c(9)), Map.empty, Vector.empty)
    val vs = ChartValidator.check(chart).left.getOrElse(Vector.empty)
    assertEquals(vs.map(_.path), Vector("chart/focus"))
    assert(vs.head.message.contains("c9"))
  }

  test("SourceRole.numbered enforces the bound") {
    assert(SourceRole.numbered(10).isLeft)
    assert(SourceRole.numbered(-1).isLeft)
    assertEquals(SourceRole.numbered(9), Right(SourceRole.Numbered(9)))
  }
