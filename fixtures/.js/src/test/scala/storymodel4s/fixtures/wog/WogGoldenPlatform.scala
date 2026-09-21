package storymodel4s.fixtures.wog

/** The backend this test build runs on, selecting its platform-labelled hsmm/v4 WOG golden. It is a
  * per-platform source file rather than a runtime probe, so the label cannot drift from the build.
  */
private[wog] object WogGoldenPlatform:
  val current: WogGoldenBackend = WogGoldenBackend.JS
