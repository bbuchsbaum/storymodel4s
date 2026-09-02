package storymodel4s.document

import java.nio.file.{Files, Path, Paths}
import munit.FunSuite

/** Static court: the provider constructs nothing through a document-private compiler factory.
  *
  * Why static: `ChartProposalProvider` lives in `document` because the proposal ADTs live here, so
  * the compiler's `private[document]` doors (`DerivationReceipt.of`, `NarrativeCompilation.of`) are
  * in scope for it. A green suite cannot see a door that is merely reachable, so this court reads
  * the source and refuses the names. JVM-only because it reads files.
  */
class ChartProposalStaticCourtSuite extends FunSuite:
  private val documentMain: Path =
    Paths.get("document", "src", "main", "scala", "storymodel4s", "document")
  private val providerFile = "propose.scala"

  private def locate(relative: Path): Path =
    val start = Paths.get(sys.props("user.dir")).toAbsolutePath
    Iterator
      .iterate(start)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.isDirectory(_))
      .getOrElse(fail(s"cannot locate $relative above $start"))

  private lazy val mainDir: Path = locate(documentMain)
  private lazy val providerText: String = Files.readString(mainDir.resolve(providerFile))

  /** Qualified factory names the provider must never spell. */
  private val forbiddenQualified: Vector[String] = Vector(
    "DerivationReceipt.of",
    "NarrativeCompilation.of",
    "new DerivationReceipt",
    "new NarrativeCompilation"
  )

  /** Shapes that reach the same doors without spelling them: an import of the companion's members
    * (`import NarrativeCompilation.*`, `.{of => build}`, `._`) or a member selection through
    * whitespace or a selector block.
    */
  private val forbiddenShapes: Vector[scala.util.matching.Regex] = Vector(
    """import\s+(?:[\w.]+\.)?(?:DerivationReceipt|NarrativeCompilation)\s*\.""".r,
    """(?:DerivationReceipt|NarrativeCompilation)\s*\.\s*(?:\{|\*|_|of\b)""".r
  )

  private val privateMember = """private\[document\] (?:def|val) (\w+)""".r

  /** Every `private[document]` member name declared in the module outside the provider. */
  private lazy val privateNames: Vector[String] =
    val files = Files
      .list(mainDir)
      .toArray
      .map(_.asInstanceOf[Path])
      .filter(p => p.getFileName.toString.endsWith(".scala"))
      .filterNot(_.getFileName.toString == providerFile)
      .toVector
    files
      .flatMap(p => privateMember.findAllMatchIn(Files.readString(p)).map(_.group(1)).toVector)
      .distinct
      .sorted

  private def qualifiedHits(text: String): Vector[String] =
    forbiddenQualified.filter(text.contains) ++
      forbiddenShapes.flatMap(_.findAllMatchIn(text).map(_.matched).toVector)

  /** Unqualified `.name(` references to private members whose name is not the overloaded `of`. */
  private def memberHits(text: String, names: Vector[String]): Vector[String] =
    names.filterNot(_ == "of").filter(name => text.contains(s".$name("))

  test("the provider names no document-private compiler factory") {
    assert(
      providerText.contains("object ChartProposalProvider"),
      "scanned file is not the provider"
    )
    assertEquals(qualifiedHits(providerText), Vector.empty)
  }

  test("the provider references no other document-private member either") {
    assert(
      privateNames.contains("of"),
      s"expected the compiler's private `of` doors in $privateNames"
    )
    assert(privateNames.size >= 3, s"suspiciously few private members: $privateNames")
    assertEquals(memberHits(providerText, privateNames), Vector.empty)
  }

  test("the scan finds a planted factory name (positive control)") {
    val planted = "val receipt = DerivationReceipt.of(candidateSet, attempts, emitted, gaps)"
    assertEquals(qualifiedHits(planted), Vector("DerivationReceipt.of", "DerivationReceipt.of"))
    assertEquals(
      qualifiedHits("NarrativeCompilation.of(x)"),
      Vector("NarrativeCompilation.of", "NarrativeCompilation.of")
    )
    assertEquals(
      qualifiedHits("import storymodel4s.document.NarrativeCompilation.*"),
      Vector("import storymodel4s.document.NarrativeCompilation.", "NarrativeCompilation.*")
    )
    assertEquals(
      qualifiedHits("import NarrativeCompilation.{of => build}"),
      Vector("import NarrativeCompilation.", "NarrativeCompilation.{")
    )
    assertEquals(qualifiedHits("DerivationReceipt . of(x)"), Vector("DerivationReceipt . of"))
    assertEquals(qualifiedHits("DerivationReceipt._"), Vector("DerivationReceipt._"))
    assertEquals(
      memberHits("NarrativeCompiler.renderProviderCall(call)", Vector("renderProviderCall", "of")),
      Vector("renderProviderCall")
    )
  }
