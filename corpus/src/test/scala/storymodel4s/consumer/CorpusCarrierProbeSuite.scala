package storymodel4s.consumer

import scala.compiletime.testing.typeCheckErrors

import storymodel4s.corpus.*

import munit.FunSuite

/** Out-of-package proof that the intake carriers cannot be forged (ADR 0018 §2).
  *
  * This suite is deliberately NOT in `storymodel4s.corpus`. `private[corpus]` admits the whole
  * `storymodel4s.corpus.*` tree, so a probe inside that tree cannot tell a closed door from an open
  * one — it would have access either way.
  *
  * Every negative assertion is paired with a POSITIVE CONTROL in the same file and scope. A bare
  * `typeCheckErrors(...).nonEmpty` passes on *any* compile error, including a typo in the probe
  * itself or a package that does not resolve, so without a control that compiles, a probe can pass
  * for a reason unrelated to the door it claims to test. That has already happened twice in this
  * repository (`docs/design/unforgeable-types.md`).
  */
class CorpusCarrierProbeSuite extends FunSuite:

  /** Real, typed references to every probed type. This method is never called; it exists so that
    * Zinc records a dependency from this file on those types.
    *
    * Without it, `typeCheckErrors` expands to a literal list, Zinc sees no dependency, and this
    * suite is NOT recompiled when a probed type's shape or visibility changes -- so a mutation
    * silently SURVIVES unless the test scope is cleaned first. Measured: mutating
    * `final class Known` to `final case class Known` leaves this suite green on `corpusJVM/test`
    * and red only on `corpusJVM/clean corpusJVM/test`. "Always clean" is a workaround; this is the
    * fix.
    */
  private def zincAnchor(
      c: SourceCoordinate,
      r: Raw[Int],
      k: Known[Int],
      u: Unmapped,
      a: Absent,
      n: NotApplicable,
      d: Undetermined,
      v: Verified,
      va: VerifiedArtifact,
      m: SourceManifest,
      st: ArtifactStore,
      b: CodeBook[Int, String],
      rc: RowContext,
      cd: Coded[Int],
      ap: Applicability,
      ar: ArtifactRecord,
      rr: RecordRef
  ): Int =
    c.row + r.value + k.raw.value + u.raw.literal.length + a.raw.literal.length +
      n.condition.render.length + d.condition.render.length + v.artifacts.size + va.byteLength +
      m.declaredIds.size + SourceManifest.SchemaVersion + st.list.size + b.size +
      rc.columns.size + cd.at.row + ap.render.length + ar.role.length + rr.schemaVersion

  test("positive control: the public surface this suite names does resolve") {
    assertEquals(
      typeCheckErrors("""storymodel4s.corpus.ArtifactId.unsafe("a.xlsx")"""),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) => (c.row, c.column, c.toString)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors("""(r: storymodel4s.corpus.Raw[Int]) => (r.value, r.literal, r.at)"""),
      Nil
    )
  }

  test("a consumer cannot construct a SourceCoordinate") {
    assert(
      typeCheckErrors(
        """new storymodel4s.corpus.SourceCoordinate(
             storymodel4s.corpus.ArtifactId.unsafe("a.xlsx"), "s1", 1, "c")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.corpus.SourceCoordinate.at(
             storymodel4s.corpus.ArtifactId.unsafe("a.xlsx"), "s1", 1, "c")"""
      ).nonEmpty
    )
  }

  test("a consumer cannot reach a SourceCoordinate through Mirror or copy") {
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.SourceCoordinate]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) => c.copy()"""
      ).nonEmpty
    )
  }

  /** The joined claim is the thing being protected: that THIS value is what THIS literal decoded
    * to, at THIS place. A consumer holding a genuine coordinate must not be able to re-use it under
    * a value the source never carried.
    */
  test("a consumer cannot re-join a genuine coordinate to a fabricated value") {
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) =>
             new storymodel4s.corpus.Raw[Int](99, c, "anything")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(c: storymodel4s.corpus.SourceCoordinate) =>
             storymodel4s.corpus.Raw.of[Int](99, c, "anything")"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[Int]) => r.copy(value = 99)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.Raw[Int]]]"""
      ).nonEmpty
    )
  }

  /** The status IS the claim: that this literal decoded to this value under this code book, or that
    * the column did not apply here. A consumer that can mint a `Known` can assert a decode that
    * never happened while keeping a genuine coordinate, which is precisely the forgery the carrier
    * exists to prevent. An earlier revision shipped these as `enum` cases — i.e. case classes — on
    * the judgement that a forgeable status was harmless.
    */
  test("positive control: the Coded surface this suite names does resolve") {
    assertEquals(
      typeCheckErrors(
        """(c: storymodel4s.corpus.Coded[Int]) => c.at"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(k: storymodel4s.corpus.Known[Int]) => (k.raw, k.book)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """storymodel4s.corpus.Applicability.WhenColumnEquals("RecallType", "1").render"""
      ),
      Nil
    )
  }

  test("a consumer cannot fabricate a decode status") {
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[Int]) =>
             new storymodel4s.corpus.Known[Int](r, storymodel4s.corpus.CodeBookId.unsafe("b"))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[String]) =>
             new storymodel4s.corpus.Unmapped(r, storymodel4s.corpus.CodeBookId.unsafe("b"))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[String]) => new storymodel4s.corpus.Absent(r)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(r: storymodel4s.corpus.Raw[String]) =>
             new storymodel4s.corpus.NotApplicable(r, storymodel4s.corpus.Applicability.Always)"""
      ).nonEmpty
    )
  }

  test("a consumer cannot reach a decode status through Mirror or copy") {
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.Known[Int]]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.NotApplicable]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(k: storymodel4s.corpus.Known[Int]) => k.copy()"""
      ).nonEmpty
    )
  }

  test("a consumer cannot mint a code book, a row context, or run the decoder") {
    assert(
      typeCheckErrors(
        """storymodel4s.corpus.CodeBook.of(
             storymodel4s.corpus.CodeBookId.unsafe("b"),
             storymodel4s.corpus.Citation("s", "l"),
             Map(1 -> "x"),
             storymodel4s.corpus.Applicability.Always)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """storymodel4s.corpus.RowContext.of(Map("RecallType" -> "1"))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(b: storymodel4s.corpus.CodeBook[Int, String], l: storymodel4s.corpus.Raw[String],
             r: storymodel4s.corpus.RowContext) =>
             storymodel4s.corpus.Coded.decode(b, None, l, r)"""
      ).nonEmpty
    )
  }

  test("positive control: the Verified surface this suite names does resolve") {
    assertEquals(
      typeCheckErrors("""(v: storymodel4s.corpus.Verified) => (v.manifest, v.artifacts)"""),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(a: storymodel4s.corpus.VerifiedArtifact) => (a.id, a.checksum, a.byteLength)"""
      ),
      Nil
    )
    assertEquals(
      typeCheckErrors(
        """(m: storymodel4s.corpus.SourceManifest, s: storymodel4s.corpus.ArtifactStore) =>
             storymodel4s.corpus.Verify.verify(m, s)"""
      ),
      Nil
    )
  }

  /** Guarantee 1. A consumer that can mint a `Verified` can claim bytes were hashed that were not,
    * which is the whole of the first wall.
    */
  test("a consumer cannot mint a Verified or a VerifiedArtifact") {
    assert(
      typeCheckErrors(
        """(m: storymodel4s.corpus.SourceManifest) => new storymodel4s.corpus.Verified(m, Vector.empty)"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """new storymodel4s.corpus.VerifiedArtifact(
             storymodel4s.corpus.ArtifactId.unsafe("a"),
             IArray.empty[Byte],
             storymodel4s.core.Checksum.ofText("x"))"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """summon[scala.deriving.Mirror.ProductOf[storymodel4s.corpus.Verified]]"""
      ).nonEmpty
    )
    assert(
      typeCheckErrors(
        """(v: storymodel4s.corpus.Verified) => v.copy()"""
      ).nonEmpty
    )
  }

  test("a consumer cannot build a SourceManifest around bytes nobody hashed") {
    assert(
      typeCheckErrors(
        """storymodel4s.corpus.SourceManifest.of(
             storymodel4s.corpus.CorpusId.unsafe("c"), Vector.empty, Vector.empty,
             storymodel4s.corpus.AdmissionStatus(
               storymodel4s.corpus.AdmissionState.Proposed, false, Vector.empty),
             storymodel4s.corpus.ContentPolicy(false, false, Vector.empty),
             Vector.empty, Map.empty)"""
      ).nonEmpty
    )
  }

  /** The verified bytes are an IArray, so there is no write path at all -- not merely a private
    * one. This is the type-level half of the two aliasing routes; the copy-before-hash half is
    * proved behaviourally in VerifySuite.
    */
  /** There is no accessor that returns the backing array at all. An earlier version exposed one as
    * an `IArray[Byte]` and claimed it was immutable; the standard library hands the backing array
    * straight back from an `IArray`, so that claim was false and a verified payload could be
    * rewritten after verification while its checksum went on vouching for the original.
    */
  test("a consumer cannot reach the verified bytes, by any accessor") {
    assert(typeCheckErrors("""(a: storymodel4s.corpus.VerifiedArtifact) => a.bytes""").nonEmpty)
    assert(typeCheckErrors("""(a: storymodel4s.corpus.VerifiedArtifact) => a.payload""").nonEmpty)
    // what IS offered hands out copies and reads, never the array
    assertEquals(
      typeCheckErrors(
        """(a: storymodel4s.corpus.VerifiedArtifact) => (a.toArray, a.iterator, a.byteAt(0))"""
      ),
      Nil
    )
  }
