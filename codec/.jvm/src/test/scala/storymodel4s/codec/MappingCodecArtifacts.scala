package storymodel4s.codec

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import storymodel4s.core.Checksum
import storymodel4s.laws.MappingMiniature

/** Explicit local evidence writer; ordinary tests do not write artifacts. */
object MappingCodecArtifacts:
  def main(args: Array[String]): Unit =
    require(args.length == 1, "one output directory is required")
    val directory = Path.of(args(0))
    val _ = Files.createDirectories(directory)
    val cases = Vector(
      "miniature" -> MappingMiniature.record,
      "sherlock-shaped" -> MappingCompositionFixture.record,
      "historical" -> MappingCodecFixture.record(),
      "large-coordinates" -> MappingCompositionFixture.largeCoordinates
    )
    val manifest = cases
      .map { (name, record) =>
        val encoded = MappingCodecs.encode(record)
        val _ =
          Files.writeString(directory.resolve(name + ".json"), encoded, StandardCharsets.UTF_8)
        s"$name ${record.digest.hex} ${Checksum.ofText(encoded).hex} ${encoded.length}"
      }
      .mkString("name recordDigest canonicalSha256 bytes\n", "\n", "\n")
    val _ = Files.writeString(directory.resolve("manifest.txt"), manifest, StandardCharsets.UTF_8)
    println(manifest)
