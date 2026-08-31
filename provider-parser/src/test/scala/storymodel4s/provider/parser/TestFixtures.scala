package storymodel4s.provider.parser

import cats.Id
import storymodel4s.amr.schema.{FrameLexicon, StarterLexicon}
import storymodel4s.core.*

private[parser] object TestFixtures:
  val source: StorySource = StorySource
    .fromText("The boy wants to go. A girl sleeps.")
    .fold(error => throw new IllegalArgumentException(error.message), identity)
  val atlas: SurfaceAtlas = SurfaceAnalyzer.analyze(source)

  val inputs: Vector[ParserSentenceInput] = atlas.sentences.zipWithIndex.map { (sentence, index) =>
    ParserSentenceInput
      .fromAtlas(ParserRequestId.unsafe(s"request-$index"), atlas, sentence.id)
      .fold(error => throw new IllegalArgumentException(error.message), identity)
  }

  val batch: ParserBatch = ParserBatch
    .validated(inputs)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  private def artifact(component: RuntimeComponent, name: String): RuntimeArtifact =
    RuntimeArtifact
      .from(
        component,
        version = "v1",
        source = s"https://example.test/$name",
        checksum = Checksum.ofText(name),
        licenseEvidence = "Apache-2.0 test fixture"
      )
      .fold(error => throw new IllegalArgumentException(error.message), identity)

  val runtime: PinnedRuntime = PinnedRuntime
    .from(
      provider = "test-parser",
      model = "test-amr",
      version = "v1",
      artifacts = Vector(
        artifact(RuntimeComponent.ParserSource, "source"),
        artifact(RuntimeComponent.ParserPackage, "package"),
        artifact(RuntimeComponent.Checkpoint, "checkpoint"),
        artifact(RuntimeComponent.BaseImage, "image"),
        artifact(RuntimeComponent.Dependency("bart.large"), "bart")
      ),
      additionalRequired = Set(RuntimeComponent.Dependency("bart.large"))
    )
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val config: ParserConfig = ParserConfig
    .from(Map("beam" -> "1"), seed = Some(42L), timeoutMillis = 5000L)
    .fold(error => throw new IllegalArgumentException(error.message), identity)

  val lexicon: FrameLexicon = StarterLexicon.lexicon

  val wantPenman: String =
    "(w / want-01~e.2 :ARG0 (b / boy~e.1) :ARG1 (g / go-02~e.4 :ARG0 b))"
  val sleepPenman: String = "(s / sleep-01~e.2 :ARG0 (g / girl~e.1))"
  val wantAlignments: Vector[(String, Vector[Int])] =
    Vector("w" -> Vector(2), "b" -> Vector(1), "g" -> Vector(4))
  val sleepAlignments: Vector[(String, Vector[Int])] =
    Vector("s" -> Vector(2), "g" -> Vector(1))

  def wireTokens(input: ParserSentenceInput): Vector[WireToken] = input.tokens.map { token =>
    WireToken(token.id, token.span.start, token.span.endExclusive, token.text)
  }

  def modelInput(input: ParserSentenceInput): String = input.tokens.map(_.text).mkString(" ")

  def proposed(
      input: ParserSentenceInput,
      penman: String,
      alignments: Vector[(String, Vector[Int])],
      dialect: ParserAlignmentDialect = ParserAlignmentDialect.ExplicitIndexListV1,
      alignmentSchema: String = ParserEnvelope.MarkerSidecarSchema
  ): WireItem =
    val rows = alignments.zipWithIndex.map { case ((providerNodeId, indices), ordinal) =>
      WireMarkerAlignment(ordinal, providerNodeId, indices)
    }
    WireItem(
      input.id,
      modelInput(input),
      wireTokens(input),
      WireItemResult.Proposed(penman, alignmentSchema, dialect.wireName, rows)
    )

  def wantItem(input: ParserSentenceInput = inputs(0)): WireItem =
    proposed(input, wantPenman, wantAlignments)

  def sleepItem(input: ParserSentenceInput = inputs(1)): WireItem =
    proposed(input, sleepPenman, sleepAlignments)

  def response(items: Vector[WireItem], fingerprint: Fingerprint = runtime.fingerprint): String =
    ParserEnvelope.encodeResponse(
      WireResponse(
        ParserEnvelope.ResultSchema,
        fingerprint,
        config.checksum,
        items,
        WireDiagnostics(12L, Some(0), None, 0L)
      )
    )

  def successResponse: String =
    response(Vector(wantItem(), sleepItem()))

  final class ScriptedTransport(initial: Vector[Either[TransportFailure, String]])
      extends ParserTransport[Id]:
    private var remaining = initial
    var calls: Int = 0

    def exchange(
        requestJson: String,
        timeoutMillis: Long
    ): Either[TransportFailure, String] =
      calls += 1
      remaining match
        case head +: tail =>
          remaining = tail
          head
        case _ =>
          Left(TransportFailure.Io(ProviderFailureCode.from("script-exhausted").toOption.get))

  final class MemoryCache extends ParserCache[Id]:
    private var values = Map.empty[ParserCacheKey, CachedParserProposal]
    var gets: Int = 0
    var puts: Int = 0

    def get(key: ParserCacheKey): Option[CachedParserProposal] =
      gets += 1
      values.get(key)

    def put(key: ParserCacheKey, value: CachedParserProposal): Unit =
      puts += 1
      values = values.updated(key, value)

    def overwrite(key: ParserCacheKey, value: CachedParserProposal): Unit =
      values = values.updated(key, value)
