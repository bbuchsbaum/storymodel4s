package storymodel4s.embed.onnx

import java.nio.file.Path
import storymodel4s.core.Checksum

/** Reuse the existing fixed lookup graph; this is an engineering fixture, never learned gold. */
object HistoricalParityFixture:
  val modelChecksum = Checksum.unsafe(
    "3e21121e42719ab61e888e8dbb9559591278701bec9193729bd2d1de38daca02"
  )
  val tokenizerChecksum = Checksum.unsafe(
    "0237b6bbc55d00142f9fa04557641e8ce5ea3abaf89e957aac5bca7dbd3bbf9a"
  )
  val model: OnnxSentenceModel =
    OnnxSentenceModel.testFixture(modelChecksum, tokenizerChecksum, 4, 4)

  def open(root: Path): OnnxSentenceEmbedder =
    val fixture = root.resolve("embed-onnx/src/test/resources/fixture")
    OnnxSentenceEmbedder
      .open(
        model,
        OnnxSentenceArtifacts(fixture.resolve("model.onnx"), fixture.resolve("tokenizer.json"))
      )
      .fold(e => throw new AssertionError(e.message), identity)
