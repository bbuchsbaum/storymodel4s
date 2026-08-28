package storymodel4s.core

import org.scalacheck.{Arbitrary, Gen}

object Gens:
  val idString: Gen[String] =
    Gen.nonEmptyListOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf(':', '-', '_', '.'))).map(_.mkString)

  val textSpan: Gen[TextSpan] = for
    a <- Gen.chooseNum(0, 500)
    b <- Gen.chooseNum(0, 500)
  yield TextSpan.unsafe(math.min(a, b), math.max(a, b))

  val spanRef: Gen[SpanRef] = for
    s <- textSpan
    unit <- Gen.option(idString.map(SurfaceUnitId.unsafe))
  yield SpanRef(unit, s)

  val spanSet: Gen[SpanSet] = Gen.nonEmptyListOf(spanRef).map(rs => SpanSet.of(rs).get)

  val probability: Gen[Probability] = Gen.chooseNum(0.0, 1.0).map(Probability.unsafe)

  val credence: Gen[Credence] = Gen.oneOf(
    Gen.chooseNum(-10.0, 10.0).map(Credence.unsafeRaw),
    for
      s <- Gen.chooseNum(-10.0, 10.0)
      p <- probability
    yield Credence.calibrated(s, p, "isotonic-v1").toOption.get
  )

  val paragraphText: Gen[String] =
    val word = Gen.chooseNum(1, 8).flatMap(n => Gen.listOfN(n, Gen.alphaLowerChar)).map(_.mkString)
    val sentence = for
      n <- Gen.chooseNum(1, 8)
      ws <- Gen.listOfN(n, word)
      term <- Gen.oneOf(".", "!", "?")
    yield ws.mkString(" ").capitalize + term
    Gen.chooseNum(1, 6).flatMap(n => Gen.listOfN(n, sentence)).map(_.mkString(" "))

  val storyText: Gen[String] =
    Gen.chooseNum(1, 5).flatMap(n => Gen.listOfN(n, paragraphText)).map(_.mkString("\n\n"))

  given Arbitrary[TextSpan] = Arbitrary(textSpan)
  given Arbitrary[SpanRef] = Arbitrary(spanRef)
  given Arbitrary[SpanSet] = Arbitrary(spanSet)
  given Arbitrary[Probability] = Arbitrary(probability)
  given Arbitrary[Credence] = Arbitrary(credence)
