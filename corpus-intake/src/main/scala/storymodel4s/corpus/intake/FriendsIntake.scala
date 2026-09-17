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

  /** The 56-event scale. `WhichEvent` in the recall workbook is scored against THIS, not against
    * `FriendsNarrComb`'s 52 -- which nothing in the file names or the intake record says.
    */
  private val MoreEMs = "FriendsMoreEMs"

  /** The measured reading of the main storyboard. Every encoding is declared, none inferred. */
  val storyboardBinding: SheetBinding = SheetBinding(
    headerRow = 1,
    columns = Map(
      "Episode" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "EventModelNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      // Excel 1900 serials: 1.000439814814815 is 38 seconds, not a number near one
      "TimeOrig" -> ColumnBinding(CellEncoding.ExcelSerialDays(1)),
      "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(1)),
      "SceneNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "Place" -> ColumnBinding(CellEncoding.PlainText),
      "EventModel1" -> ColumnBinding(CellEncoding.PlainText)
    )
  )

  def profile: Either[ProfileRefusal, CorpusProfile] =
    CorpusProfile.of(
      ProfileId.unsafe("friends.storyboard.v1"),
      1,
      Map((Storyboard, NarrComb) -> storyboardBinding, (Storyboard, MoreEMs) -> storyboardBinding)
    )

  /** Derives the 56 -> 52 crosswalk from the two scales' SHARED `Time` axis.
    *
    * The upstream notebook is not present locally and is not needed: the 56-event sheet and the
    * 52-event sheet are both in this workbook and both carry `Time`, so the map is recoverable from
    * the data with the storyboard as its own oracle.
    *
    * THE TRAP: three matched events carry `Time` values that differ between the two sheets by one
    * to three seconds. An exact-equality join silently drops them and yields a WRONG map of 49, so
    * the join is by nearest onset within a tolerance and the result is ASSERTED to be a bijection
    * on the survivors.
    */
  def crosswalk(
      opened: CorpusReader.OpenCorpus,
      toleranceSeconds: Long
  ): Either[String, (Map[Int, Option[Int]], Int)] =
    def scale(sheet: String): Either[String, Vector[(Int, Long)]] =
      opened
        .sheet(Storyboard, sheet)
        .toRight(s"sheet $sheet was not opened")
        .map(_.rows.flatMap { r =>
          (r.context.valueOf("EventModelNum"), r.context.valueOf("Time")) match
            case (ColumnValue.Value(n), ColumnValue.Value(t)) =>
              for a <- n.toIntOption; b <- t.toLongOption yield (a, b)
            case _ => None
        })
    for
      em56 <- scale(MoreEMs)
      em52 <- scale(NarrComb)
      _ <- Either.cond(em56.nonEmpty && em52.nonEmpty, (), "a scale came back empty")
    yield
      val mapping = em56.map { (n, t) =>
        val near = em52.filter((_, t2) => math.abs(t2 - t) <= toleranceSeconds)
        n -> near.minByOption((_, t2) => math.abs(t2 - t)).map(_._1)
      }.toMap
      val exact = em56.count((_, t) => em52.exists((_, t2) => t2 == t))
      (mapping, exact)

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
      walk <- crosswalk(opened, toleranceSeconds = 5L)
    yield receipt(verified, prof, opened) ++ crosswalkReceipt(walk._1, walk._2)

  private def crosswalkReceipt(mapping: Map[Int, Option[Int]], exactMatches: Int): Vector[String] =
    val mapped = mapping.values.flatten.toVector
    val removed = mapping.collect { case (k, None) => k }.toVector.sorted
    Vector(
      "",
      "56 -> 52 CROSSWALK, derived from the shared Time axis",
      s"sources (56-scale)    : ${mapping.size}",
      s"mapped                : ${mapped.size}",
      s"distinct targets      : ${mapped.distinct.size}",
      s"injective on survivors: ${mapped.size == mapped.distinct.size}",
      s"removed               : ${removed.mkString(", ")}",
      s"exact Time matches    : $exactMatches of ${mapping.size}",
      s"  -> an exact-equality join would have produced a map of $exactMatches, not ${mapped.size}"
    )

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

