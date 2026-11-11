package storymodel4s.pipeline.agentmapping

import io.circe.Json
import storymodel4s.align.bridge.TimedSourceView

/** Where each target scene lies on its film's own clip clock, and the rule that turns a time in a
  * film into a scene.
  *
  * Why: some conditions answer with a film and a time, and the reference is scene-level. The
  * conversion has to be one rule, decided by exact arithmetic, so a time on a boundary lands the
  * same way everywhere. A table can only be built from a checked source and a declared clock, and
  * it refuses a clock whose origin is not the film's first target row.
  *
  * A film's clip starts at its first target row. On the clip clock a scene starts at its earliest
  * row and ends where the next target scene starts; the first starts at 0 and the last ends at
  * the end of the clip, including that instant.
  */
final class SceneIntervals private (
    val tolerance: SceneIntervals.Exact,
    val films: Map[Int, SceneIntervals.Film]
):
  import SceneIntervals.*

  /** The scenes whose interval lies within the tolerance of `seconds`, in scene order. The
    * distance to an interval is 0 inside it and at both ends.
    */
  private def near(film: Film, seconds: Exact): Vector[Int] =
    film.scenes.collect {
      case Interval(scene, start, end)
          if (start - seconds).max(seconds - end).max(Exact.zero) <= tolerance =>
        scene
    }

  /** Where a time in a film falls. */
  def locate(film: BigInt, seconds: Exact): Located =
    Option.when(film.isValidInt)(film.toInt).flatMap(films.get) match
      case None        => Located.UnknownFilm
      case Some(entry) =>
        val close = near(entry, seconds)
        if seconds < Exact.zero || seconds > entry.duration then Located.Outside(close)
        else
          val scene = entry.scenes
            .find(i => i.start <= seconds && seconds < i.end)
            .getOrElse(entry.scenes.last)
          Located.Scene(scene.scene, close)

  /** The table as a dictionary a reader can check a time against, in exact fractions. */
  def json: Json = Json.obj(
    "toleranceSeconds" -> Json.fromString(tolerance.text),
    "films" -> Json.fromValues(films.toVector.sortBy(_._1).map { (film, entry) =>
      Json.obj(
        "collection" -> Json.fromInt(film),
        "durationSeconds" -> Json.fromString(entry.duration.text),
        "scenes" -> Json.fromValues(entry.scenes.map { i =>
          Json.obj(
            "scene" -> Json.fromInt(i.scene),
            "start" -> Json.fromString(i.start.text),
            "end" -> Json.fromString(i.end.text)
          )
        })
      )
    })
  )

  override def equals(other: Any): Boolean = other match
    case that: SceneIntervals => tolerance == that.tolerance && films == that.films
    case _                    => false
  override def hashCode(): Int = (tolerance, films).hashCode()
  override def toString: String = s"SceneIntervals(${films.size} films)"

