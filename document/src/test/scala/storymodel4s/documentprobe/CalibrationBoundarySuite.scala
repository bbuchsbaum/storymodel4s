package storymodel4s.documentprobe

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Separate-package controls for every construction mechanism of fitted study values. */
class CalibrationBoundarySuite extends FunSuite:
  test("same-shape public product controls compile") {
    assertEquals(typeCheckErrors("case class P(x: Int); P(1)"), Nil)
    assertEquals(typeCheckErrors("case class P(x: Int); P(1).copy(x = 2)"), Nil)
    assertEquals(typeCheckErrors("storymodel4s.core.Evidence.fromProduct(EmptyTuple)"), Nil)
    assertEquals(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.Evidence]].fromProduct(EmptyTuple)"
      ),
      Nil
    )
  }
  test("Item refuses constructor, apply, copy and both product doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.Item(null, null, null, null, null, null, null, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Item(null, null, null, null, null, null, null, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.Item) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Item.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; summon[scala.deriving.Mirror.ProductOf[ParticipantCalibration.Item]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }
  test("Corpus refuses constructor, apply, copy and both product doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.Corpus(Vector.empty, Vector.empty, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Corpus(Vector.empty, Vector.empty, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.Corpus) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Corpus.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; summon[scala.deriving.Mirror.ProductOf[ParticipantCalibration.Corpus]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }
  test("CellFit refuses constructor, apply, copy and both product doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.CellFit(0, 0, Set.empty, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.CellFit(0, 0, Set.empty, null)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.CellFit) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.CellFit.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; summon[scala.deriving.Mirror.ProductOf[ParticipantCalibration.CellFit]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }
  test("Model refuses constructor, apply, copy and both product doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.Model(null, null, Map.empty, Vector.empty)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Model(null, null, Map.empty, Vector.empty)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.Model) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Model.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; summon[scala.deriving.Mirror.ProductOf[ParticipantCalibration.Model]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }
  test("Fold refuses constructor, apply, copy and both product doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.Fold(null, Left(ParticipantCalibration.Refusal.TrainingStory), Vector.empty, 0, None, None)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Fold(null, Left(ParticipantCalibration.Refusal.TrainingStory), Vector.empty, 0, None, None)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.Fold) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.Fold.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; summon[scala.deriving.Mirror.ProductOf[ParticipantCalibration.Fold]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }

  test("HeldOut has no forged joined-output construction doors") {
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; new ParticipantCalibration.HeldOut(null, ParticipantCalibration.Verdict.Correct, Left(ParticipantCalibration.Refusal.TrainingStory))"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ParticipantCalibration.HeldOut(null, ParticipantCalibration.Verdict.Correct, Left(ParticipantCalibration.Refusal.TrainingStory))"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "import storymodel4s.document.ParticipantCalibration; ((x: ParticipantCalibration.HeldOut) => x.copy())"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "storymodel4s.document.ParticipantCalibration.HeldOut.fromProduct(EmptyTuple)"
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        "summon[scala.deriving.Mirror.ProductOf[storymodel4s.document.ParticipantCalibration.HeldOut]].fromProduct(EmptyTuple)"
      ).nonEmpty
    )
  }
