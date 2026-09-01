package docsprobe

import storymodel4s.core.*

/** Public-API-only tour of the source atlas. */
@main def inspectSource(): Unit =
  val source = StorySource
    .fromText(
      "One night two young men left Egulac.",
      title = Some("A short story")
    )
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val atlas = SurfaceAnalyzer.analyze(source)

  println(s"paragraphs: ${atlas.paragraphs.size}")
  println(s"sentences: ${atlas.sentences.size}")
  println(s"tokens: ${atlas.tokens.size}")
  println(s"sentence text: ${atlas.sentences.map(atlas.text).mkString}")
  println(s"sentence span: ${atlas.sentences.head.span}")
  atlas.tokens.foreach(unit => println(s"token ${atlas.text(unit)} ${unit.span}"))
  println(
    s"token at offset 25: ${atlas.unitAt(25, SurfaceUnitKind.Token).map(atlas.text).getOrElse("none")}"
  )
  val overlap = atlas
    .unitsOverlapping(TextSpan.unsafe(20, 28), SurfaceUnitKind.Token)
    .map(atlas.text)
  println(s"tokens overlapping [20, 28): ${overlap.mkString(", ")}")

  val sentence = atlas.sentences.head
  val evidence = SpanSet.one(SpanRef(Some(sentence.id), sentence.span))
  println(s"evidence covered length: ${evidence.coveredLength}")
  println(s"evidence unit count: ${evidence.units.size}")
