package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package courts distinguish checked construction from compiler-generated product doors.
  */
class MappingBoundarySuite extends FunSuite:
  test("MappingPipeline.Run constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(execution: MappingRun.Execution, reference: MappingResult, reconstruction: MappingResult, config: MappingPipeline.Config, registration: Checksum, caption: Option[CaptionMapping.Registered], onnx: Option[OnnxMappingRegistration.Registered])
new Open(??? : MappingRun.Execution, ??? : MappingResult, ??? : MappingResult, ??? : MappingPipeline.Config, ??? : Checksum, ??? : Option[CaptionMapping.Registered], ??? : Option[OnnxMappingRegistration.Registered])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new MappingPipeline.Run(??? : MappingRun.Execution, ??? : MappingResult, ??? : MappingResult, ??? : MappingPipeline.Config, ??? : Checksum, ??? : Option[CaptionMapping.Registered], ??? : Option[OnnxMappingRegistration.Registered])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Run apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(execution: MappingRun.Execution, reference: MappingResult, reconstruction: MappingResult, config: MappingPipeline.Config, registration: Checksum, caption: Option[CaptionMapping.Registered], onnx: Option[OnnxMappingRegistration.Registered])
Open(??? : MappingRun.Execution, ??? : MappingResult, ??? : MappingResult, ??? : MappingPipeline.Config, ??? : Checksum, ??? : Option[CaptionMapping.Registered], ??? : Option[OnnxMappingRegistration.Registered])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
MappingPipeline.Run(??? : MappingRun.Execution, ??? : MappingResult, ??? : MappingResult, ??? : MappingPipeline.Config, ??? : Checksum, ??? : Option[CaptionMapping.Registered], ??? : Option[OnnxMappingRegistration.Registered])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Run copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(execution: MappingRun.Execution, reference: MappingResult, reconstruction: MappingResult, config: MappingPipeline.Config, registration: Checksum, caption: Option[CaptionMapping.Registered], onnx: Option[OnnxMappingRegistration.Registered]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : MappingPipeline.Run).copy()""").nonEmpty)
  }

  test("MappingPipeline.Run fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(execution: MappingRun.Execution, reference: MappingResult, reconstruction: MappingResult, config: MappingPipeline.Config, registration: Checksum, caption: Option[CaptionMapping.Registered], onnx: Option[OnnxMappingRegistration.Registered])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
MappingPipeline.Run.fromProduct(???)""").nonEmpty)
  }

  test("MappingPipeline.Run Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(execution: MappingRun.Execution, reference: MappingResult, reconstruction: MappingResult, config: MappingPipeline.Config, registration: Checksum, caption: Option[CaptionMapping.Registered], onnx: Option[OnnxMappingRegistration.Registered])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[MappingPipeline.Run]]""").nonEmpty)
  }

  test("MappingPipeline.Outcome constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, observation: Option[Checksum], failure: Option[String], run: Option[MappingPipeline.Run], prepared: Option[StudyPreparation.Recall])
new Open(??? : String, ??? : String, ??? : Option[Checksum], ??? : Option[String], ??? : Option[MappingPipeline.Run], ??? : Option[StudyPreparation.Recall])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new MappingPipeline.Outcome(??? : String, ??? : String, ??? : Option[Checksum], ??? : Option[String], ??? : Option[MappingPipeline.Run], ??? : Option[StudyPreparation.Recall])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Outcome apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, observation: Option[Checksum], failure: Option[String], run: Option[MappingPipeline.Run], prepared: Option[StudyPreparation.Recall])
Open(??? : String, ??? : String, ??? : Option[Checksum], ??? : Option[String], ??? : Option[MappingPipeline.Run], ??? : Option[StudyPreparation.Recall])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
MappingPipeline.Outcome(??? : String, ??? : String, ??? : Option[Checksum], ??? : Option[String], ??? : Option[MappingPipeline.Run], ??? : Option[StudyPreparation.Recall])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Outcome copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, observation: Option[Checksum], failure: Option[String], run: Option[MappingPipeline.Run], prepared: Option[StudyPreparation.Recall]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : MappingPipeline.Outcome).copy()""").nonEmpty)
  }

  test("MappingPipeline.Outcome fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, observation: Option[Checksum], failure: Option[String], run: Option[MappingPipeline.Run], prepared: Option[StudyPreparation.Recall])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
MappingPipeline.Outcome.fromProduct(???)""").nonEmpty)
  }

  test("MappingPipeline.Outcome Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, observation: Option[Checksum], failure: Option[String], run: Option[MappingPipeline.Run], prepared: Option[StudyPreparation.Recall])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[MappingPipeline.Outcome]]""").nonEmpty)
  }

  test("StudyPreparation.Recall constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, sourceEdition: Checksum, observation: Checksum, graph: RecallGraph[Checked], inventory: RecallInventory, timing: Option[RecallTiming])
new Open(??? : String, ??? : String, ??? : Checksum, ??? : Checksum, ??? : RecallGraph[Checked], ??? : RecallInventory, ??? : Option[RecallTiming])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new StudyPreparation.Recall(??? : String, ??? : String, ??? : Checksum, ??? : Checksum, ??? : RecallGraph[Checked], ??? : RecallInventory, ??? : Option[RecallTiming])"""
      ).nonEmpty
    )
  }

  test("StudyPreparation.Recall apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, sourceEdition: Checksum, observation: Checksum, graph: RecallGraph[Checked], inventory: RecallInventory, timing: Option[RecallTiming])
Open(??? : String, ??? : String, ??? : Checksum, ??? : Checksum, ??? : RecallGraph[Checked], ??? : RecallInventory, ??? : Option[RecallTiming])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Recall(??? : String, ??? : String, ??? : Checksum, ??? : Checksum, ??? : RecallGraph[Checked], ??? : RecallInventory, ??? : Option[RecallTiming])"""
      ).nonEmpty
    )
  }

  test("StudyPreparation.Recall copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, sourceEdition: Checksum, observation: Checksum, graph: RecallGraph[Checked], inventory: RecallInventory, timing: Option[RecallTiming]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : StudyPreparation.Recall).copy()""").nonEmpty)
  }

  test("StudyPreparation.Recall fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, sourceEdition: Checksum, observation: Checksum, graph: RecallGraph[Checked], inventory: RecallInventory, timing: Option[RecallTiming])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Recall.fromProduct(???)""").nonEmpty)
  }

  test("StudyPreparation.Recall Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(participant: String, session: String, sourceEdition: Checksum, observation: Checksum, graph: RecallGraph[Checked], inventory: RecallInventory, timing: Option[RecallTiming])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[StudyPreparation.Recall]]""").nonEmpty)
  }

  test("StudyPreparation.Prepared constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(source: StudyPreparation.Source, outcomes: Vector[StudyPreparation.Outcome], config: StudyPreparation.Config)
new Open(??? : StudyPreparation.Source, ??? : Vector[StudyPreparation.Outcome], ??? : StudyPreparation.Config)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new StudyPreparation.Prepared(??? : StudyPreparation.Source, ??? : Vector[StudyPreparation.Outcome], ??? : StudyPreparation.Config)"""
      ).nonEmpty
    )
  }

  test("StudyPreparation.Prepared apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(source: StudyPreparation.Source, outcomes: Vector[StudyPreparation.Outcome], config: StudyPreparation.Config)
Open(??? : StudyPreparation.Source, ??? : Vector[StudyPreparation.Outcome], ??? : StudyPreparation.Config)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Prepared(??? : StudyPreparation.Source, ??? : Vector[StudyPreparation.Outcome], ??? : StudyPreparation.Config)"""
      ).nonEmpty
    )
  }

  test("StudyPreparation.Prepared copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(source: StudyPreparation.Source, outcomes: Vector[StudyPreparation.Outcome], config: StudyPreparation.Config);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : StudyPreparation.Prepared).copy()""").nonEmpty)
  }

  test("StudyPreparation.Prepared fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(source: StudyPreparation.Source, outcomes: Vector[StudyPreparation.Outcome], config: StudyPreparation.Config)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Prepared.fromProduct(???)""").nonEmpty)
  }

  test("StudyPreparation.Prepared Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(source: StudyPreparation.Source, outcomes: Vector[StudyPreparation.Outcome], config: StudyPreparation.Config)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[StudyPreparation.Prepared]]""").nonEmpty)
  }

  test("StudyPreparation.Source constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(underlying: SourceView, representation: SourceRepresentation, artifact: String)
new Open(??? : SourceView, ??? : SourceRepresentation, ??? : String)"""),
      Nil
    )
    assert(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new StudyPreparation.Source(??? : SourceView, ??? : SourceRepresentation, ??? : String)""").nonEmpty
    )
  }

  test("StudyPreparation.Source apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(underlying: SourceView, representation: SourceRepresentation, artifact: String)
Open(??? : SourceView, ??? : SourceRepresentation, ??? : String)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Source(??? : SourceView, ??? : SourceRepresentation, ??? : String)""").nonEmpty)
  }

  test("StudyPreparation.Source copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(underlying: SourceView, representation: SourceRepresentation, artifact: String);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : StudyPreparation.Source).copy()""").nonEmpty)
  }

  test("StudyPreparation.Source fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(underlying: SourceView, representation: SourceRepresentation, artifact: String)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
StudyPreparation.Source.fromProduct(???)""").nonEmpty)
  }

  test("StudyPreparation.Source Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(underlying: SourceView, representation: SourceRepresentation, artifact: String)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[StudyPreparation.Source]]""").nonEmpty)
  }

  test("CaptionMapping.Registered constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(media: CaptionSearchResult, produced: CaptionScoring.Produced, expected: CaptionMapping.Expected)
new Open(??? : CaptionSearchResult, ??? : CaptionScoring.Produced, ??? : CaptionMapping.Expected)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new CaptionMapping.Registered(??? : CaptionSearchResult, ??? : CaptionScoring.Produced, ??? : CaptionMapping.Expected)"""
      ).nonEmpty
    )
  }

  test("CaptionMapping.Registered apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(media: CaptionSearchResult, produced: CaptionScoring.Produced, expected: CaptionMapping.Expected)
Open(??? : CaptionSearchResult, ??? : CaptionScoring.Produced, ??? : CaptionMapping.Expected)"""),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
CaptionMapping.Registered(??? : CaptionSearchResult, ??? : CaptionScoring.Produced, ??? : CaptionMapping.Expected)"""
      ).nonEmpty
    )
  }

  test("CaptionMapping.Registered copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(media: CaptionSearchResult, produced: CaptionScoring.Produced, expected: CaptionMapping.Expected);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : CaptionMapping.Registered).copy()""").nonEmpty)
  }

  test("CaptionMapping.Registered fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(media: CaptionSearchResult, produced: CaptionScoring.Produced, expected: CaptionMapping.Expected)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
CaptionMapping.Registered.fromProduct(???)""").nonEmpty)
  }

  test("CaptionMapping.Registered Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(media: CaptionSearchResult, produced: CaptionScoring.Produced, expected: CaptionMapping.Expected)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[CaptionMapping.Registered]]""").nonEmpty)
  }

  test("TimedSourceView constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(segments: Vector[TimedSegment], parts: Map[String, SourceBundle], artifact: Checksum, document: String, view: InMemorySourceView, representation: SourceRepresentation)
new Open(??? : Vector[TimedSegment], ??? : Map[String, SourceBundle], ??? : Checksum, ??? : String, ??? : InMemorySourceView, ??? : SourceRepresentation)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
new TimedSourceView(??? : Vector[TimedSegment], ??? : Map[String, SourceBundle], ??? : Checksum, ??? : String, ??? : InMemorySourceView, ??? : SourceRepresentation)"""
      ).nonEmpty
    )
  }

  test("TimedSourceView apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(segments: Vector[TimedSegment], parts: Map[String, SourceBundle], artifact: Checksum, document: String, view: InMemorySourceView, representation: SourceRepresentation)
Open(??? : Vector[TimedSegment], ??? : Map[String, SourceBundle], ??? : Checksum, ??? : String, ??? : InMemorySourceView, ??? : SourceRepresentation)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
TimedSourceView(??? : Vector[TimedSegment], ??? : Map[String, SourceBundle], ??? : Checksum, ??? : String, ??? : InMemorySourceView, ??? : SourceRepresentation)"""
      ).nonEmpty
    )
  }

  test("TimedSourceView copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(segments: Vector[TimedSegment], parts: Map[String, SourceBundle], artifact: Checksum, document: String, view: InMemorySourceView, representation: SourceRepresentation);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
(??? : TimedSourceView).copy()""").nonEmpty)
  }

  test("TimedSourceView fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(segments: Vector[TimedSegment], parts: Map[String, SourceBundle], artifact: Checksum, document: String, view: InMemorySourceView, representation: SourceRepresentation)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
TimedSourceView.fromProduct(???)""").nonEmpty)
  }

  test("TimedSourceView Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
case class Open(segments: Vector[TimedSegment], parts: Map[String, SourceBundle], artifact: Checksum, document: String, view: InMemorySourceView, representation: SourceRepresentation)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
import storymodel4s.pipeline.*
import storymodel4s.media.*
import storymodel4s.embed.onnx.OnnxMappingRegistration
import storymodel4s.align.bridge.*
summon[scala.deriving.Mirror.ProductOf[TimedSourceView]]""").nonEmpty)
  }

  test("MappingPipeline.Batch constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; case class Open(prepared: StudyPreparation.Prepared, config: MappingPipeline.Config, outcomes: Vector[MappingPipeline.Outcome]); new Open(??? : StudyPreparation.Prepared, ??? : MappingPipeline.Config, ??? : Vector[MappingPipeline.Outcome])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; new MappingPipeline.Batch(??? : StudyPreparation.Prepared, ??? : MappingPipeline.Config, ??? : Vector[MappingPipeline.Outcome])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Batch apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; case class Open(prepared: StudyPreparation.Prepared, config: MappingPipeline.Config, outcomes: Vector[MappingPipeline.Outcome]); Open(??? : StudyPreparation.Prepared, ??? : MappingPipeline.Config, ??? : Vector[MappingPipeline.Outcome])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; MappingPipeline.Batch(??? : StudyPreparation.Prepared, ??? : MappingPipeline.Config, ??? : Vector[MappingPipeline.Outcome])"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Batch copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; case class Open(prepared: StudyPreparation.Prepared, config: MappingPipeline.Config, outcomes: Vector[MappingPipeline.Outcome]); (??? : Open).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; (??? : MappingPipeline.Batch).copy()"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Batch fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; case class Open(prepared: StudyPreparation.Prepared, config: MappingPipeline.Config, outcomes: Vector[MappingPipeline.Outcome]); object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }; Open.fromProduct(???)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; MappingPipeline.Batch.fromProduct(???)"""
      ).nonEmpty
    )
  }

  test("MappingPipeline.Batch Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; case class Open(prepared: StudyPreparation.Prepared, config: MappingPipeline.Config, outcomes: Vector[MappingPipeline.Outcome]); summon[scala.deriving.Mirror.ProductOf[Open]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.pipeline.*; summon[scala.deriving.Mirror.ProductOf[MappingPipeline.Batch]]"""
      ).nonEmpty
    )
  }
