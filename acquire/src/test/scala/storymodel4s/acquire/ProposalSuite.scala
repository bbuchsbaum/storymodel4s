package storymodel4s.acquire

import munit.ScalaCheckSuite
import org.scalacheck.Prop.*
import storymodel4s.core.*

class ProposalSuite extends ScalaCheckSuite:
  import Fixtures.*
  import Fixtures.given

  test("Proposed and Alternative require a value and evidence"):
    assert(
      AgentProposal
        .from[Int](
          task,
          None,
          ProposalDisposition.Proposed,
          Vector(ev("e")),
          None,
          Vector.empty,
          receipt
        )
        .isLeft
    )
    assert(
      AgentProposal
        .from[Int](
          task,
          Some(1),
          ProposalDisposition.Proposed,
          Vector.empty,
          None,
          Vector.empty,
          receipt
        )
        .isLeft
    )
    assert(
      AgentProposal
        .from[Int](
          task,
          Some(1),
          ProposalDisposition.Alternative,
          Vector(ev("e")),
          None,
          Vector.empty,
          receipt
        )
        .isRight
    )

  test("Abstained and Unsupported must not carry a value"):
    assert(
      AgentProposal
        .from[Int](
          task,
          Some(1),
          ProposalDisposition.Abstained,
          Vector.empty,
          None,
          Vector.empty,
          receipt
        )
        .isLeft
    )
    assert(
      AgentProposal
        .from[Int](
          task,
          None,
          ProposalDisposition.Unsupported,
          Vector(ev("e")),
          None,
          Vector.empty,
          receipt
        )
        .isRight
    )

  test("raw scores reject non-finite values"):
    assert(RawScore.from(Double.NaN, ScorerId.unsafe("test-scorer")).isLeft)
    assert(RawScore.from(Double.PositiveInfinity, ScorerId.unsafe("test-scorer")).isLeft)
    assert(RawScore.from(-3.5, ScorerId.unsafe("test-scorer")).isRight)

  property("smart constructors always satisfy the disposition invariants"):
    forAll { (p: AgentProposal[Int]) =>
      import ProposalDisposition.*
      p.disposition match
        case Proposed | Alternative  => p.value.isDefined && p.evidence.nonEmpty
        case Abstained | Unsupported => p.value.isEmpty
    }

  property("Functor maps the value and nothing else"):
    forAll { (p: AgentProposal[Int]) =>
      val q = p.map(_ + 1)
      q.value == p.value.map(_ + 1) && q.disposition == p.disposition &&
      q.evidence == p.evidence && q.rawScore == p.rawScore && q.receipt == p.receipt
    }

  property("functor identity and composition"):
    forAll { (p: AgentProposal[Int]) =>
      val f = (i: Int) => i * 2
      val g = (i: Int) => i - 3
      p.map(identity) == p && p.map(f).map(g) == p.map(f andThen g)
    }

  test("evidence refs expose ids and spans"):
    val inline = EvidenceRef.Inline(evidence("x", Some(someSpans)))
    assertEquals(inline.evidenceId, EvidenceId.unsafe("x"))
    assertEquals(inline.spans, Some(someSpans))
    assertEquals(EvidenceRef.ById(EvidenceId.unsafe("y")).spans, None)