object SceneIntervals:
  val ClockSchema: String = "storymodel4s.agentmapping.clip-clock/v1"

  /** An exact fraction. Times are compared and combined without rounding. */
  final class Exact private (val numerator: BigInt, val denominator: BigInt)
      extends Ordered[Exact]:
    def +(that: Exact): Exact =
      Exact(numerator * that.denominator + that.numerator * denominator, denominator * that.denominator)
    def -(that: Exact): Exact =
      Exact(numerator * that.denominator - that.numerator * denominator, denominator * that.denominator)
    def *(that: Exact): Exact = Exact(numerator * that.numerator, denominator * that.denominator)
    def max(that: Exact): Exact = if this >= that then this else that
    def compare(that: Exact): Int =
      (numerator * that.denominator).compare(that.numerator * denominator)
    def text: String = s"$numerator/$denominator"
    override def equals(other: Any): Boolean = other match
      case that: Exact => numerator == that.numerator && denominator == that.denominator
      case _           => false
    override def hashCode(): Int = (numerator, denominator).hashCode()
    override def toString: String = text

  object Exact:
    val zero: Exact = new Exact(BigInt(0), BigInt(1))
    private val Decimal = "(-?)([0-9]+)(?:\\.([0-9]+))?".r

    /** `denominator` must be positive. */
    private[agentmapping] def apply(numerator: BigInt, denominator: BigInt): Exact =
      val g = numerator.gcd(denominator).max(BigInt(1))
      new Exact(numerator / g, denominator / g)

    def ofInt(n: BigInt): Exact = new Exact(n, BigInt(1))

    /** A positive fraction, such as the seconds one tick lasts. */
    def ratio(numerator: BigInt, denominator: BigInt): Option[Exact] =
      Option.when(numerator > 0 && denominator > 0)(apply(numerator, denominator))

    /** A plain decimal: digits, optionally a point and more digits, optionally a leading minus. */
    def decimal(text: String): Option[Exact] = text match
      case Decimal(sign, whole, fraction) =>
        val digits = Option(fraction).getOrElse("")
        val n = BigInt(whole + digits)
        Some(apply(if sign == "-" then -n else n, BigInt(10).pow(digits.length)))
      case _ => None

  /** A film's clip clock: `clip = rate * (source - origin) + offset`, all in seconds, and the
    * clip's length.
    */
  final case class Clock(origin: Exact, rate: Exact, offset: Exact, clipSeconds: Exact)

  final case class Interval(scene: Int, start: Exact, end: Exact)
  final case class Film(duration: Exact, scenes: Vector[Interval])

  enum Located:
    /** The film has no clip clock: it is not one a reply may place a time in. */
    case UnknownFilm

    /** Before the clip starts or after it ends. It names the film and selects no scene. */
    case Outside(near: Vector[Int])
    case Scene(scene: Int, near: Vector[Int])

  private val TimeText = "([0-9]{1,2}):([0-5][0-9](?:\\.[0-9]{1,3})?)".r

  /** A time written `m:ss` or `mm:ss`, optionally with up to three decimals of a second, and
    * nothing else: its exact seconds, and the same value as the nearest double.
    */
  def parseTime(text: String): Option[(Exact, Double)] = text match
    case TimeText(minutes, seconds) =>
      Exact.decimal(seconds).map { s =>
        (
          Exact.ofInt(BigInt(minutes) * 60) + s,
          (BigDecimal(minutes) * 60 + BigDecimal(seconds)).toDouble
        )
      }
    case _ => None

  /** Read a clock document: the tolerance and one clock per film. */
  def parseClock(doc: Json): Either[String, (Exact, Map[Int, Clock])] =
    val c = doc.hcursor
    def exact(j: io.circe.ACursor, key: String): Either[String, Exact] =
      j.get[String](key).toOption.flatMap(Exact.decimal).toRight(s"clock: $key is not a decimal")
    for
      _ <- Either.cond(c.get[String]("schema").contains(ClockSchema), (), "not a clip clock")
      tolerance <- exact(c, "toleranceSeconds")
      _ <- Either.cond(tolerance >= Exact.zero, (), "clock: negative tolerance")
      rows <- c.get[Vector[Json]]("collections").left.map(_ => "clock: no collections")
      clocks <- rows.foldLeft[Either[String, Map[Int, Clock]]](Right(Map.empty)) { (acc, row) =>
        val r = row.hcursor
        for
          done <- acc
          film <- r.get[Int]("collection").left.map(_ => "clock: collection is not an integer")
          _ <- Either.cond(!done.contains(film), (), s"clock: collection $film is listed twice")
          origin <- exact(r, "origin")
          rate <- exact(r, "rate")
          offset <- exact(r, "offset")
          clip <- exact(r, "clipSeconds")
          _ <- Either.cond(rate > Exact.zero && clip > Exact.zero, (), "clock: rate or clip length")
        yield done.updated(film, Clock(origin, rate, offset, clip))
      }
    yield (tolerance, clocks)

  /** Build the table for a checked source. `secondsPerTick` is each part's declared timebase.
    *
    * Refuses unless every target film, and no other, has a clock; every row of a film has a time;
    * a film's clock origin is the start of its first target row; no scene that is not a target
    * starts inside the clip; and scene starts increase and end before the clip does.
    */
  def build(
      view: TimedSourceView,
      secondsPerTick: Map[String, Exact],
      tolerance: Exact,
      clocks: Map[Int, Clock]
  ): Either[String, SceneIntervals] =
    val targetFilms =
      view.collectionRefs.collect { case (n, ref) if view.targetCollections.contains(ref) => n }.toSet
    val targetScenes =
      view.groupRefs.collect { case (n, ref) if view.targetGroups.contains(ref) => n }.toSet
    def film(n: Int, clock: Clock): Either[String, Film] =
      val rows = view.segments.filter(_.collection.exists(_.ordinal == n))
      val timed = rows.flatMap { s =>
        for
          group <- s.group
          locus <- s.locus
          scale <- secondsPerTick.get(locus.part)
        yield (group.ordinal, s.target, Exact.ofInt(BigInt(locus.startTick)) * scale)
      }
      if timed.size != rows.size then Left(s"film $n: a row has no time on a declared part")
      else
        val starts = timed.groupMapReduce(_._1)(_._3)((a, b) => if a <= b then a else b)
        val content = timed.filter(_._2).map(_._3)
        if content.isEmpty then Left(s"film $n has no target row")
        else if content.min != clock.origin then
          Left(s"film $n: the clock's origin is not the start of its first target row")
        else if starts.exists((scene, at) => !targetScenes(scene) && at >= clock.origin) then
          Left(s"film $n: a scene that is not a target lies inside the clip")
        else
          val own = starts.keys.filter(targetScenes).toVector.sorted
          val begin = own.map(s => clock.rate * (starts(s) - clock.origin) + clock.offset)
          val bounds = begin :+ clock.clipSeconds
          if bounds.zip(bounds.drop(1)).exists((a, b) => a >= b) then
            Left(s"film $n: scene starts are not increasing inside the clip")
          else
            val from = bounds.updated(0, Exact.zero)
            Right(
              Film(
                clock.clipSeconds,
                own.indices.toVector.map(i => Interval(own(i), from(i), from(i + 1)))
              )
            )
    if clocks.keySet != targetFilms then Left("the clock does not cover exactly the target films")
    else
      clocks.toVector
        .sortBy(_._1)
        .foldLeft[Either[String, Map[Int, Film]]](Right(Map.empty)) { case (acc, (n, clock)) =>
          acc.flatMap(done => film(n, clock).map(done.updated(n, _)))
        }
        .map(new SceneIntervals(tolerance, _))
