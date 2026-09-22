package storymodel4s.core

/** Exercise the package-internal representation boundary without adding a production escape hatch.
  */
object ScannerRationalForgery:
  def make(n: Long, d: Long): ExactRational = new ExactRational(n, d)
