package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.core.Checksum

/** How much of the recall the producer was shown beside each target unit.
  *
  * Why: a producer that reads the whole recall can use what comes later to place a unit; one shown
  * only `k` preceding units cannot. The two answer different questions (ADR 0031 section 4).
  */
sealed trait ContextWindow:
  /** The canonical token used in renderings and identifiers. */
  def token: String

object ContextWindow:
  /** The whole recall, in the order it was told. */
  case object WholeRecall extends ContextWindow:
    val token: String = "whole-recall"

  /** The `units` units that precede the target in its own recall, and nothing after it. */
  final class Preceding private (val units: Int) extends ContextWindow:
    def token: String = s"preceding-$units"
    override def equals(other: Any): Boolean = other match
      case that: Preceding => units == that.units
      case _               => false
    override def hashCode: Int = units.hashCode
    override def toString: String = s"Preceding($units)"

  object Preceding:
    /** `k` preceding units; `k` is zero or more. */
    def of(k: Int): Either[ProducerCondition.Refusal, Preceding] =
      Either.cond(k >= 0, new Preceding(k), ProducerCondition.Refusal.NegativeWindow(k))

/** Whether the producer could see where a target unit sits in its recall.
  *
  * Why: pooling units from every participant and shuffling them hides recall order, which is a
  * separate fact from how much context each unit carries.
  */
enum OrderExposure:
  /** Units were presented in recall order. */
  case Visible

  /** Units from every participant were pooled and shuffled with this recorded seed. */
  case PooledShuffle(seed: Long)

  def token: String = this match
    case Visible           => "order-visible"
    case PooledShuffle(sd) => s"pooled-shuffle-$sd"

/** The item identifiers the producer saw.
  *
  * Why: an identifier that encodes a unit's position tells the producer the order a shuffle hides;
  * this is the leak case study 1 found. An opaque identifier records its salt only by checksum,
  * because the recorded prompts already fix every exposed identifier for replay, and whoever holds
  * the salt can prove it against the checksum.
  *
  * The checksum is not secrecy. A low-entropy salt is recovered from it by guessing, and a salt
  * equal to the recorded shuffle seed (as in the case-study harness) is disclosed by the seed. The
  * salt is not secret from people who read a bundle; what the condition records is that the model
  * was never shown it, nor any identifier that encodes position.
  */
enum ExposedIds:
  /** Identifiers that are, or are derived from, the unit's position in its recall. */
  case Positional

  /** Identifiers derived from a salted hash under the named `scheme`; `saltSha256` is the checksum
    * of the salt's text.
    */
  case OpaqueSalted(scheme: String, saltSha256: Checksum)

  def token: String = this match
    case Positional              => "ids-positional"
    case OpaqueSalted(scheme, h) => s"ids-opaque-salted-$scheme-${h.hex}"

/** What a producer saw of the recall: its context window, whether recall order was exposed, and how
  * items were identified to it. This is the recall part of the rendering identity ADR 0022 asks
  * for.
  *
  * Why a checked type: some combinations of lawful fields are false. Hidden order with positional
  * identifiers is not hidden order (the identifiers carry it), and a whole recall in the order it
  * was told shows every unit's place, so neither can be recorded as a condition.
  */
