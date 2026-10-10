package storymodel4s.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors

/** External callers cannot manufacture checked imported inspection packets. */
class SurfaceWorkspaceBoundarySuite extends FunSuite:

  test("Policy constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(id: ArtifactId, bytes: String, document: Json, claimedRecordDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; new Policy(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("Policy apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(id: ArtifactId, bytes: String, document: Json, claimedRecordDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; Policy(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum)"""
      ).nonEmpty
    )
  }

  test("Policy copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(id: ArtifactId, bytes: String, document: Json, claimedRecordDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; (??? : Policy).copy()"""
      ).nonEmpty
    )
  }

  test("Policy fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(id: ArtifactId, bytes: String, document: Json, claimedRecordDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple4(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; Policy.fromProduct(Tuple4(??? : ArtifactId, ??? : String, ??? : Json, ??? : Checksum))"""
      ).nonEmpty
    )
  }

  test("Policy mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(id: ArtifactId, bytes: String, document: Json, claimedRecordDigest: Checksum); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[Policy]]"""
      ).nonEmpty
    )
  }

  test("Investigation constructor refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(archive: WorkspaceArchive, sourceArtifact: String, sourceKind: SourceKind, view: SourceView, source: SourceRepresentation, narrativeModel: Option[TextModel[ModelStatus.Validated]], recall: RecallGraph[RecallGraphStatus.Checked], inventory: RecallInventory, policies: Vector[Policy], disclosureDescription: Json, producerRevision: String, canonicalArchive: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; new Control(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; new Investigation(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String)"""
      ).nonEmpty
    )
  }

  test("Investigation apply refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(archive: WorkspaceArchive, sourceArtifact: String, sourceKind: SourceKind, view: SourceView, source: SourceRepresentation, narrativeModel: Option[TextModel[ModelStatus.Validated]], recall: RecallGraph[RecallGraphStatus.Checked], inventory: RecallInventory, policies: Vector[Policy], disclosureDescription: Json, producerRevision: String, canonicalArchive: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String)"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; Investigation(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String)"""
      ).nonEmpty
    )
  }

  test("Investigation copy refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(archive: WorkspaceArchive, sourceArtifact: String, sourceKind: SourceKind, view: SourceView, source: SourceRepresentation, narrativeModel: Option[TextModel[ModelStatus.Validated]], recall: RecallGraph[RecallGraphStatus.Checked], inventory: RecallInventory, policies: Vector[Policy], disclosureDescription: Json, producerRevision: String, canonicalArchive: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; (??? : Control).copy()"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; (??? : Investigation).copy()"""
      ).nonEmpty
    )
  }

  test("Investigation fromProduct refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(archive: WorkspaceArchive, sourceArtifact: String, sourceKind: SourceKind, view: SourceView, source: SourceRepresentation, narrativeModel: Option[TextModel[ModelStatus.Validated]], recall: RecallGraph[RecallGraphStatus.Checked], inventory: RecallInventory, policies: Vector[Policy], disclosureDescription: Json, producerRevision: String, canonicalArchive: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; Control.fromProduct(Tuple12(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String))"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; Investigation.fromProduct(Tuple12(??? : WorkspaceArchive, ??? : String, ??? : SourceKind, ??? : SourceView, ??? : SourceRepresentation, ??? : Option[TextModel[ModelStatus.Validated]], ??? : RecallGraph[RecallGraphStatus.Checked], ??? : RecallInventory, ??? : Vector[Policy], ??? : Json, ??? : String, ??? : String))"""
      ).nonEmpty
    )
  }

  test("Investigation mirror refuses with same-shape control") {
    assertEquals(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; case class Control(archive: WorkspaceArchive, sourceArtifact: String, sourceKind: SourceKind, view: SourceView, source: SourceRepresentation, narrativeModel: Option[TextModel[ModelStatus.Validated]], recall: RecallGraph[RecallGraphStatus.Checked], inventory: RecallInventory, policies: Vector[Policy], disclosureDescription: Json, producerRevision: String, canonicalArchive: String); object Control { def fromProduct(p: Product): Control = summon[scala.deriving.Mirror.ProductOf[Control]].fromProduct(p) }; summon[scala.deriving.Mirror.ProductOf[Control]]"""
      ),
      Nil
    )
    assert(
      typeCheckErrors(
        """import storymodel4s.codec.*; import storymodel4s.codec.SurfaceWorkspaceCodecs.*; import storymodel4s.align.*; import storymodel4s.core.{Checksum}; import storymodel4s.view.ArtifactId; import storymodel4s.recall.*; import storymodel4s.story.*; import io.circe.Json; summon[scala.deriving.Mirror.ProductOf[Investigation]]"""
      ).nonEmpty
    )
  }
