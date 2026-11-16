package storymodel4s.pipeline.agentmapping

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import storymodel4s.align.*
import storymodel4s.align.OnsetLevelProjection.{
  Absence,
  BinValue,
  Bins,
  Declaration as ProjectionDeclaration,
  Interpretation,
  Layer,
  Level,
  NotApplicableReason,
  Nesting,
  Reducer,
  Request,
  Sample
}
import storymodel4s.core.*
import storymodel4s.corpus.intake.{RecallTimingIntake, SherlockAnnotations, TimebaseRepair}
import storymodel4s.recall.*
import storymodel4s.recall.RecallGraphStatus.Checked

/** Library parity driver for case study 2, milestone 0 (research tooling, not a product command).
  *
  * Why: case study 1 computed recall-time reference measures in a Python harness. This driver runs
  * the same recorded reference answers through the library path (G6 answers, the declared
  * conversion, G3 reference measures, G5 onset-point projection) so the two can be compared. It
  * writes only to a new directory under the ignored data root; the outputs carry unit ids and
  * numbers, never recall or film text (rule 14d).
  *
  * Usage (from the repository root):
  * `sbt 'pipeline/runMain storymodel4s.pipeline.agentmapping.ReferenceParity <dataRoot> <outDir>'`
  */
object ReferenceParity:
  val Version: String = "reference-parity/v1"
  val Clock: RecallTiming.ClockKey = RecallTiming.ClockKey.unsafe("openneuro-word-onset-seconds")
  val OnsetColumn: Int = 4
  val Tr: ExactRational = ExactRational.of(3L, 2L).toOption.get
  val Answers: String = "study/recall-precision/llm/llm-local-v2-a"
  val Units: String = "study/recall-to-video/development-20260923/A"

  /** One participant's checked inputs. */
  final case class Inputs(
      graph: RecallGraph[Checked],
      inventory: RecallInventory,
      timing: RecallTiming,
      timingRecipe: String,
      answers: Map[String, Json]
  )

  /** The recall graph by the unit manifest's recipe (`AgentMappingRecord.recall`: the word column
    * joined by single spaces, segmented by the standard segmenter), keeping the checked graph the
    * projection needs. The inventory's words are the CSV's own word cells, not lexical tokens: a
    * cell's onset is never divided among the several lexical tokens a cell can hold (a hyphenated
    * word, a clock time, an apostrophe written as three characters), and the harness treated each
    * cell as one word. Unit ids depend only on the transcript, so they are unchanged.
    */
  def recall(csv: Array[Byte]): Either[String, (RecallGraph[Checked], RecallInventory)] =
    val content = new String(csv, StandardCharsets.UTF_8)
    val lines = content.split('\n').toVector.map(_.stripSuffix("\r")).filter(_.nonEmpty)
    lines match
      case header +: rows if header.split(',').headOption.exists(_.trim == "Words") =>
        val words = rows.flatMap(_.split(',').headOption.map(_.trim).filter(_.nonEmpty))
        for
          source <- StorySource
            .fromText(words.mkString(" "), Some("sherlock-recall"))
            .left
            .map(_.message)
          graph = RecallSegmenter.segment(source)
          inventory <- RecallInventory
            .of(
              graph,
              cells(words),
              WordIdPolicy.inputArtifact(Checksum.ofBytes(csv))
            )
            .left
            .map(_.message)
        yield graph -> inventory
      case _ => Left("expected a header row starting with 'Words'")

  /** OpenNeuro onsets for the inventory words, by the exact cell-replay intake recipe. */
  def timing(
      csv: Array[Byte],
      graph: RecallGraph[Checked],
      inventory: RecallInventory
  ): Either[String, (RecallTiming, String)] =
    for
      table <- RecallTimingIntake.records(csv).left.map(r => s"timing CSV: $r")
      header <- table.headOption.toRight("timing CSV is empty")
      columns = RecallTimingIntake.Columns(header, 0, OnsetColumn, Clock)
      result <- RecallTimingIntake
        .read(csv, Checksum.ofBytes(csv), columns, graph, inventory)
        .left
        .map(r => s"timing intake refused: $r")
    yield result.timing -> RecallTimingIntake.Recipe

  /** UTF-16 spans of the nonblank word cells in the space-joined transcript. */
  private def cells(words: Vector[String]): Vector[TextSpan] =
    words
      .scanLeft(0)((at, w) => at + w.length + 1)
      .zip(words)
      .map((start, w) => TextSpan.unsafe(start, start + w.length))

  /** Bins `[k·1.5, (k+1)·1.5)` from 0 through the last onset. */
  def bins(timing: RecallTiming): Either[String, Bins] =
    val onsets = timing.entries.collect {
      case RecallTiming.Entry(_, _, RecallTiming.Observation.OnsetOnly(at, _)) => at
    }
    val last = onsets.map(r => BigDecimal(r.numerator) / BigDecimal(r.denominator)).maxOption
    last.toRight("no onsets").flatMap { max =>
      val n = (max / BigDecimal(1.5)).setScale(0, BigDecimal.RoundingMode.FLOOR).toInt + 1
      val bounds = (0 until n).toVector.map { k =>
        (ExactRational.of(3L * k, 2L).toOption.get, ExactRational.of(3L * (k + 1), 2L).toOption.get)
      }
      Bins.checked(timing.clock, bounds).left.map(r => s"bins refused: $r")
    }

  /** One reference's row in the per-reference table. */
  final case class RefRow(
      unit: RecallUnit,
      index: Int,
      outcome: Either[ReferenceConversion.Refusal, ReferenceConversion.Converted],
      extent: Option[Either[ReferenceExtent.Unavailable, ReferenceExtent.Result]]
  )

  private val notAboutFilm: Set[ReferenceAnswers.Talk] = Set(
    ReferenceAnswers.Talk.Metamemory,
    ReferenceAnswers.Talk.Commentary,
    ReferenceAnswers.Talk.Fragment
  )

  private def count[A](xs: Iterable[A])(key: A => String, unit: A => String): Json =
    Json.fromFields(xs.groupBy(key).toVector.sortBy(_._1).map { (k, v) =>
      k -> Json.obj(
        "count" -> Json.fromInt(v.size),
        "units" -> Json.fromValues(v.map(unit).toVector.distinct.sorted.map(Json.fromString))
      )
    })

  private def fmt(d: Double): String = f"$d%.6f"

  /** Run one participant and write its TSVs and ledger into `out`. */
  def participant(
      dataRoot: Path,
      out: Path,
      name: String,
      film: AgentMappingRecord.Film,
      atlas: SherlockAnnotations.Atlas
  ): Either[String, Json] =
    val csv = Files.readAllBytes(dataRoot.resolve(s"sherlock/recall/$name.csv"))
    val view = film.view
    val rows = SourceRows.of(view)
    def row(r: Int): SourceNodeRef =
      SourceNodeRef.Situation(SituationId.unsafe(s"timed:${view.artifact.hex}:$r"))
    val selections = Vector(
      TemporalSupport.Selection.Part(atlas.partABundle.identity),
      TemporalSupport.Selection.Part(atlas.partBBundle.identity)
    )
    for
      (graph, inventory) <- recall(csv)
      table <- AgentPrompts
        .units(Files.readString(dataRoot.resolve(s"$Units/recall-map-$name.tsv")))
        .left
        .map(r => s"unit table: $r")
      ids <- stageIds(dataRoot.resolve(s"$Units/recall-map-$name.tsv.stages.json"))
      _ <- AgentMappingRecord.bind(
        table,
        ids,
        AgentMappingRecord.Recall(inventory, graph.ordered.map(_.text))
      )
      (timed, recipe) <- timing(csv, graph, inventory)
      answers <- answerMap(dataRoot.resolve(s"$Answers/$name.json"))
      grid <- bins(timed)
      result <- run(
        out,
        name,
        graph,
        inventory,
        timed,
        recipe,
        answers,
        grid,
        rows,
        row,
        view,
        selections
      )
    yield result

  private def stageIds(path: Path): Either[String, Map[Int, String]] =
    for
      doc <- io.circe.jawn.parse(Files.readString(path)).left.map(_ => "stage record is not JSON")
      units <- doc.hcursor.get[Vector[Json]]("units").left.map(_ => "stage record has no units")
    yield units
      .flatMap(u =>
        (u.hcursor.get[Int]("unit").toOption, u.hcursor.get[String]("unitId").toOption).mapN2
      )
      .toMap

  extension [A, B](p: (Option[A], Option[B]))
    private def mapN2: Option[(A, B)] = for a <- p._1; b <- p._2 yield (a, b)

  private def answerMap(path: Path): Either[String, Map[String, Json]] =
    for
      doc <- io.circe.jawn.parse(Files.readString(path)).left.map(_ => "answers are not JSON")
      units <- doc.hcursor.get[Vector[Json]]("units").left.map(_ => "answers have no units")
    yield units.flatMap(u => u.hcursor.get[String]("id").toOption.map(_ -> u)).toMap

  private def run(
      out: Path,
      name: String,
      graph: RecallGraph[Checked],
      inventory: RecallInventory,
      timed: RecallTiming,
      recipe: String,
      answers: Map[String, Json],
      grid: Bins,
      rows: SourceRows,
      row: Int => SourceNodeRef,
      view: storymodel4s.align.bridge.TimedSourceView,
      selections: Vector[TemporalSupport.Selection]
  ): Either[String, Json] =
    val source = view.representation
    // Unit outcomes.
    val unitNotes = Vector.newBuilder[(String, String)]
    val refRows = Vector.newBuilder[RefRow]
    val notApplicable = Vector.newBuilder[RecallUnit]
    graph.ordered.foreach { unit =>
      answers.get(unit.id.value) match
        case None       => unitNotes += ("no-record" -> unit.id.value)
        case Some(item) =>
          val answer = ReferenceAnswers.answer(item, ReferenceAnswers.Schema.LocalV2, rows)
          answer.talk match
            case ReferenceAnswers.TalkAnswer.Whole(AgentAnswers.Field.Value(talk)) =>
              val refs = answer.refs match
                case AgentAnswers.Field.Value(rs) => Some(rs)
                case _                            => None
              if notAboutFilm(talk) then
                notApplicable += unit
                unitNotes += (s"not-about-film-${talk.token}" -> unit.id.value)
                if refs.exists(_.nonEmpty) then
                  unitNotes += ("not-about-film-with-refs-ignored" -> unit.id.value)
              else
                unitNotes += ("episodic" -> unit.id.value)
                refs match
                  case None     => unitNotes += ("refs-unstated" -> unit.id.value)
                  case Some(rs) =>
                    if rs.isEmpty then unitNotes += ("episodic-without-refs" -> unit.id.value)
                    rs.zipWithIndex.foreach { (ref, i) =>
                      val converted = ReferenceConversion
                        .reference(unit, ref, row, rows, source, selections.head)
                      refRows += RefRow(unit, i, converted, None)
                    }
            case _ => unitNotes += ("talk-unstated" -> unit.id.value)
    }
    val references0 = refRows.result()
    val naUnits = notApplicable.result()
    // Admission over every sample of the layer: converted anchors and not-about-film units.
    val converted = references0.zipWithIndex.collect { case (RefRow(_, _, Right(c), _), j) =>
      j -> c
    }
    val naAnchors = naUnits.map(_.span)
    val refused =
      ReferenceConversion.inadmissible(inventory, converted.map(_._2.anchor) ++ naAnchors)
    val refusedNa = naUnits.indices.filter(i => refused.contains(converted.size + i)).toSet
    val references = references0.zipWithIndex.map { (r, j) =>
      val k = converted.indexWhere(_._1 == j)
      if k >= 0 && refused.contains(k) then r.copy(outcome = Left(refused(k)))
      else if k >= 0 then
        r.copy(extent =
          Some(ReferenceExtent.of(source, selections, r.outcome.toOption.get.declaration))
        )
      else r
    }
    val admitted = references.collect { case r @ RefRow(_, _, Right(c), _) => (r, c) }
    val naKept = naUnits.zipWithIndex.filterNot((_, i) => refusedNa(i)).map(_._1)
    val naSamples = naKept.map(_.span)
    // Per-sample labels for the word table: `unit:ref` for a reference, `na:unit` for a unit.
    val labels = admitted.map((r, _) => s"${r.unit.ordinal}:${r.index}") ++
      naKept.map(u => s"na:${u.ordinal}")
    val declaration = (measure: String) =>
      ProjectionDeclaration(
        Layer.Custom("case-study", "v2-reference"),
        Interpretation.Referent,
        Checksum.ofText(measure)
      )
    val altSamples =
      admitted.map((_, c) => Sample(c.anchor, Level.Applicable(c.alternativesPresent))) ++
        naSamples.map(s => Sample(s, Level.NotApplicable(NotApplicableReason.Discourse)))
    // The extent projection admits only references with an extent; its own ties are re-checked.
    val withExtent = admitted.collect { case (RefRow(_, _, _, Some(Right(e))), c) => c.anchor -> e }
    val extentRefused =
      ReferenceConversion.inadmissible(inventory, withExtent.map(_._1) ++ naSamples)
    val extentSamples =
      withExtent.zipWithIndex.filterNot((_, i) => extentRefused.contains(i)).map {
        case ((a, e), _) =>
          Sample(a, Level.Applicable(e.log10))
      } ++ naSamples.zipWithIndex
        .filterNot((_, i) => extentRefused.contains(withExtent.size + i))
        .map((s, _) => Sample(s, Level.NotApplicable(NotApplicableReason.Discourse)))
    for
      altRequest <- Request
        .checked(
          graph,
          timed,
          declaration("alternatives-present"),
          Nesting.Innermost,
          grid,
          altSamples,
          Reducer.Share[Boolean]()
        )
        .left
        .map(r => s"alternatives projection refused: $r")
      extentRequest <- Request
        .checked(
          graph,
          timed,
          declaration(ReferenceExtent.Version),
          Nesting.Innermost,
          grid,
          extentSamples,
          Reducer.Mean
        )
        .left
        .map(r => s"extent projection refused: $r")
    yield
      val alt = OnsetLevelProjection.project(altRequest)
      val ext = OnsetLevelProjection.project(extentRequest)
      val _ = writeBins(out, name, alt, ext)
      val _ = writeReferences(out, name, references)
      val _ = writeWords(out, name, inventory, timed, altSamples.map(_.anchor), labels)
      val ledger = ledgerJson(
        name,
        inventory,
        timed,
        recipe,
        unitNotes.result(),
        references,
        naUnits,
        refusedNa,
        extentRefused.size,
        alt,
        ext
      )
      Files.writeString(out.resolve(s"${name.take(4)}_ledger.json"), ledger.spaces2 + "\n")
      ledger

  private def writeBins(
      out: Path,
      name: String,
      alt: OnsetLevelProjection.Projection[Boolean, OnsetLevelProjection.CategoryShares[Boolean]],
      ext: OnsetLevelProjection.Projection[Double, Double]
  ): Path =
    val header = Vector(
      "onset",
      "duration",
      "status",
      "words",
      "n_refs",
      "n_not_applicable_refs",
      "n_covered_words",
      "n_uncovered_member_words",
      "n_uncovered_unassigned_words",
      "extent_log10_s",
      "extent_n_refs",
      "alternatives_present_share",
      "not_applicable_share"
    )
    val lines = alt.bins.zip(ext.bins).map { (a, e) =>
      val l = a.ledger
      val status = a.value match
        case BinValue.Present(_)                        => "observed"
        case BinValue.Absent(Absence.NoSample)          => "no_sample"
        case BinValue.Absent(Absence.NotApplicableOnly) => "only_not_applicable"
        case BinValue.Absent(Absence.UncoveredOnly)     => "only_uncovered"
      val share = a.value match
        case BinValue.Present(s) =>
          s.share(true).map(r => fmt(r.numerator.toDouble / r.denominator)).getOrElse("n/a")
        case _ => "n/a"
      val extent = e.value match
        case BinValue.Present(v) => fmt(v)
        case _                   => "n/a"
      val refs = l.applicableReferences + l.notApplicableReferences
      val na =
        if refs == 0 then "n/a" else fmt(l.notApplicableReferences.toDouble / refs.toDouble)
      val start = BigDecimal(a.bin.start.numerator) / BigDecimal(a.bin.start.denominator)
      Vector(
        start.setScale(1).toString,
        "1.5",
        status,
        l.wordsWithOnset.toString,
        l.applicableReferences.toString,
        l.notApplicableReferences.toString,
        l.coveredWords.toString,
        l.uncoveredMemberWords.toString,
        l.uncoveredUnassignedWords.toString,
        extent,
        e.ledger.applicableReferences.toString,
        share,
        na
      ).mkString("\t")
    }
    Files.writeString(
      out.resolve(s"${name.take(4)}_desc-libraryparity_timeseries.tsv"),
      (header.mkString("\t") +: lines).mkString("\n") + "\n"
    )

  /** One row per inventory word: its unit, the sample it holds under Innermost, and its onset. */
  private def writeWords(
      out: Path,
      name: String,
      inventory: RecallInventory,
      timed: RecallTiming,
      anchors: Vector[SpanSet],
      labels: Vector[String]
  ): Path =
    val support = anchors.map(OnsetLevelProjection.support)
    val ordinal = inventory.units.map(u => u.id -> u.ordinal).toMap
    val lines = inventory.words.map { w =>
      val unit = inventory.membership(w.id) match
        case WordMembership.Member(u)     => ordinal(u).toString
        case WordMembership.Unassigned(_) => "-1"
      val covering = support.indices.filter(i => support(i).exists(_.overlaps(w.span)))
      val sample =
        if covering.isEmpty then "-1" else labels(covering.minBy(i => support(i).map(_.length).sum))
      val onset = timed.entry(w.id).map(_.observation) match
        case Some(RecallTiming.Observation.OnsetOnly(at, _)) =>
          (BigDecimal(at.numerator) / BigDecimal(at.denominator)).toString
        case _ => "n/a"
      s"${w.index}\t$unit\t$sample\t$onset"
    }
    Files.writeString(
      out.resolve(s"${name.take(4)}_desc-libraryparity_words.tsv"),
      ("word_index\tunit_index\tsample\tonset" +: lines).mkString("\n") + "\n"
    )

  private def writeReferences(out: Path, name: String, refs: Vector[RefRow]): Path =
    val header = "unit_id\tunit_index\tref_index\tstatus\tform\talternatives_present\tmembers\t" +
      "extent_s\textent_log10_s\tunassigned_share\treason"
    val lines = refs.map { r =>
      val base = Vector(r.unit.id.value, r.unit.ordinal.toString, r.index.toString)
      r.outcome match
        case Left(reason) =>
          (base ++ Vector("refused", "n/a", "n/a", "n/a", "n/a", "n/a", "n/a", reason.code))
            .mkString("\t")
        case Right(c) =>
          val members = c.declaration match
            case ProvisionalReference.Declaration.Alternative(ms) => ms.size
            case _                                                => 1
          val weights = c.declaration match
            case ProvisionalReference.Declaration.Alternative(ms) => ms.flatMap(_.weight)
            case ProvisionalReference.Declaration.Definite(_, w)  => w.toVector
            case _                                                => Vector.empty
          val total = weights.sorted.sum
          val share =
            if math.abs(1.0 - total) <= MappingTolerance.RowSum then 0.0 else 1.0 - total
          val (s, l, why) = r.extent match
            case Some(Right(e)) => (fmt(e.seconds), fmt(e.log10), "")
            case Some(Left(u))  => ("n/a", "n/a", s"extent-${u.code}")
            case None           => ("n/a", "n/a", "extent-not-computed")
          (base ++ Vector(
            "converted",
            c.form.token,
            c.alternativesPresent.toString,
            members.toString,
            s,
            l,
            fmt(share),
            why
          )).mkString("\t")
    }
    Files.writeString(
      out.resolve(s"${name.take(4)}_desc-libraryparity_references.tsv"),
      (header +: lines).mkString("\n") + "\n"
    )

  private def ledgerJson(
      name: String,
      inventory: RecallInventory,
      timed: RecallTiming,
      recipe: String,
      unitNotes: Vector[(String, String)],
      references: Vector[RefRow],
      naUnits: Vector[RecallUnit],
      refusedNa: Set[Int],
      extentRefused: Int,
      alt: OnsetLevelProjection.Projection[Boolean, ?],
      ext: OnsetLevelProjection.Projection[Double, ?]
  ): Json =
    def proj(p: OnsetLevelProjection.Projection[?, ?]) = Json.obj(
      "bins" -> Json.fromInt(p.bins.size),
      "samples" -> Json.fromInt(p.request.samples.size),
      "missingTimingWords" -> Json.fromInt(p.missingTimingWords.size),
      "outsideBinWords" -> Json.fromInt(p.outsideBinWords.size),
      "wordlessReferences" -> Json.fromInt(p.wordlessReferences.size),
      "unsampledReferences" -> Json.fromInt(p.unsampledReferences.size)
    )
    val missing = timed.entries.count(_.observation match
      case RecallTiming.Observation.Missing(_) => true
      case _                                   => false)
    Json.obj(
      "schema" -> Json.fromString(Version),
      "participant" -> Json.fromString(name.take(4)),
      "conversion" -> Json.fromString(ReferenceConversion.Version),
      "extentReducer" -> Json.fromString(ReferenceExtent.Version),
      "projection" -> Json.fromString(OnsetLevelProjection.Version),
      "nesting" -> Json.fromString("innermost"),
      "weights" -> Json.fromString(
        "self-reported, as written; coverage exhaustive; no calibration"
      ),
      "unitIdParity" -> Json.fromString("unit ids and texts bound to the frozen stage record"),
      "timing" -> Json.obj(
        "clock" -> Json.fromString(Clock.value),
        "recipe" -> Json.fromString(recipe),
        "words" -> Json.fromInt(inventory.words.size),
        "missingOnsetWords" -> Json.fromInt(missing),
        "unassignedWords" -> Json.fromInt(inventory.membership.values.count {
          case WordMembership.Unassigned(_) => true
          case _                            => false
        })
      ),
      "units" -> Json.obj(
        "count" -> Json.fromInt(inventory.units.size),
        "outcomes" -> count(unitNotes)(_._1, _._2)
      ),
      "references" -> Json.obj(
        "total" -> Json.fromInt(references.size),
        "converted" -> Json.fromInt(references.count(_.outcome.isRight)),
        "refused" -> count(references.collect { case r @ RefRow(_, _, Left(_), _) => r })(
          _.outcome.left.toOption.get.code,
          _.unit.id.value
        ),
        "extentUnavailable" -> count(references.collect {
          case r @ RefRow(_, _, Right(_), Some(Left(_))) => r
        })(_.extent.get.left.toOption.get.code, _.unit.id.value),
        "extentProjectionInadmissible" -> Json.fromInt(extentRefused)
      ),
      "notAboutFilmUnits" -> Json.obj(
        "count" -> Json.fromInt(naUnits.size),
        "inadmissible" -> Json.fromInt(refusedNa.size)
      ),
      "alternativesProjection" -> proj(alt),
      "extentProjection" -> proj(ext)
    )

  def main(args: Array[String]): Unit =
    args.toVector match
      case Vector(root, outDir) =>
        val dataRoot = Path.of(root)
        val out = Path.of(outDir)
        val code =
          try
            if Files.exists(out) then
              System.err.println(s"refused: $out exists")
              2
            else
              val partition = io.circe.jawn
                .parse(Files.readString(dataRoot.resolve("study/recall-to-video/partition.json")))
                .toOption
                .get
              val development =
                partition.hcursor.get[Vector[String]]("development").toOption.get
              val untouched =
                partition.hcursor.get[Vector[String]]("untouchedTest").toOption.get.toSet
              val annotation =
                Files.readAllBytes(dataRoot.resolve("sherlock/Sherlock_Segments_1000_NN_2017.tsv"))
              val result = for
                record <- TimebaseRepair.loadCommitted().left.map(_.message)
                atlas <- SherlockAnnotations.parse(annotation, record).left.map(_.message)
                film <- AgentMappingRecord.film(atlas, Checksum.ofBytes(annotation))
              yield (atlas, film)
              result match
                case Left(e) =>
                  System.err.println(s"refused: $e")
                  2
                case Right((atlas, film)) =>
                  Files.createDirectories(out)
                  val outcomes = development.filterNot(untouched).map { name =>
                    name -> participant(dataRoot, out, name, film, atlas)
                  }
                  outcomes.foreach {
                    case (n, Left(e))  => System.err.println(s"$n refused: $e")
                    case (n, Right(_)) => println(s"$n written")
                  }
                  Files.writeString(
                    out.resolve("manifest.json"),
                    Json
                      .obj(
                        "schema" -> Json.fromString(Version),
                        "participants" -> Json.fromFields(outcomes.map { (n, o) =>
                          n.take(4) -> o
                            .fold(e => Json.obj("refused" -> Json.fromString(e)), identity)
                        })
                      )
                      .spaces2 + "\n"
                  )
                  if outcomes.forall(_._2.isRight) then 0 else 1
          catch
            case NonFatal(e) =>
              System.err.println(s"failed: ${e.getClass.getSimpleName}")
              2
        sys.exit(code)
      case _ =>
        System.err.println("usage: ReferenceParity <dataRoot> <outDir>")
        sys.exit(2)