final class ProducerCondition private (
    val window: ContextWindow,
    val order: OrderExposure,
    val ids: ExposedIds
):
  /** The canonical rendering: every field, in a fixed order. Equal renderings mean equal
    * conditions.
    */
  def rendering: String = s"producer-condition/v1:${window.token}:${order.token}:${ids.token}"

  /** The checksum of the canonical rendering; it enters policy identifiers and stage receipts. */
  def digest: Checksum = Checksum.ofText(rendering)

  /** What the condition measures, without its seed or salt: two runs with the same estimand are
    * replicates; two with different estimands answer different questions.
    */
  def estimand: ProducerCondition.Estimand =
    ProducerCondition.Estimand(
      window,
      order match
        case OrderExposure.Visible          => ProducerCondition.OrderKind.Visible
        case OrderExposure.PooledShuffle(_) => ProducerCondition.OrderKind.Hidden
      ,
      ids match
        case ExposedIds.Positional         => ProducerCondition.IdKind.Positional
        case ExposedIds.OpaqueSalted(_, _) => ProducerCondition.IdKind.Opaque
    )

  /** A plain description for a record's context policy. */
  def describe: String =
    val context = window match
      case ContextWindow.WholeRecall  => "the participant's whole recall"
      case p: ContextWindow.Preceding => s"each target unit with its ${p.units} preceding units"
    val shown = order match
      case OrderExposure.Visible           => "in recall order"
      case OrderExposure.PooledShuffle(sd) => s"pooled across participants and shuffled (seed $sd)"
    val named = ids match
      case ExposedIds.Positional         => "positional item ids"
      case ExposedIds.OpaqueSalted(s, _) => s"opaque salted item ids ($s)"
    s"$context, $shown, with $named"

  def json: Json = Json.obj(
    "rendering" -> Json.fromString(rendering),
    "window" -> (window match
      case ContextWindow.WholeRecall  => Json.obj("kind" -> Json.fromString("whole-recall"))
      case p: ContextWindow.Preceding =>
        Json.obj("kind" -> Json.fromString("preceding"), "units" -> Json.fromInt(p.units))),
    "order" -> (order match
      case OrderExposure.Visible           => Json.obj("kind" -> Json.fromString("visible"))
      case OrderExposure.PooledShuffle(sd) =>
        Json.obj("kind" -> Json.fromString("pooled-shuffle"), "seed" -> Json.fromLong(sd))),
    "ids" -> (ids match
      case ExposedIds.Positional         => Json.obj("kind" -> Json.fromString("positional"))
      case ExposedIds.OpaqueSalted(s, h) =>
        Json.obj(
          "kind" -> Json.fromString("opaque-salted"),
          "scheme" -> Json.fromString(s),
          "saltSha256" -> Json.fromString(h.hex)
        ))
  )

  override def equals(other: Any): Boolean = other match
    case that: ProducerCondition => rendering == that.rendering
    case _                       => false
  override def hashCode: Int = rendering.hashCode
  override def toString: String = s"ProducerCondition($rendering)"

