package storymodel4s.codec

import cats.syntax.all.*
import io.circe.Json
import storymodel4s.align.StructuralSelection
import storymodel4s.core.Checksum

/** A declared weighted member surrounds unchanged record bytes with exact rational identity. */
object WeightedMappingExchange:
  val SchemaVersion: String = "weighted-mapping-exchange/v1"

  /** Only a genuine selected execution can produce this manifest and its member table. */
  final class Bundle private[WeightedMappingExchange] (val files: Vector[(String, String)]):
    def manifest: String = files.head._2
    def digest: Checksum = Checksum.ofText(manifest)
    override def equals(other: Any): Boolean = other match
      case b: Bundle => files == b.files
      case _         => false
    override def hashCode: Int = files.hashCode
    override def toString: String = s"WeightedMappingExchange(${digest.short()})"

  def encode(selection: StructuralSelection): Either[MappingExchange.Error, Bundle] =
    for
      record <- selection.record.left.map(e =>
        MappingExchange.Error.Mapping(MappingCodecError.Rejected(e))
      )
      encoded <- MappingExchange.encode(record)
    yield
      val e = selection.execution
      val recordProfile =
        if selection.weight.isReference then e.reference.profile.fingerprint
        else if selection.weight.isReconstruction then e.reconstruction.profile.fingerprint
        else selection.profile.fingerprint
      val columns = Vector(
        "lambda",
        "profile_id",
        "record_profile_id",
        "selection_id",
        "execution_id",
        "shared_evidence_id",
        "measure_kind",
        "record_digest"
      )
      val values = Vector(
        selection.weight.toString,
        selection.profile.fingerprint.hex,
        recordProfile.hex,
        selection.identity.hex,
        e.identity.hex,
        e.reference.evidence.identity.checksum.hex,
        selection.kind.toString,
        record.digest.hex
      )
      def row(values: Vector[String]): String =
        values.map(v => "\"" + v.replace("\"", "\"\"") + "\"").mkString("\t") + "\n"
      val table = row(columns) + row(values)
      val files = encoded.files.map((name, content) => s"record--$name" -> content) :+
        ("members.tsv" -> table)
      val metadata =
        Json.obj(columns.zip(values).map((name, value) => name -> Json.fromString(value))*)
      val manifest = MappingJson.print(
        Json.obj(
          "schemaVersion" -> Json.fromString(SchemaVersion),
          "metadata" -> metadata,
          "files" -> Json.fromValues(files.map { (name, content) =>
            Json.obj(
              "path" -> Json.fromString(name),
              "sha256" -> Json.fromString(Checksum.ofText(content).hex),
              "bytes" -> Json.fromString(content.getBytes("UTF-8").length.toString)
            )
          })
        )
      )
      new Bundle(("manifest.json" -> manifest) +: files)

  /** Rebuild against the originating weighted selection; self-described hashes confer no authority.
    */
  def decode(
      files: Vector[(String, String)],
      expected: StructuralSelection
  ): Either[MappingExchange.Error, StructuralSelection] =
    for
      rebuilt <- encode(expected)
      _ <- Either.cond(
        files.map(_._1).distinct.size == files.size &&
          files.map(_._1).toSet == rebuilt.files.map(_._1).toSet,
        (),
        MappingExchange.Error.ContentMismatch("file-set")
      )
      supplied = files.toMap
      _ <- rebuilt.files.traverse_ { (name, text) =>
        Either.cond(supplied(name) == text, (), MappingExchange.Error.ContentMismatch(name))
      }
    yield expected
