package storymodel4s.corpus.intake

import java.nio.file.{Files, Path}

import storymodel4s.corpus.*

/** A real run of the intake contract over the Friends source set.
  *
  * Deliberately a runnable main and NOT a test: the bytes live in the git-ignored data root, the
  * source set's admission state is `proposed`, and a test that required them would be red in CI and
  * on any machine without the staged files.
  *
  * Everything it prints is CONTENT-FREE -- counts, ranges and checksums. `Explanation` and `Place`
  * are stimulus prose and never leave the data root, so the receipt reports how many distinct
  * values a column had, never what they were.
  *
  * Usage:
  * `corpusIntake/runMain storymodel4s.corpus.intake.runFriendsIntake [DATA_ROOT] [DOCS_ROOT]`
  */
object FriendsIntake:

  private val Storyboard = ArtifactId.unsafe("friendsStoryBoard.xlsx")
  private val NarrComb = "FriendsNarrComb"

  /** The measured reading of the main storyboard. Every encoding is declared, none inferred. */
  val storyboardBinding: SheetBinding = SheetBinding(
    headerRow = 1,
    columns = Map(
      "Episode" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "EventModelNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      // Excel 1900 serials: 1.000439814814815 is 38 seconds, not a number near one
      "TimeOrig" -> ColumnBinding(CellEncoding.ExcelSerialDays),
      "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays),
      "SceneNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "Place" -> ColumnBinding(CellEncoding.PlainText),
      "EventModel1" -> ColumnBinding(CellEncoding.PlainText)
    )
  )

  def profile: Either[ProfileRefusal, CorpusProfile] =
    CorpusProfile.of(
      ProfileId.unsafe("friends.storyboard.v1"),
      1,
      Map((Storyboard, NarrComb) -> storyboardBinding)
    )

  def run(dataRoot: Path, docsRoot: Path): Either[String, Vector[String]] =
    val friendsData = dataRoot.resolve("friends")
    val legacy = docsRoot.resolve("friends").resolve("source-manifest.json")
    for
      _ <- Either.cond(Files.exists(friendsData), (), s"no staged bytes at $friendsData")
      _ <- Either.cond(Files.exists(legacy), (), s"no source manifest at $legacy")
      json <- Right(Files.readString(legacy))
      manifest <- LegacyManifest
        .liftArtifactsArray(CorpusId.unsafe("friends"), json)
        .left
        .map(r => s"migration refused: ${r.message}")
      store <- FileStore.at(friendsData).left.map(r => s"store refused: ${r.message}")
      verified <- Verify
        .verify(manifest, store)
        .left
        .map(fs => s"verification refused:\n  " + fs.toVector.map(_.message).mkString("\n  "))
      prof <- profile.left.map(_.message)
      opened <- CorpusReader.open(verified, prof).left.map(_.message)
    yield receipt(verified, prof, opened)

  private def receipt(
      verified: Verified,
      prof: CorpusProfile,
      opened: CorpusReader.OpenCorpus
  ): Vector[String] =
    val sheet = opened.sheet(Storyboard, NarrComb)
    val rows = sheet.map(_.rows).getOrElse(Vector.empty)
    def distinct(col: String) = rows
      .flatMap(_.context.valueOf(col) match
        case ColumnValue.Value(v) => Some(v)
        case _                    => None)
      .distinct
    val times = distinct("Time").flatMap(_.toLongOption).sorted
    Vector(
      s"corpus                : ${verified.manifest.corpus.value}",
      s"admission             : ${verified.manifest.admission.state.render} " +
        s"(court opened: ${verified.manifest.admission.courtOpened})",
      s"migrated from         : ${verified.manifest.extensions.getOrElse("migratedFrom", "?")}",
      s"artifacts verified    : ${verified.artifacts.size} of " +
        s"${verified.manifest.artifacts.size} declared",
      s"total bytes hashed    : ${verified.artifacts.map(_.byteLength.toLong).sum}",
      s"profile               : ${prof.id.value} v${prof.version} ${prof.identity.short()}",
      s"sheet                 : $NarrComb",
      s"rows read             : ${rows.size}",
      s"distinct EventModelNum: ${distinct("EventModelNum").size}",
      s"distinct SceneNum     : ${distinct("SceneNum").size}",
      s"distinct Episode      : ${distinct("Episode").size}",
      s"distinct storylines   : ${distinct("EventModel1").size}",
      s"distinct places       : ${distinct("Place").size}",
      s"Time range (s)        : ${times.headOption.getOrElse(-1L)}..${times.lastOption.getOrElse(-1L)}",
      s"TimeOrig max (s)      : ${distinct("TimeOrig").flatMap(_.toLongOption).maxOption.getOrElse(-1L)}",
      "",
      "No column value appears above. Explanation and Place are stimulus prose and stay in the",
      "data root; this receipt reports cardinalities and ranges only."
    )

@main def runFriendsIntake(args: String*): Unit =
  val data = Path.of(args.headOption.getOrElse("data"))
  val docs = Path.of(args.lift(1).getOrElse("docs/data"))
  FriendsIntake.run(data, docs) match
    case Left(err) =>
      println(s"FRIENDS INTAKE REFUSED\n$err")
    case Right(lines) =>
      println("FRIENDS INTAKE RECEIPT")
      lines.foreach(println)
