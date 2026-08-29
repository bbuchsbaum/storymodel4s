package storymodel4s.codecprobe

import scala.compiletime.testing.typeCheckErrors

import munit.FunSuite

/** Probes from OUTSIDE `storymodel4s.codec`.
  *
  * Demonstrated forge (closed by this slice). Before the three witnesses became plain classes, each
  * of these compiled from any package and minted a witness that witnessed nothing:
  *
  * {{{
  * summon[scala.deriving.Mirror.ProductOf[storymodel4s.codec.SidecarBlockRange]]
  *   .fromProduct((0, 0, 1, 80L, 4L))
  * summon[scala.deriving.Mirror.ProductOf[storymodel4s.codec.CheckedSidecarPrelude]]
  *   .fromProduct(layout *: Vector.empty[storymodel4s.core.Checksum] *: EmptyTuple)
  * summon[scala.deriving.Mirror.ProductOf[storymodel4s.codec.CheckedSidecarBlock]]
  *   .fromProduct(range *: Vector(Vector(1.0)) *: EmptyTuple)
  * }}}
  *
  * `CheckedSidecarPrelude` also exposed `blockDigests` as public `_2()`.
  */
class ConstructionProbeSuite extends FunSuite:

  private def refused(errors: List[scala.compiletime.testing.Error], what: String): Unit =
    assert(errors.nonEmpty, s"$what must not be constructible outside storymodel4s.codec")

  test("positive control: compiletime testing reports a real error") {
    val errors = typeCheckErrors("val x: Int = \"not-an-int\"")
    assert(errors.nonEmpty, "positive control must fail or negative assertions are vacuous")
  }

  test("positive control: public sidecar factories remain visible") {
    assert(
      typeCheckErrors(
        """(
             manifest: storymodel4s.features.SidecarManifest,
             bytes: Array[Byte]
           ) => storymodel4s.codec.SidecarCodec.validateBlockedPrelude(manifest, bytes)"""
      ).isEmpty
    )
    assert(
      typeCheckErrors(
        """(
             prelude: storymodel4s.codec.CheckedSidecarPrelude,
             block: Int,
             bytes: Array[Byte]
           ) => storymodel4s.codec.SidecarCodec.validateBlockedBlock(prelude, block, bytes)"""
      ).isEmpty
    )
    assert(typeCheckErrors("storymodel4s.codec.SidecarCodec.Magic").isEmpty)
  }

  test("positive control: public observations remain readable") {
    val reads = List(
      typeCheckErrors(
        """(range: storymodel4s.codec.SidecarBlockRange) =>
             (range.block, range.firstRow, range.rowCount, range.byteOffset, range.byteLength,
              range.rowEndExclusive, range.byteEndExclusive)"""
      ),
      typeCheckErrors(
        """(prelude: storymodel4s.codec.CheckedSidecarPrelude) =>
             (prelude.layout, prelude.blocksForRows(Vector(0)))"""
      ),
      typeCheckErrors(
        """(block: storymodel4s.codec.CheckedSidecarBlock) =>
             (block.range, block.rows, block.row(0))"""
      )
    )
    assert(reads.forall(_.isEmpty), reads.flatten.mkString("\n"))
  }

  test("SidecarBlockRange has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.codec.SidecarBlockRange.fromProduct((0, 0, 1, 80L, 4L))"""
      ),
      "SidecarBlockRange.fromProduct"
    )
  }

  test("CheckedSidecarPrelude has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.codec.CheckedSidecarPrelude.fromProduct(EmptyTuple)"""
      ),
      "CheckedSidecarPrelude.fromProduct"
    )
  }

  test("CheckedSidecarBlock has no derived fromProduct bypass") {
    refused(
      typeCheckErrors(
        """storymodel4s.codec.CheckedSidecarBlock.fromProduct(EmptyTuple)"""
      ),
      "CheckedSidecarBlock.fromProduct"
    )
  }

  test("the three witnesses have no public apply or copy") {
    refused(
      typeCheckErrors(
        """storymodel4s.codec.SidecarBlockRange(0, 0, 1, 80L, 4L)"""
      ),
      "SidecarBlockRange.apply"
    )
    refused(
      typeCheckErrors(
        """(range: storymodel4s.codec.SidecarBlockRange) =>
             storymodel4s.codec.CheckedSidecarBlock(range, Vector(Vector(1.0)))"""
      ),
      "CheckedSidecarBlock.apply"
    )
    refused(
      typeCheckErrors(
        """(block: storymodel4s.codec.CheckedSidecarBlock) =>
             block.copy(rows = Vector.empty)"""
      ),
      "CheckedSidecarBlock.copy"
    )
  }

  test("CheckedSidecarPrelude does not expose blockDigests as a public Product slot") {
    refused(
      typeCheckErrors(
        """(prelude: storymodel4s.codec.CheckedSidecarPrelude) => prelude.blockDigests"""
      ),
      "CheckedSidecarPrelude.blockDigests"
    )
    refused(
      typeCheckErrors(
        """(prelude: storymodel4s.codec.CheckedSidecarPrelude) => prelude._2"""
      ),
      "CheckedSidecarPrelude._2"
    )
  }
