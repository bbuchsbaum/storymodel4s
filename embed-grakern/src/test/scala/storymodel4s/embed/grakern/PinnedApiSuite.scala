package storymodel4s.embed.grakern

import java.nio.file.{Files, Path, Paths}

import munit.FunSuite

/** The structural channel must only use grakern APIs that exist at the pinned revision
  * (`GrakernPin.revision`). `WLCompiledKernel.cross` is committed there; the single-sample `query`
  * method was uncommitted upstream work and must never be referenced again.
  */
class PinnedApiSuite extends FunSuite:

  private val relative = Paths.get(
    "embed-grakern/src/main/scala/storymodel4s/embed/grakern/distance.scala"
  )

  private def source: String =
    val candidates = Vector(relative, Paths.get("..").resolve(relative))
    candidates.find(Files.exists(_)) match
      case Some(p: Path) => Files.readString(p)
      case None          => fail(s"distance.scala not found from ${Paths.get("").toAbsolutePath}")

  test("distance.scala uses WLCompiledKernel.cross, never the uncommitted query method") {
    val text = source
    assert(text.contains(".cross("), "expected a call to WLCompiledKernel.cross")
    assert(!text.contains(".query("), "must not call WLCompiledKernel.query (not at the pin)")
  }

  test("the pin string names the grakern revision the module was verified against") {
    assertEquals(GrakernPin.revision.length, 40)
    assert(GrakernPin.revision.forall(c => c.isDigit || ('a' to 'f').contains(c)))
  }
