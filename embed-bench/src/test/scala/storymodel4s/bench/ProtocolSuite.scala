package storymodel4s.bench

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import munit.FunSuite

/** The pinned protocol checksum must equal the document in the repository: an edit of the protocol
  * without a bench revision is exactly the drift the FREEZE rule exists to catch.
  */
class ProtocolSuite extends FunSuite:
  private def repoRoot: Option[Path] =
    Iterator
      .iterate(Paths.get(sys.props("user.dir")).toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.exists(p.resolve(ProtocolDocument.path)))

  test("pinned checksum equals the canonical text of the protocol document at this revision") {
    val root =
      repoRoot.getOrElse(fail(s"${ProtocolDocument.path} not found above ${sys.props("user.dir")}"))
    val text =
      new String(Files.readAllBytes(root.resolve(ProtocolDocument.path)), StandardCharsets.UTF_8)
    val current = ProtocolDocument.checksumOf(text)
    assertEquals(
      current,
      ProtocolDocument.pinned,
      s"protocol document drifted: recompute ProtocolDocument.pinned = ${current.hex}"
    )
  }

  test("the checksum is over canonical text: line endings and trailing spaces do not matter") {
    val a = ProtocolDocument.checksumOf("# rule\n\nbody\n")
    val b = ProtocolDocument.checksumOf("# rule   \r\n\r\nbody\r\n\r\n")
    assertEquals(a, b)
    assertNotEquals(a, ProtocolDocument.checksumOf("# rule\n\nbody, edited\n"))
  }
