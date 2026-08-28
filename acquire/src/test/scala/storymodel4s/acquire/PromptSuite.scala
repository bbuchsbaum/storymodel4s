package storymodel4s.acquire

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class PromptSuite extends ScalaCheckSuite:
  import Fixtures.*

  test("a complete manifest validates and verifies against its own ref"):
    assert(PromptPackageManifest.validate(manifest).isValid)
    assert(PromptPackageManifest.verify(manifest, manifest.ref))

  test("missing required fields are all reported"):
    val bad =
      manifest.copy(name = " ", permittedOperations = Vector.empty, selfCheck = Vector.empty)
    PromptPackageManifest
      .validate(bad)
      .fold(errs => assertEquals(errs.length, 3L), _ => fail("valid"))

  test("reserved separators are rejected"):
    val bad = manifest.copy(role = "a\u001fb")
    assert(PromptPackageManifest.validate(bad).isInvalid)
    val nl = manifest.copy(abstentionRules = Vector("line\nbreak"))
    assert(PromptPackageManifest.validate(nl).isInvalid)

  test("checksum changes with content and a stale ref fails verification"):
    val changed = manifest.copy(version = "1.0.1")
    assertNotEquals(changed.checksum, manifest.checksum)
    assert(!PromptPackageManifest.verify(changed, manifest.ref))
    assert(!PromptPackageManifest.verify(manifest, manifest.ref.copy(name = "other")))

  private val clean: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  property("canonical form is injective on list boundaries"):
    forAll(clean, clean) { (a, b) =>
      val one = manifest.copy(exampleIds = Vector(a + b))
      val two = manifest.copy(exampleIds = Vector(a, b))
      one.canonicalForm != two.canonicalForm && one.checksum != two.checksum
    }

  property("canonical form is injective across fields"):
    forAll(clean) { (x) =>
      val a = manifest.copy(exampleIds = Vector(x), counterexampleIds = Vector.empty)
      val b = manifest.copy(exampleIds = Vector.empty, counterexampleIds = Vector(x))
      a.checksum != b.checksum
    }

  property("checksum is deterministic"):
    forAll(clean) { (x) =>
      val m = manifest.copy(role = x)
      m.checksum == m.copy().checksum && m.ref == m.ref
    }
