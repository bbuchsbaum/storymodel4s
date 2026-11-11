package storymodel4s.pipeline.agentmapping

import io.circe.Json
import io.circe.jawn.parse
import java.nio.charset.StandardCharsets.UTF_8
import storymodel4s.align.bridge.TimedSourceView
import storymodel4s.pipeline.TimedAnnotationInput

/** An invented two-film source with a clip clock, for conditions that answer with a time.
  *
  * One tick is a tenth of a second. Film 1 opens with a title card (scene 1, before the clip) and
  * has scenes 2 and 3; collection 2 is a cartoon; film 3 has scenes 5 and 6 and a clip that runs at
  * half speed. On the clip clocks: scene 2 is [0, 20.5), scene 3 is [20.5, 45]; scene 5 is [0, 5),
  * scene 6 is [5, 20]. The tolerance is 2 seconds.
  */
object TimeStudyFixture:
  import SceneIntervals.Exact

  val parts: String =
    """[{"part":"a","start":"0","end":"1000","numerator":"1","denominator":"10"}]"""
  def rows(thirdSceneStart: Int = 300, titleTarget: String = "no"): Vector[String] = Vector(
    s"1,A title card.,a,0,100,1,scene 1,,,1,Harbour film,$titleTarget",
    "2,Anna arrived.,a,100,300,2,scene 2,,Anna,1,Harbour film,",
    s"3,Anna sailed.,a,$thirdSceneStart,450,3,scene 3,,Anna,1,Harbour film,",
    "4,A mouse dances.,a,500,600,4,scene 4,,,2,Cartoon,no",
    "5,Bob waited.,a,600,700,5,scene 5,,Bob,3,Station film,",
    "6,Bob left.,a,700,,6,scene 6,,Bob,3,Station film,"
  )
  def source(lines: Vector[String] = rows()): TimedSourceView =
    TimedAnnotationInput
      .prepare(
        (TimedAnnotationInput.HeaderV2
          .mkString(",") +: lines).mkString("", "\n", "\n").getBytes(UTF_8),
        parts.getBytes(UTF_8)
      )
      .fold(e => throw new AssertionError(e.toString), identity)
  lazy val view: TimedSourceView = source()

  def clockJson(
      origin1: String = "10",
      clip1: String = "45",
      films: Vector[Int] = Vector(1, 3)
  ): Json =
    val one =
      s"""{"collection":1,"origin":"$origin1","rate":"1","offset":"0.5","clipSeconds":"$clip1"}"""
    val two = """{"collection":2,"origin":"50","rate":"1","offset":"0","clipSeconds":"10"}"""
    val three = """{"collection":3,"origin":"60","rate":"0.5","offset":"0","clipSeconds":"20"}"""
    val chosen = films.map(Map(1 -> one, 2 -> two, 3 -> three)).mkString(",")
    parse(
      s"""{"schema":"storymodel4s.agentmapping.clip-clock/v1","toleranceSeconds":"2","collections":[$chosen]}"""
    ).fold(e => throw new AssertionError(e.toString), identity)

  val scales: Map[String, Exact] = Map("a" -> Exact.ratio(BigInt(1), BigInt(10)).get)

  def intervalsOf(
      view: TimedSourceView = view,
      clock: Json = clockJson(),
      scales: Map[String, Exact] = scales
  ): Either[String, SceneIntervals] =
    SceneIntervals
      .parseClock(clock)
      .flatMap((tolerance, clocks) => SceneIntervals.build(view, scales, tolerance, clocks))
  lazy val intervals: SceneIntervals =
    intervalsOf().fold(e => throw new AssertionError(e), identity)
