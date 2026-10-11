package storymodel4s.pipeline

import java.nio.charset.StandardCharsets
import storymodel4s.core.*

/** Separates an existing admission description from the exact story bytes it describes. */
object AdmittedStoryInput:
  val Format: String = "admitted-story/v1"
  val Boundary: String = "--- BEGIN STORY TEXT ---\n"
  private val fields = Set(
    "Format",
    "Title",
    "Source",
    "Basis",
    "Modifications",
    "Tradition",
    "Admitted by",
    "Text-SHA256"
  )

  /** Refusals contain no source text or header values. */
  enum Refusal:
    case InvalidUtf8, InvalidHeader, MissingBoundary, DigestMismatch, InvalidText,
      RequiresAdmittedMode

  /** A parsed description records provenance; it grants no legal or disclosure authority. */
  final class AdmissionRecord private[AdmittedStoryInput] (
      val fields: Map[String, String],
      val documentChecksum: Checksum,
      val textChecksum: Checksum
  ):
    def title: String = fields("Title")
    override def equals(other: Any): Boolean = other match
      case that: AdmissionRecord =>
        fields == that.fields && documentChecksum == that.documentChecksum && textChecksum == that.textChecksum
      case _ => false
    override def hashCode: Int = (fields, documentChecksum, textChecksum).hashCode
    override def toString: String = s"AdmissionRecord(${documentChecksum.hex},${textChecksum.hex})"

  /** The checked pair binds a separate header record to a body-only text source. */
  final class Loaded private[AdmittedStoryInput] (
      val record: AdmissionRecord,
      val sourcePackage: TextSourcePackage
  ):
    override def equals(other: Any): Boolean = other match
      case that: Loaded => record == that.record
      case _            => false
    override def hashCode: Int = record.hashCode
    override def toString: String = s"AdmittedStoryInput.Loaded(${record.textChecksum.hex})"

  private def utf8(bytes: Array[Byte]): Either[Refusal, String] =
    val text = new String(bytes, StandardCharsets.UTF_8)
    Either.cond(
      java.util.Arrays.equals(bytes, text.getBytes(StandardCharsets.UTF_8)),
      text,
      Refusal.InvalidUtf8
    )

  /** Plain public intake refuses recognized admission headers, including the older unversioned
    * form.
    */
  def plain(bytes: Array[Byte]): Either[Refusal, TextSourcePackage] =
    for
      text <- utf8(bytes)
      header = text.takeWhile(_ != '\u0000').take(16384)
      recognized = header.startsWith("Format: admitted-story/") || header.contains(
        "--- BEGIN STORY TEXT ---"
      ) ||
        (header.linesIterator.exists(_.startsWith("Source:")) && header.linesIterator.exists(
          _.startsWith("Admitted by:")
        ))
      _ <- Either.cond(!recognized, (), Refusal.RequiresAdmittedMode)
      value <- TextSourcePackage.fromUtf8(bytes).left.map(_ => Refusal.InvalidText)
    yield value

  /** Verify the SHA-256 over the exact UTF-8 body before canonicalization or segmentation. */
  def load(bytes: Array[Byte]): Either[Refusal, Loaded] =
    for
      text <- utf8(bytes)
      offset = text.indexOf("\n" + Boundary)
      _ <- Either.cond(offset >= 0, (), Refusal.MissingBoundary)
      header = text.substring(0, offset)
      body = text.substring(offset + 1 + Boundary.length)
      lines = header.split("\n", -1).toVector
      entries <- lines.foldLeft[Either[Refusal, Vector[(String, String)]]](Right(Vector.empty)) {
        (acc, line) =>
          val colon = line.indexOf(':')
          for
            previous <- acc
            _ <- Either.cond(colon > 0 && !line.contains('\r'), (), Refusal.InvalidHeader)
            key = line.substring(0, colon)
            value = line.substring(colon + 1).trim
            _ <- Either.cond(
              fields(key) && value.nonEmpty && !previous.exists(_._1 == key),
              (),
              Refusal.InvalidHeader
            )
          yield previous :+ (key -> value)
      }
      values = entries.toMap
      _ <- Either.cond(
        values.keySet == fields && values
          .get("Format")
          .contains(Format) && lines.head == s"Format: $Format",
        (),
        Refusal.InvalidHeader
      )
      digest <- Checksum.from(values("Text-SHA256")).left.map(_ => Refusal.InvalidHeader)
      _ <- Either.cond(digest == Checksum.ofText(body), (), Refusal.DigestMismatch)
      documentDigest = Checksum.ofText(text)
      metadata = values.map { case (key, value) => s"admitted-story.$key" -> value } +
        ("admitted-story.document-sha256" -> documentDigest.hex)
      source <- StorySource
        .fromText(body, title = Some(values("Title")), metadata = metadata)
        .left
        .map(_ => Refusal.InvalidText)
      prepared <- TextSourcePackage.analyze(source).left.map(_ => Refusal.InvalidText)
    yield new Loaded(new AdmissionRecord(values, documentDigest, digest), prepared)
