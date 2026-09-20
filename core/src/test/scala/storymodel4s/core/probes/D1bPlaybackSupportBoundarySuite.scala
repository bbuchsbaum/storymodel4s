package storymodel4s.core.probes

import munit.FunSuite
import scala.compiletime.testing.typeCheckErrors
import storymodel4s.core.PlaybackSupport

class D1bPlaybackSupportBoundarySuite extends FunSuite:
  private val dependency = classOf[PlaybackSupport]
  test("accepting control: checked playback union permits explicit intervals and points"):
    assert(dependency.getName.nonEmpty)
    assert(typeCheckErrors("""
      import storymodel4s.core.*
      def checked(axis: PresentationAxisId, intervals: Vector[PlaybackInterval], points: Vector[PlaybackInstant]) =
        PlaybackSupport.of(axis, intervals, points)
    """).isEmpty)

  test("playback union constructor stays private even inside core"):
    assert(typeCheckErrors("""
      import storymodel4s.core.*
      def forged(axis: PresentationAxisId, intervals: Vector[PlaybackInterval], points: Vector[PlaybackInstant]) =
        new PlaybackSupport(axis, intervals, points)
    """).nonEmpty)

  test("playback union has no Product or Mirror construction door"):
    assert(typeCheckErrors("""
      import storymodel4s.core.PlaybackSupport
      def product(s: PlaybackSupport): Product = s
    """).nonEmpty)
    assert(typeCheckErrors("summon[scala.deriving.Mirror.ProductOf[storymodel4s.core.PlaybackSupport]]").nonEmpty)
