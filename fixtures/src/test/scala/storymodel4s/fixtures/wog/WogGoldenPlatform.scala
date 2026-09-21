package storymodel4s.fixtures.wog

/** The backend this test build runs on, selecting its platform-labelled `hsmm/v4` WOG golden.
  *
  * Scala.js and Scala Native each report their own `java.vm.name` ("Scala.js", "Scala Native");
  * anything else is a JVM. A misread label cannot pass silently: the backend's live bytes would be
  * compared with another backend's pin and the golden court would fail. (A per-platform source file
  * would also work, but `tools/reference-scope.sh` refuses changed `.js`/`.native` sources.)
  */
private[wog] object WogGoldenPlatform:
  val current: WogGoldenBackend =
    Option(System.getProperty("java.vm.name")).getOrElse("") match
      case "Scala.js"     => WogGoldenBackend.JS
      case "Scala Native" => WogGoldenBackend.Native
      case _              => WogGoldenBackend.JVM