object ProducerCondition:
  /** Why a condition was refused. */
  enum Refusal:
    case NegativeWindow(k: Int)

    /** Positional identifiers would expose the order a pooled shuffle hides. */
    case PositionalIdsExposeOrder

    /** A whole recall in its told order shows each unit's place, so its order is not hidden. */
    case WholeRecallExposesOrder
    case BadScheme(scheme: String)
    case Malformed(reason: String)

    /** A path that renders only one kind of prompt was given a condition it does not render. */
    case NotRendered(path: String, rendering: String)

    def message: String = this match
      case NegativeWindow(k)        => s"a context window of $k preceding units"
      case PositionalIdsExposeOrder => "positional item ids expose the order a pooled shuffle hides"
      case WholeRecallExposesOrder  => "a whole recall in its told order exposes recall order"
      case BadScheme(s)             => s"the id scheme $s is not a plain name"
      case Malformed(reason)        => s"producer condition: $reason"
      case NotRendered(path, r)     => s"the $path renders no prompt under $r"

  /** What a condition measures; see `ProducerCondition.estimand`. */
  final case class Estimand(window: ContextWindow, order: OrderKind, ids: IdKind)

  /** Whether recall order was exposed, without the seed. */
  enum OrderKind(val token: String):
    case Visible extends OrderKind("order-visible")
    case Hidden extends OrderKind("order-hidden")

  /** Whether item ids were positional or opaque, without the scheme or salt. */
  enum IdKind(val token: String):
    case Positional extends IdKind("ids-positional")
    case Opaque extends IdKind("ids-opaque")

  private val Scheme = "[A-Za-z0-9][A-Za-z0-9_.-]*".r

  /** The checked condition. */
  def of(
      window: ContextWindow,
      order: OrderExposure,
      ids: ExposedIds
  ): Either[Refusal, ProducerCondition] =
    (window, order, ids) match
      case (_, _: OrderExposure.PooledShuffle, ExposedIds.Positional) =>
        Left(Refusal.PositionalIdsExposeOrder)
      case (ContextWindow.WholeRecall, _: OrderExposure.PooledShuffle, _) =>
        Left(Refusal.WholeRecallExposesOrder)
      case (_, _, ExposedIds.OpaqueSalted(s, _)) if !Scheme.matches(s) =>
        Left(Refusal.BadScheme(s))
      case _ => Right(new ProducerCondition(window, order, ids))

  /** The existing whole-recall mapper: the whole recall in order, with positional unit numbers. */
  val WholeRecallInOrder: ProducerCondition =
    new ProducerCondition(ContextWindow.WholeRecall, OrderExposure.Visible, ExposedIds.Positional)

  /** Read a condition as `json` writes it. The stored rendering must be the one the fields give. */
  def decode(doc: Json): Either[Refusal, ProducerCondition] =
    val c = doc.hcursor
    def bad(reason: String) = Refusal.Malformed(reason)
    def keys(at: Option[Json]): Set[String] = at.flatMap(_.asObject).fold(Set.empty)(_.keys.toSet)
    def exactly(at: Option[Json], allowed: Set[String], what: String): Either[Refusal, Unit] =
      Either.cond(
        keys(at) == allowed,
        (),
        bad(s"$what has keys other than ${allowed.toVector.sorted.mkString(", ")}")
      )
    def integral(at: io.circe.ACursor, key: String): Option[BigInt] =
      at.downField(key).focus.flatMap(StudyReplay.strictInteger)
    for
      _ <- exactly(Some(doc), Set("rendering", "window", "order", "ids"), "a producer condition")
      windowKind <- c.downField("window").get[String]("kind").left.map(_ => bad("no window"))
      window <- windowKind match
        case "whole-recall" =>
          exactly(c.downField("window").focus, Set("kind"), "the window")
            .map(_ => ContextWindow.WholeRecall)
        case "preceding" =>
          for
            _ <- exactly(c.downField("window").focus, Set("kind", "units"), "the window")
            k <- integral(c.downField("window"), "units")
              .filter(_.isValidInt)
              .toRight(bad("window units are not an integer"))
            w <- ContextWindow.Preceding.of(k.toInt)
          yield w
        case other => Left(bad(s"unknown window $other"))
      orderKind <- c.downField("order").get[String]("kind").left.map(_ => bad("no order"))
      order <- orderKind match
        case "visible" =>
          exactly(c.downField("order").focus, Set("kind"), "the order").map(_ =>
            OrderExposure.Visible
          )
        case "pooled-shuffle" =>
          for
            _ <- exactly(c.downField("order").focus, Set("kind", "seed"), "the order")
            seed <- integral(c.downField("order"), "seed")
              .filter(_.isValidLong)
              .toRight(bad("the shuffle seed is not an integer"))
          yield OrderExposure.PooledShuffle(seed.toLong)
        case other => Left(bad(s"unknown order $other"))
      idsKind <- c.downField("ids").get[String]("kind").left.map(_ => bad("no ids"))
      ids <- idsKind match
        case "positional" =>
          exactly(c.downField("ids").focus, Set("kind"), "the ids").map(_ => ExposedIds.Positional)
        case "opaque-salted" =>
          for
            _ <- exactly(c.downField("ids").focus, Set("kind", "scheme", "saltSha256"), "the ids")
            scheme <- c.downField("ids").get[String]("scheme").left.map(_ => bad("no id scheme"))
            raw <- c.downField("ids").get[String]("saltSha256").left.map(_ => bad("no salt hash"))
            hash <- Checksum.from(raw).left.map(_ => bad("salt hash is not a checksum"))
          yield ExposedIds.OpaqueSalted(scheme, hash)
        case other => Left(bad(s"unknown ids $other"))
      condition <- of(window, order, ids)
      _ <- Either.cond(
        c.get[String]("rendering").contains(condition.rendering),
        (),
        bad("the stored rendering is not the fields' rendering")
      )
    yield condition

