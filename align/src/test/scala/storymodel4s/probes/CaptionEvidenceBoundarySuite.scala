package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** Outside-package courts have a same-shape control for each prohibited door. */
class CaptionEvidenceBoundarySuite extends FunSuite:
  test("CaptionEvidence constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(bundle: SourceBundle, framesIdentity: Checksum, framesChecksum: Checksum, receipt: SourceDerivationReceipt, captions: Vector[CaptionEvidence.Caption])
new Open(??? : SourceBundle, ??? : Checksum, ??? : Checksum, ??? : SourceDerivationReceipt, ??? : Vector[CaptionEvidence.Caption])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
new CaptionEvidence(??? : SourceBundle, ??? : Checksum, ??? : Checksum, ??? : SourceDerivationReceipt, ??? : Vector[CaptionEvidence.Caption])"""
      ).nonEmpty
    )
  }

  test("CaptionEvidence apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(bundle: SourceBundle, framesIdentity: Checksum, framesChecksum: Checksum, receipt: SourceDerivationReceipt, captions: Vector[CaptionEvidence.Caption])
Open(??? : SourceBundle, ??? : Checksum, ??? : Checksum, ??? : SourceDerivationReceipt, ??? : Vector[CaptionEvidence.Caption])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionEvidence(??? : SourceBundle, ??? : Checksum, ??? : Checksum, ??? : SourceDerivationReceipt, ??? : Vector[CaptionEvidence.Caption])"""
      ).nonEmpty
    )
  }

  test("CaptionEvidence copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(bundle: SourceBundle, framesIdentity: Checksum, framesChecksum: Checksum, receipt: SourceDerivationReceipt, captions: Vector[CaptionEvidence.Caption]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
(??? : CaptionEvidence).copy()""").nonEmpty)
  }

  test("CaptionEvidence fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(bundle: SourceBundle, framesIdentity: Checksum, framesChecksum: Checksum, receipt: SourceDerivationReceipt, captions: Vector[CaptionEvidence.Caption])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionEvidence.fromProduct(???)""").nonEmpty)
  }

  test("CaptionEvidence Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(bundle: SourceBundle, framesIdentity: Checksum, framesChecksum: Checksum, receipt: SourceDerivationReceipt, captions: Vector[CaptionEvidence.Caption])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
summon[scala.deriving.Mirror.ProductOf[CaptionEvidence]]""").nonEmpty)
  }

  test("CaptionEvidence.Caption constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(text: String, frames: Vector[Int], support: PlaybackInterval)
new Open(??? : String, ??? : Vector[Int], ??? : PlaybackInterval)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
new CaptionEvidence.Caption(??? : String, ??? : Vector[Int], ??? : PlaybackInterval)""").nonEmpty)
  }

  test("CaptionEvidence.Caption apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(text: String, frames: Vector[Int], support: PlaybackInterval)
Open(??? : String, ??? : Vector[Int], ??? : PlaybackInterval)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionEvidence.Caption(??? : String, ??? : Vector[Int], ??? : PlaybackInterval)""").nonEmpty)
  }

  test("CaptionEvidence.Caption copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(text: String, frames: Vector[Int], support: PlaybackInterval);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
(??? : CaptionEvidence.Caption).copy()""").nonEmpty)
  }

  test("CaptionEvidence.Caption fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(text: String, frames: Vector[Int], support: PlaybackInterval)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionEvidence.Caption.fromProduct(???)""").nonEmpty)
  }

  test("CaptionEvidence.Caption Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(text: String, frames: Vector[Int], support: PlaybackInterval)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
summon[scala.deriving.Mirror.ProductOf[CaptionEvidence.Caption]]""").nonEmpty)
  }

  test("CaptionScoring constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(blend: Double, evidence: CaptionEvidence, additionalEvidence: Vector[CaptionEvidence], correspondence: Option[Checksum])
new Open(??? : Double, ??? : CaptionEvidence, ??? : Vector[CaptionEvidence], ??? : Option[Checksum])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
new CaptionScoring(??? : Double, ??? : CaptionEvidence, ??? : Vector[CaptionEvidence], ??? : Option[Checksum])"""
      ).nonEmpty
    )
  }

  test("CaptionScoring apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(blend: Double, evidence: CaptionEvidence, additionalEvidence: Vector[CaptionEvidence], correspondence: Option[Checksum])
Open(??? : Double, ??? : CaptionEvidence, ??? : Vector[CaptionEvidence], ??? : Option[Checksum])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring(??? : Double, ??? : CaptionEvidence, ??? : Vector[CaptionEvidence], ??? : Option[Checksum])"""
      ).nonEmpty
    )
  }

  test("CaptionScoring copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(blend: Double, evidence: CaptionEvidence, additionalEvidence: Vector[CaptionEvidence], correspondence: Option[Checksum]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
(??? : CaptionScoring).copy()""").nonEmpty)
  }

  test("CaptionScoring fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(blend: Double, evidence: CaptionEvidence, additionalEvidence: Vector[CaptionEvidence], correspondence: Option[Checksum])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring.fromProduct(???)""").nonEmpty)
  }

  test("CaptionScoring Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(blend: Double, evidence: CaptionEvidence, additionalEvidence: Vector[CaptionEvidence], correspondence: Option[Checksum])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
summon[scala.deriving.Mirror.ProductOf[CaptionScoring]]""").nonEmpty)
  }

  test("CaptionScoring.Session constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(scoring: CaptionScoring, recall: RecallGraph[Checked], view: SourceView, source: SourceRepresentation, captions: Map[SourceNodeRef, CaptionEvidence.Caption])
new Open(??? : CaptionScoring, ??? : RecallGraph[Checked], ??? : SourceView, ??? : SourceRepresentation, ??? : Map[SourceNodeRef, CaptionEvidence.Caption])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
new CaptionScoring.Session(??? : CaptionScoring, ??? : RecallGraph[Checked], ??? : SourceView, ??? : SourceRepresentation, ??? : Map[SourceNodeRef, CaptionEvidence.Caption])"""
      ).nonEmpty
    )
  }

  test("CaptionScoring.Session apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(scoring: CaptionScoring, recall: RecallGraph[Checked], view: SourceView, source: SourceRepresentation, captions: Map[SourceNodeRef, CaptionEvidence.Caption])
Open(??? : CaptionScoring, ??? : RecallGraph[Checked], ??? : SourceView, ??? : SourceRepresentation, ??? : Map[SourceNodeRef, CaptionEvidence.Caption])"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring.Session(??? : CaptionScoring, ??? : RecallGraph[Checked], ??? : SourceView, ??? : SourceRepresentation, ??? : Map[SourceNodeRef, CaptionEvidence.Caption])"""
      ).nonEmpty
    )
  }

  test("CaptionScoring.Session copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(scoring: CaptionScoring, recall: RecallGraph[Checked], view: SourceView, source: SourceRepresentation, captions: Map[SourceNodeRef, CaptionEvidence.Caption]);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
(??? : CaptionScoring.Session).copy()""").nonEmpty)
  }

  test("CaptionScoring.Session fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(scoring: CaptionScoring, recall: RecallGraph[Checked], view: SourceView, source: SourceRepresentation, captions: Map[SourceNodeRef, CaptionEvidence.Caption])
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring.Session.fromProduct(???)""").nonEmpty)
  }

  test("CaptionScoring.Session Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(scoring: CaptionScoring, recall: RecallGraph[Checked], view: SourceView, source: SourceRepresentation, captions: Map[SourceNodeRef, CaptionEvidence.Caption])
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
summon[scala.deriving.Mirror.ProductOf[CaptionScoring.Session]]""").nonEmpty)
  }

  test("CaptionScoring.Produced constructor refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(session: CaptionScoring.Session, config: StrictCandidateConfig, inventory: RecallInventory, evidence: LocalEvidence)
new Open(??? : CaptionScoring.Session, ??? : StrictCandidateConfig, ??? : RecallInventory, ??? : LocalEvidence)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
new CaptionScoring.Produced(??? : CaptionScoring.Session, ??? : StrictCandidateConfig, ??? : RecallInventory, ??? : LocalEvidence)"""
      ).nonEmpty
    )
  }

  test("CaptionScoring.Produced apply refuses with matching positive control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(session: CaptionScoring.Session, config: StrictCandidateConfig, inventory: RecallInventory, evidence: LocalEvidence)
Open(??? : CaptionScoring.Session, ??? : StrictCandidateConfig, ??? : RecallInventory, ??? : LocalEvidence)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring.Produced(??? : CaptionScoring.Session, ??? : StrictCandidateConfig, ??? : RecallInventory, ??? : LocalEvidence)"""
      ).nonEmpty
    )
  }

  test("CaptionScoring.Produced copy refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(session: CaptionScoring.Session, config: StrictCandidateConfig, inventory: RecallInventory, evidence: LocalEvidence);
(??? : Open).copy()"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
(??? : CaptionScoring.Produced).copy()""").nonEmpty)
  }

  test("CaptionScoring.Produced fromProduct refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(session: CaptionScoring.Session, config: StrictCandidateConfig, inventory: RecallInventory, evidence: LocalEvidence)
object Open { def fromProduct(p: Product): Open = summon[scala.deriving.Mirror.ProductOf[Open]].fromProduct(p) }
Open.fromProduct(???)"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
CaptionScoring.Produced.fromProduct(???)""").nonEmpty)
  }

  test("CaptionScoring.Produced Mirror refuses with matching positive control") {
    assertEquals(
      typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
case class Open(session: CaptionScoring.Session, config: StrictCandidateConfig, inventory: RecallInventory, evidence: LocalEvidence)
summon[scala.deriving.Mirror.ProductOf[Open]]"""),
      Nil
    )
    assert(typeCheckErrors("""import storymodel4s.align.*
import storymodel4s.core.*
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked
summon[scala.deriving.Mirror.ProductOf[CaptionScoring.Produced]]""").nonEmpty)
  }