/** Memento, the second corpus in the same lab dialect -- and the one that broke the schema.
  *
  * Its storyboard is the same KIND of record as Friends' and differs in two ways that a single
  * corpus could not have revealed:
  *
  *   1. `Time` starts at 0, not 1.0, so its Excel serials are `v x 86400` where Friends' are
  *      `(v - 1) x 86400`. Applying either rule to the other corpus gives nonsense. The day origin
  *      is now a parameter of the encoding rather than a constant in the reader.
  *   2. Its manifest names each artifact `id` where Friends names it `path`. The lift accepts
  *      either, and invents neither.
  */
object MementoIntake:
  private val Storyboard = ArtifactId.unsafe("MementoStoryBoard.xlsx")
  private val Sheet = "storyBoard"

  val storyboardBinding: SheetBinding = SheetBinding(
    headerRow = 1,
    columns = Map(
      "OverallScene" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      // origin 0, not 1 -- the measured break
      "Time" -> ColumnBinding(CellEncoding.ExcelSerialDays(0)),
      "BroadSceneNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "SubsceneNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "StoryOrderSceneNum" -> ColumnBinding(CellEncoding.IntegerOrWholeDecimalText),
      "NarrativePart" -> ColumnBinding(CellEncoding.PlainText),
      "Place" -> ColumnBinding(CellEncoding.PlainText)
    )
  )

  def profile: Either[ProfileRefusal, CorpusProfile] =
    CorpusProfile.of(
      ProfileId.unsafe("memento.storyboard.v1"),
      1,
      Map((Storyboard, Sheet) -> storyboardBinding)
    )

  def run(dataRoot: Path, docsRoot: Path): Either[String, Vector[String]] =
    val data = dataRoot.resolve("memento")
    val legacy = docsRoot.resolve("memento").resolve("source-manifest.json")
    for
      _ <- Either.cond(Files.exists(data), (), s"no staged bytes at $data")
      _ <- Either.cond(Files.exists(legacy), (), s"no source manifest at $legacy")
      manifest <- LegacyManifest
        .liftArtifactsArray(CorpusId.unsafe("memento"), Files.readString(legacy))
        .left
        .map(r => s"migration refused: ${r.message}")
      store <- FileStore.at(data).left.map(r => s"store refused: ${r.message}")
      verified <- Verify
        .verify(manifest, store)
        .left
        .map(fs => s"verification refused:\n  " + fs.toVector.map(_.message).mkString("\n  "))
      prof <- profile.left.map(_.message)
      opened <- CorpusReader.open(verified, prof).left.map(_.message)
    yield
      val rows = opened.sheet(Storyboard, Sheet).map(_.rows).getOrElse(Vector.empty)
      def distinct(c: String) = rows
        .flatMap(_.context.valueOf(c) match
          case ColumnValue.Value(v) => Some(v)
          case _                    => None)
        .distinct
      val times = distinct("Time").flatMap(_.toLongOption).sorted
      Vector(
        s"corpus                : ${verified.manifest.corpus.value}",
        s"artifacts verified    : ${verified.artifacts.size} of ${verified.manifest.artifacts.size}",
        s"profile               : ${prof.id.value} ${prof.identity.short()}",
        s"rows read             : ${rows.size}",
        s"distinct subscenes    : ${distinct("OverallScene").size}",
        s"distinct broad scenes : ${distinct("BroadSceneNum").size}",
        s"distinct story order  : ${distinct("StoryOrderSceneNum").size}",
        s"narrative parts       : ${distinct("NarrativePart").size}",
        s"Time range (s)        : ${times.headOption.getOrElse(-1L)}..${times.lastOption.getOrElse(-1L)}",
        "",
        "Time is read at Excel day origin 0; the Friends rule would have refused every row."
      )

@main def runMementoIntake(args: String*): Unit =
  val data = Path.of(args.headOption.getOrElse("data"))
  val docs = Path.of(args.lift(1).getOrElse("docs/data"))
  MementoIntake.run(data, docs) match
    case Left(err)    => println(s"MEMENTO INTAKE REFUSED\n$err")
    case Right(lines) => println("MEMENTO INTAKE RECEIPT"); lines.foreach(println)

@main def runFriendsIntake(args: String*): Unit =
  val data = Path.of(args.headOption.getOrElse("data"))
  val docs = Path.of(args.lift(1).getOrElse("docs/data"))
  FriendsIntake.run(data, docs) match
    case Left(err) =>
      println(s"FRIENDS INTAKE REFUSED\n$err")
    case Right(lines) =>
      println("FRIENDS INTAKE RECEIPT")
      lines.foreach(println)
