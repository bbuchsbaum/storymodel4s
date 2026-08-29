package storymodel4s.consumer

import munit.FunSuite

import storymodel4s.bench.MetricObservation
import storymodel4s.features.{MissingReason, UndefinedReason}

/** Public-package proof that metric observations remain inspectable without exposing an unsafe
  * finite-value constructor.
  */
class MetricObservationPublicSuite extends FunSuite:
  private def render(observation: MetricObservation): String =
    observation.fold(
      onIneligible = "ineligible",
      onObserved = value => f"observed:$value%.1f",
      onMissing = reason => s"missing:$reason"
    )

  test("a public consumer can eliminate all three observation states") {
    assertEquals(render(MetricObservation.Ineligible), "ineligible")
    assertEquals(render(MetricObservation.observed(0.5)), "observed:0.5")
    assertEquals(
      render(MetricObservation.Missing(MissingReason.ProviderAbstained)),
      s"missing:${MissingReason.ProviderAbstained}"
    )
  }

  test("the public finite constructor still converts invalid values to typed missing") {
    assertEquals(
      render(MetricObservation.observed(Double.NaN)),
      s"missing:${MissingReason.Undefined(UndefinedReason.NotFinite)}"
    )
  }
