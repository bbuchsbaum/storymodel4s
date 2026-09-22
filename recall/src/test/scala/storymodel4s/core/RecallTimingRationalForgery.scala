package storymodel4s.core

/** Test-only attacker in the legacy rational constructor's visibility scope. */
object RecallTimingRationalForgery:
  def make(numerator: Long, denominator: Long): ExactRational =
    new ExactRational(numerator, denominator)
