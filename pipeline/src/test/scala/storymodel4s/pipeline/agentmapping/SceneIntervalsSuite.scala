package storymodel4s.pipeline.agentmapping

import io.circe.Json
import munit.FunSuite

/** The rule that turns a time in a film into a scene: exact at every boundary, and refused when the
  * clock does not fit the source.
  */
class SceneIntervalsSuite extends FunSuite:
  import SceneIntervals.{Exact, Interval, Located}
  import TimeStudyFixture.*

  private def q(text: String): Exact = Exact.decimal(text).get
  private def at(film: Int, time: String): Located =
    intervals.locate(BigInt(film), SceneIntervals.parseTime(time).get._1)

  test("scenes tile each clip from 0 to its end, on the film's own clock"):
    assertEquals(
      intervals.films(1).scenes,
      Vector(Interval(2, q("0"), q("20.5")), Interval(3, q("20.5"), q("45")))
    )
    // Half speed: twenty source seconds are ten clip seconds.
    assertEquals(
      intervals.films(3).scenes,
      Vector(Interval(5, q("0"), q("5")), Interval(6, q("5"), q("20")))
    )
    assertEquals(intervals.films.keySet, Set(1, 3))

  test("a time on a boundary belongs to the scene that starts there, and is near both"):
    assertEquals(at(1, "0:20.499"), Located.Scene(2, Vector(2, 3)))
    assertEquals(at(1, "0:20.5"), Located.Scene(3, Vector(2, 3)))
    assertEquals(at(1, "0:10"), Located.Scene(2, Vector(2)))
    assertEquals(at(1, "0:00"), Located.Scene(2, Vector(2)))

  test("the end of the clip is inside the last scene; any later time is outside"):
    assertEquals(at(3, "0:20"), Located.Scene(6, Vector(6)))
    assertEquals(at(3, "0:20.001"), Located.Outside(Vector(6)))
    assertEquals(at(3, "0:22"), Located.Outside(Vector(6)))
    assertEquals(at(3, "0:22.001"), Located.Outside(Vector.empty))

  test("the tolerance is inclusive and measured to the interval"):
    assertEquals(at(1, "0:18.5"), Located.Scene(2, Vector(2, 3)))
    assertEquals(at(1, "0:18.499"), Located.Scene(2, Vector(2)))
    assertEquals(at(1, "0:22.5"), Located.Scene(3, Vector(2, 3)))
    assertEquals(at(1, "0:22.501"), Located.Scene(3, Vector(3)))

  test("a film with no clock is unknown: the cartoon, a missing film, a number too large"):
    val t = SceneIntervals.parseTime("0:05").get._1
    assertEquals(intervals.locate(BigInt(2), t), Located.UnknownFilm)
    assertEquals(intervals.locate(BigInt(9), t), Located.UnknownFilm)
    assertEquals(intervals.locate(BigInt("99999999999999999999"), t), Located.UnknownFilm)

  test("a time is m:ss with at most three decimals, and nothing else"):
    assertEquals(SceneIntervals.parseTime("1:05").map(_._1), Some(q("65")))
    assertEquals(SceneIntervals.parseTime("12:05.25"), Some((q("725.25"), 725.25)))
    assertEquals(SceneIntervals.parseTime("0:00.001").map(_._2), Some(0.001))
    Vector(
      "65",
      "1:5",
      "1:60",
      "1:05.1234",
      " 1:05",
      "1:05 ",
      "100:05",
      "1:05:00",
      "-1:05",
      "١:05",
      ""
    )
      .foreach(text => assertEquals(SceneIntervals.parseTime(text), None, text))

  test("exact decimals: no rounding, and equal values are equal"):
    assertEquals(q("0.996") * q("1000"), q("996"))
    assertEquals(q("0.50"), q("0.5"))
    assertEquals(q("-0.7") + q("0.7"), Exact.zero)
    assert(q("0.1") + q("0.2") == q("0.3"))
    Vector("1.", ".5", "1e3", "0x10", "1,5", "").foreach(t =>
      assertEquals(Exact.decimal(t), None, t)
    )
    assertEquals(Exact.ratio(BigInt(0), BigInt(1)), None)

  test("a clock whose origin is not the film's first target row is refused"):
    assertEquals(
      intervalsOf(clock = clockJson(origin1 = "0")),
      Left("film 1: the clock's origin is not the start of its first target row")
    )
    assertEquals(
      intervalsOf(clock = clockJson(origin1 = "10.5")),
      Left("film 1: the clock's origin is not the start of its first target row")
    )

  test("the clock must cover exactly the target films"):
    val refusal = Left("the clock does not cover exactly the target films")
    assertEquals(intervalsOf(clock = clockJson(films = Vector(1))), refusal)
    assertEquals(intervalsOf(clock = clockJson(films = Vector(1, 2, 3))), refusal)

  test("a scene that is not a target may not lie inside the clip"):
    // Scene 3 loses its only target row: it is now a non-target inside film 1's clip.
    val lines = rows().updated(2, "3,A title card.,a,300,450,3,scene 3,,,1,Harbour film,no")
    assertEquals(
      intervalsOf(view = source(lines)),
      Left("film 1: a scene that is not a target lies inside the clip")
    )

  test("scene starts must increase and end before the clip does"):
    assertEquals(
      intervalsOf(clock = clockJson(clip1 = "20.5")),
      Left("film 1: scene starts are not increasing inside the clip")
    )
    assert(intervalsOf(clock = clockJson(clip1 = "20.501")).isRight)

  test("every row needs a time on a part whose timebase is declared"):
    assertEquals(
      intervalsOf(scales = Map.empty),
      Left("film 1: a row has no time on a declared part")
    )

  test("a clock document is refused when it is not one, repeats a film, or is not decimal"):
    def edit(f: Json => Json) = SceneIntervals.parseClock(f(clockJson()))
    assertEquals(
      edit(_.mapObject(_.add("schema", Json.fromString("x")))).left.toOption,
      Some("not a clip clock")
    )
    assertEquals(
      SceneIntervals.parseClock(clockJson(films = Vector(1, 1))).left.toOption,
      Some("clock: collection 1 is listed twice")
    )
    assertEquals(
      edit(_.mapObject(_.add("toleranceSeconds", Json.fromInt(2)))).left.toOption,
      Some("clock: toleranceSeconds is not a decimal")
    )
    assertEquals(
      edit(_.mapObject(_.add("toleranceSeconds", Json.fromString("-1")))).left.toOption,
      Some("clock: negative tolerance")
    )

  test("the dictionary a reader checks against is in exact fractions"):
    val film = intervals.json.hcursor.downField("films").downArray
    assertEquals(film.get[String]("durationSeconds").toOption, Some("45/1"))
    assertEquals(
      film.downField("scenes").downArray.get[String]("end").toOption,
      Some("41/2")
    )
    assertEquals(intervals.json.hcursor.get[String]("toleranceSeconds").toOption, Some("2/1"))