/** The condition a study or bundle records: a typed producer condition under a study name, or a
  * name alone from before conditions were typed.
  *
  * Why a legacy case: an old bundle names a condition but says nothing about what the producer saw.
  * Reading it as whole-recall or content-local would invent that fact, so it reads as unspecified
  * and keeps its old policy identifier, which keeps its records byte-identical.
  */
sealed trait RecordedCondition:
  /** The study's own plain name for the condition. */
  def name: String

  /** The typed producer condition, when one was recorded. */
  def producer: Option[ProducerCondition]

object RecordedCondition:
  private val Name = "[A-Za-z0-9][A-Za-z0-9_-]*".r

  /** A name recorded before producer conditions were typed; what the producer saw is unspecified.
    */
  final class Legacy private[RecordedCondition] (val name: String) extends RecordedCondition:
    def producer: Option[ProducerCondition] = None
    override def equals(other: Any): Boolean = other match
      case that: Legacy => name == that.name
      case _            => false
    override def hashCode: Int = ("legacy", name).hashCode
    override def toString: String = s"Legacy($name)"

  /** A named condition with its typed producer condition. */
  final class Declared private[RecordedCondition] (
      val name: String,
      val condition: ProducerCondition
  ) extends RecordedCondition:
    def producer: Option[ProducerCondition] = Some(condition)
    override def equals(other: Any): Boolean = other match
      case that: Declared => name == that.name && condition == that.condition
      case _              => false
    override def hashCode: Int = ("declared", name, condition).hashCode
    override def toString: String = s"Declared($name, ${condition.rendering})"

  private def named(name: String): Either[String, String] =
    Either.cond(Name.matches(name), name, "the condition is not a plain name")

  def legacy(name: String): Either[String, Legacy] = named(name).map(new Legacy(_))

  def declared(name: String, condition: ProducerCondition): Either[String, Declared] =
    named(name).map(new Declared(_, condition))

/** A recorded condition that a production path is known to render: the witness a record needs
  * before it may carry a rendering identity.
  *
  * Why a checked type: a declared condition alone is a label (ADR 0022: caller assertions remain
  * asserted provenance). Only the study path's own check can make one, so a record's rendering
  * identity can only come from a condition that path's prompts actually show.
  */
final class RenderedCondition private (val recorded: RecordedCondition):
  def name: String = recorded.name
  def producer: Option[ProducerCondition] = recorded.producer

  /** The rendering identity a record's decision receipt carries; none for a legacy condition. */
  def renderingSha256: Option[Checksum] = producer.map(_.digest)

  override def equals(other: Any): Boolean = other match
    case that: RenderedCondition => recorded == that.recorded
    case _                       => false
  override def hashCode: Int = ("rendered", recorded).hashCode
  override def toString: String = s"RenderedCondition($recorded)"

object RenderedCondition:
  /** The only producer condition a study export renders. `StudyReplay.validate` requires every
    * chunk prompt to hold every unit's line, numbered by its position, in recall order; so a study
    * prompt shows the whole recall, in order, with positional ids, and a declared condition must
    * say exactly that. A legacy condition asserts nothing and passes.
    */
  def study(condition: RecordedCondition): Either[ProducerCondition.Refusal, RenderedCondition] =
    condition.producer match
      case Some(p) if p != ProducerCondition.WholeRecallInOrder =>
        Left(ProducerCondition.Refusal.NotRendered("study export path", p.rendering))
      case _ => Right(new RenderedCondition(condition))
