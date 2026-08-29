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
    val bad = PromptPackageManifest.of(
      name = " ",
      version = manifest.version,
      role = manifest.role,
      inputSchemaId = manifest.inputSchemaId,
      outputSchemaId = manifest.outputSchemaId,
      permittedOperations = Vector.empty,
      prohibitedInferences = manifest.prohibitedInferences,
      standardsRefs = manifest.standardsRefs,
      exampleIds = manifest.exampleIds,
      counterexampleIds = manifest.counterexampleIds,
      abstentionRules = manifest.abstentionRules,
      selfCheck = Vector.empty,
      benchmarkSuiteId = manifest.benchmarkSuiteId
    )
    bad.fold(errs => assertEquals(errs.length, 3L), _ => fail("valid"))

  test("reserved separators are rejected"):
    assert(
      PromptPackageManifest
        .of(
          manifest.name,
          manifest.version,
          PromptRole.Custom("a\u001fb", "x"),
          manifest.inputSchemaId,
          manifest.outputSchemaId,
          manifest.permittedOperations,
          manifest.prohibitedInferences,
          manifest.standardsRefs,
          manifest.exampleIds,
          manifest.counterexampleIds,
          manifest.abstentionRules,
          manifest.selfCheck,
          manifest.benchmarkSuiteId
        )
        .isInvalid
    )
    assert(
      manifest.replace(abstentionRules = Vector("line\nbreak")).isInvalid
    )

  test("checksum changes with content and a stale ref fails verification"):
    val changed = manifest.replace(version = "1.0.1").toOption.get
    assertNotEquals(changed.checksum, manifest.checksum)
    assert(!PromptPackageManifest.verify(changed, manifest.ref))
    assert(!PromptPackageManifest.verify(manifest, manifest.ref.copy(name = "other")))

  private val clean: Gen[String] = Gen.nonEmptyListOf(Gen.alphaNumChar).map(_.mkString)

  property("canonical form is injective on list boundaries"):
    forAll(clean, clean) { (a, b) =>
      val one = manifest.replace(exampleIds = Vector(a + b)).toOption.get
      val two = manifest.replace(exampleIds = Vector(a, b)).toOption.get
      one.canonicalForm != two.canonicalForm && one.checksum != two.checksum
    }

  property("canonical form is injective across fields"):
    forAll(clean) { (x) =>
      val a = manifest
        .replace(exampleIds = Vector(x), counterexampleIds = Vector.empty)
        .toOption
        .get
      val b = manifest
        .replace(exampleIds = Vector.empty, counterexampleIds = Vector(x))
        .toOption
        .get
      a.checksum != b.checksum
    }

  property("checksum is deterministic"):
    forAll(clean) { (x) =>
      val m = manifest.replace(role = PromptRole.Custom("ns", x.replace(":", ""))).toOption.get
      m.checksum == m.replace().toOption.get.checksum && m.ref == m.ref
    }

  test("custom roles and operations may not contain the rendering separator"):
    assert(manifest.replace(role = PromptRole.Custom("a:b", "x")).isInvalid)
    assert(
      manifest
        .replace(permittedOperations = Vector(PermittedOperation.Custom("ns", "a:b")))
        .isInvalid
    )
    assert(manifest.replace(role = PromptRole.Critic(CriticFamily.FrameRole)).isValid)
