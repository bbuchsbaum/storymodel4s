package storymodel4s.pipeline

import java.nio.file.Files
import munit.FunSuite
import storymodel4s.codec.{ExpectedMappingContext, MappingExchange}
import storymodel4s.fixtures.WorkspaceFixtures

class MappingExchangeBuildSuite extends FunSuite:
  private lazy val mapping =
    WorkspaceFixtures.all("a" * 40, includeHistorical = false).head._2.policies.head.record
  test("complete package is checked on read and an existing destination is preserved") {
    val root = Files.createTempDirectory("mapping-exchange-test-")
    val out = root.resolve("package")
    val m = mapping
    assert(MappingExchangeBuild.write(m, out).isRight)
    assertEquals(
      MappingExchangeBuild
        .read(out, ExpectedMappingContext(m.inventory, m.source))
        .toOption
        .get
        .digest,
      m.digest
    )
    val original = Files.readAllBytes(out.resolve("manifest.json")).toVector
    assertEquals(MappingExchangeBuild.write(m, out), Left(MappingExchangeBuild.Error.OutputExists))
    assertEquals(Files.readAllBytes(out.resolve("manifest.json")).toVector, original)
    val files = Files.list(out)
    try files.forEach(p => { Files.delete(p): Unit })
    finally files.close()
    Files.delete(out)
    Files.delete(root)
  }
  test("injected payload failure cannot publish a completed manifest") {
    val root = Files.createTempDirectory("mapping-exchange-failure-")
    val out = root.resolve("package")
    val m = mapping
    val b = MappingExchange.encode(m).toOption.get
    val result = MappingExchangeBuild.publish(
      b,
      out,
      (_, _) => throw new java.io.IOException("injected failure")
    )
    assertEquals(result, Left(MappingExchangeBuild.Error.OutputWrite))
    assert(!Files.exists(out.resolve("manifest.json")))
    assert(MappingExchangeBuild.read(out, ExpectedMappingContext(m.inventory, m.source)).isLeft)
    Files.delete(out)
    Files.delete(root)
  }

  test("partial staging-manifest write leaves no completion marker") {
    val root = Files.createTempDirectory("mapping-exchange-manifest-")
    val out = root.resolve("package")
    val m = mapping
    val b = MappingExchange.encode(m).toOption.get
    val result = MappingExchangeBuild.publish(
      b,
      out,
      writeManifest = (path, bytes) => {
        Files.write(path, bytes.take(20)): Unit
        throw new java.io.IOException("partial manifest write")
      }
    )
    assertEquals(result, Left(MappingExchangeBuild.Error.OutputWrite))
    assert(!Files.exists(out.resolve("manifest.json")))
    assert(MappingExchangeBuild.read(out, ExpectedMappingContext(m.inventory, m.source)).isLeft)
    val files = Files.list(out)
    try files.forEach(p => { Files.delete(p): Unit })
    finally files.close()
    Files.delete(out)
    Files.delete(root)
  }
