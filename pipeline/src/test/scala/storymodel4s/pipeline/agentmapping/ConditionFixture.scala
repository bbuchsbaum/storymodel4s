package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets.UTF_8
import storymodel4s.align.MappingResult
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.core.Checksum
import storymodel4s.pipeline.TimedAnnotationInput
import storymodel4s.provider.agent.{
  ExchangeFailure,
  ModelReply,
  ModelStopReason,
  ReplyEvidence,
  TextExchange
}

/** An invented two-film source and sessions of six units, with study bundles written under any
  * recorded condition and reference runs in the case-study harness's prompt format. Shared by the
  * producer-condition, view-rule, reference and golden suites. Loaded bundles are made only by
  * decoding what the fixture encodes.
  */
object ConditionFixture:
  import StudyReplay.{RecallUnit, ReplyProtocol, UnitReply}

  def right[E, A](value: Either[E, A]): A =
    value.fold(e => throw new AssertionError(e.toString), identity)
  def json(text: String): Json = right(parse(text))

  private val parts =
    """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
  lazy val view: TimedSourceView = right(
    TimedAnnotationInput.prepare(
      (TimedAnnotationInput.HeaderV2.mkString(",") +: Vector(
        "1,Anna arrived.,a,10,20,1,scene 1,,Anna,1,Harbour film,",
        "2,A title card.,a,20,30,2,scene 2,,,1,Harbour film,no",
        "3,A mouse dances.,a,40,50,3,scene 3,,,2,Cartoon,no",
        "4,Bob left.,a,50,,4,scene 4,,Bob,3,Station film,"
      )).mkString("", "\n", "\n").getBytes(UTF_8),
      parts.getBytes(UTF_8)
    )
  )
  val texts: Vector[String] =
    Vector("she came in", "then the title", "a mouse", "he left", "um", "what")
  val protocol: ReplyProtocol = ReplyProtocol.StrictChunked
  val protocolName = "strict-chunked/v1"
  private def item(body: String) = UnitReply.Answered(json(body))
  private val replies = Vector(
    item("""{"referent":1,"referent_film":1,"narrative":1,"narrative_film":1,"conf":0.8}"""),
    item("""{"referent":2,"referent_film":1,"narrative":1,"narrative_film":1,"conf":0.5}"""),
    item("""{"referent":null,"referent_film":3,"narrative":null,"narrative_film":2,"conf":0.4}"""),
    item("""{"referent":null,"referent_film":null,"narrative":null,"narrative_film":null}"""),
    UnitReply.Omitted,
    item("""{"referent":99,"referent_film":1,"narrative":1}""")
  )

  def legacy(name: String): RecordedCondition = right(RecordedCondition.legacy(name))
  def declared(name: String, condition: ProducerCondition): RecordedCondition.Declared =
    right(RecordedCondition.declared(name, condition))

  /** Each preceding units, order hidden by a shuffle with `seed`, opaque harness ids salted with
    * `salt`.
    */
  def contentLocal(k: Int, seed: Long = 20261117L, salt: String = "20261117"): ProducerCondition =
    right(
      ProducerCondition.of(
        right(ContextWindow.Preceding.of(k)),
        OrderExposure.PooledShuffle(seed),
        ExposedIds.OpaqueSalted(ReferenceBundle.HarnessScheme, Checksum.ofText(salt))
      )
    )

  val wholeRecall: ProducerCondition = ProducerCondition.WholeRecallInOrder

  /** One participant-session of a study: its recall, rows, records and bundle. The recall's title
    * is fixed, so the same texts under another session give the same records.
    */
  final class Study(val session: String, val texts: Vector[String], val model: String):
    val units: Vector[RecallUnit] =
      texts.zipWithIndex.map((t, i) => RecallUnit(i + 1, Json.fromInt(10 + i), t))
    lazy val recall: PreparedRecall =
      right(PreparedRecall.supplied("p1_s1", texts, Checksum.ofText(texts.mkString("|"))))
    lazy val rows: Vector[StudyBundle.Row] =
      units.zip(replies).zip(recall.unitIds).map { case ((u, r), id) =>
        StudyBundle.row(view, protocol, u, id.value, r, Vector("conf"))
      }

    /** The producer, with the study path's rendering witness when the condition has one. */
    def producer(condition: RecordedCondition): AgentMappingRecord.Producer =
      StudyRecord.producer(
        "cli",
        model,
        "SYSTEM",
        view,
        recall,
        RenderedCondition.study(condition).toOption
      )

    def records(condition: RecordedCondition): Map[String, MappingResult] =
      right(
        StudyBundle.records(condition, protocolName, recall, view, producer(condition), rows)
      )

    def provenance(condition: RecordedCondition): StudyBundle.Provenance =
      StudyBundle.Provenance(
        "p1",
        session,
        condition,
        model,
        "cli",
        protocolName,
        Checksum.ofText("SYSTEM").hex,
        "0" * 64,
        PreparedRecall.Segmentation(
          "supplied",
          Checksum.ofText(texts.mkString("|")),
          PreparedRecall.SuppliedRecipe
        )
      )

    def bundle(condition: RecordedCondition): Json =
      StudyBundle.encode(provenance(condition), view, records(condition), rows)

    def expected(condition: RecordedCondition): StudyBundle.Expected =
      StudyBundle.Expected("p1", session, units, producer(condition), condition = Some(condition))

    def decode(doc: Json, condition: RecordedCondition): Either[String, StudyBundle.Loaded] =
      StudyBundle.decode(doc, view, recall, expected(condition))

    /** The bundle under `condition`, encoded and decoded. */
    def loaded(condition: RecordedCondition): StudyBundle.Loaded =
      right(decode(bundle(condition), condition))

  def study(session: String = "s1", texts: Vector[String] = texts, model: String = "m"): Study =
    new Study(session, texts, model)

  private lazy val s1 = study()
  def units: Vector[RecallUnit] = s1.units
  def recall: PreparedRecall = s1.recall
  def rows: Vector[StudyBundle.Row] = s1.rows
  def producer(condition: RecordedCondition): AgentMappingRecord.Producer = s1.producer(condition)
  def records(condition: RecordedCondition): Map[String, MappingResult] = s1.records(condition)
  def provenance(condition: RecordedCondition): StudyBundle.Provenance = s1.provenance(condition)
  def bundle(condition: RecordedCondition): Json = s1.bundle(condition)
  def expected(condition: RecordedCondition): StudyBundle.Expected = s1.expected(condition)
  def decode(doc: Json, condition: RecordedCondition): Either[String, StudyBundle.Loaded] =
    s1.decode(doc, condition)

  // Reference runs in the case-study harness's prompt format.

  val referenceSystem = "REFERENCE SYSTEM"

  /** The harness's opaque id: `"x"` and the first ten hex digits of SHA-256 of `salt:id`. */
  def harnessId(salt: String, id: String): String = "x" + Checksum.ofText(s"$salt:$id").hex.take(10)

  /** The recall positions a reference run asks about: the first three units, and unit 5 (position
    * 4), where windows of two, three and all preceding units render differently.
    */
  val RefPositions: Vector[Int] = Vector(0, 1, 2, 4)

  /** The asked units' ids under the harness scheme with the default salt. */
  def harnessIds: Vector[String] =
    RefPositions.map(p => harnessId("20261117", recall.unitIds(p).value))

  /** One item as the harness renders it; a context line only when the window has units. */
  def itemBlock(id: String, context: Option[String], target: String): String =
    s"ITEM $id\n" + context.fold("")(c => s"CONTEXT: $c\n") + s"TARGET: $target"

  def harnessPrompt(blocks: Vector[String]): String =
    "CAST\ncast\n\nFILM ANNOTATION\noutline\n\nITEMS (random order)\n\n" +
      blocks.mkString("\n\n") + s"\n\nAnalyse all ${blocks.size} items."

  /** The harness's CONTEXT for each asked position under two preceding units, written out by hand
    * (not computed by the code under test): texts joined by single spaces, or `(start of recall)`.
    */
  val TwoPreceding: Map[Int, String] = Map(
    0 -> "(start of recall)",
    1 -> "she came in",
    2 -> "she came in then the title",
    4 -> "a mouse he left"
  )

  /** The harness's CONTEXT by position under `k` preceding units; only 0 and 2 are written out. */
  def harnessContext(k: Int)(position: Int): Option[String] = k match
    case 0 => None
    case 2 => Some(TwoPreceding(position))
    case _ => throw new IllegalArgumentException(s"no literal contexts for k = $k")

  /** The asked units under `ids` (aligned with `RefPositions`), with a foreign item `xz` from
    * another participant in the first batch. `context` and `target` give each position's lines.
    */
  def referenceBatchesWith(
      context: Int => Option[String],
      ids: Vector[String],
      target: Int => String = texts(_)
  ): Vector[ReferenceAnswers.Batch] =
    def block(i: Int) =
      itemBlock(ids(i), context(RefPositions(i)), target(RefPositions(i)))
    Vector(
      ReferenceAnswers.Batch(
        harnessPrompt(Vector(block(1), itemBlock("xz", Some("x y"), "other"), block(0))),
        Vector(ids(1), "xz", ids(0))
      ),
      ReferenceAnswers.Batch(harnessPrompt(Vector(block(2), block(3))), Vector(ids(2), ids(3)))
    )

  /** The harness's rendering of the asked units under `k` (0 or 2) preceding units. */
  def referenceBatches(k: Int, ids: Vector[String] = harnessIds): Vector[ReferenceAnswers.Batch] =
    referenceBatchesWith(harnessContext(k), ids)

  def referenceReplies(ids: Vector[String]): Map[Int, String] = Map(
    0 -> (s"""{"items":[{"id":"${ids(0)}","talk":"episodic","hedge":false,"refs":[""" +
      """{"words":"she came in","form":"specific","alts":[{"w":0.9,"iv":[[1,1]]}]}]},""" +
      """{"id":"xz","talk":"fragment"}]}"""),
    1 -> s"""{"items":[{"id":"${ids(2)}","talk":"commentary","hedge":false,"refs":[]}]}"""
  )

  def serving(
      batches: Vector[ReferenceAnswers.Batch],
      replies: Map[Int, String]
  ): String => Either[ExchangeFailure, ModelReply] =
    prompt =>
      val b = batches.indexWhere(_.prompt == prompt)
      replies
        .get(b)
        .map(text => ModelReply("opus", text, ModelStopReason.EndTurn, ReplyEvidence.Authored))
        .toRight(
          ExchangeFailure.RecordingMissing(
            TextExchange.key("cli", "opus", referenceSystem, prompt)
          )
        )

  /** One replayed reference run for this session: what the bundle is built from and read against.
    */
  final case class ReferenceRun(
      provenance: ReferenceBundle.Provenance,
      recorded: Vector[ReferenceBundle.Recorded],
      units: Vector[ReferenceBundle.UnitAnswer],
      expected: ReferenceBundle.Expected
  ):
    def doc: Either[ReferenceBundle.RenderingRefusal, Json] =
      ReferenceBundle.build(provenance, SourceRows.of(view), expected.recall, recorded, units)
    def loaded: Either[String, ReferenceBundle.Loaded] =
      doc.left.map(_.message).flatMap(ReferenceBundle.decode(_, SourceRows.of(view), expected))

  def referenceRun(
      condition: RecordedCondition.Declared,
      batches: Vector[ReferenceAnswers.Batch],
      ids: Vector[String] = harnessIds
  ): ReferenceRun =
    val rows = SourceRows.of(view)
    val (outcomes, receipts) = right(
      ReferenceAnswers.replay(
        batches,
        ReferenceAnswers.Schema.LocalV2,
        rows,
        serving(batches, referenceReplies(ids))
      )
    )
    val recorded = batches.zip(receipts).map(ReferenceBundle.Recorded(_, _))
    val unitIds = recall.unitIds.map(_.value)
    val asked =
      ids.zipWithIndex.map((item, i) => (RefPositions(i) + 1, unitIds(RefPositions(i)), item))
    val provenance = ReferenceBundle.Provenance(
      "p1",
      "s1",
      condition,
      "opus",
      "cli",
      ReferenceAnswers.Schema.LocalV2,
      Checksum.ofText(referenceSystem).hex
    )
    ReferenceRun(
      provenance,
      recorded,
      asked.map((u, id, item) => ReferenceBundle.UnitAnswer(u, id, item, outcomes(item))),
      ReferenceBundle.Expected(
        "p1",
        "s1",
        asked,
        unitIds.zip(texts),
        recorded,
        condition,
        "opus",
        "cli",
        ReferenceAnswers.Schema.LocalV2,
        Checksum.ofText(referenceSystem).hex
      )
    )
