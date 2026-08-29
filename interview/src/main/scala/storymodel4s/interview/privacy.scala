package storymodel4s.interview

import storymodel4s.core.*
import storymodel4s.embed.*

/** One detector entry: a surface form and its stable relational pseudonym.
  *
  * Matching is case-sensitive and whole-word by default. `caseInsensitive` is opt-in because a name
  * that is also an ordinary word ("Will", "Mark", "Grace") would otherwise rewrite the word
  * wherever it occurs; an entry that takes that risk must say so.
  */
final case class PseudonymEntry(
    surface: String,
    pseudonym: String,
    caseInsensitive: Boolean = false
):
  override def toString: String =
    s"PseudonymEntry(<redacted>, caseInsensitive=$caseInsensitive)"

/** A pseudonymized interview source with an exact map back to the original surface axis.
  *
  * Why separate this from [[ReidentificationKey]]: this value contains only the sanitized source,
  * its remote payload, and positional information. It can support evidence projection without
  * carrying any original surface form.
  */
final class PseudonymizedTranscript private[interview] (
    val source: StorySource,
    val payload: PseudonymizedText,
    val originalLength: Int
):
  private def deltaBefore(original: Int): Int =
    payload.offsets
      .takeWhile(_._1.endExclusive <= original)
      .lastOption
      .fold(0) { case (from, to) => to.endExclusive - from.endExclusive }

  /** Map an original start offset onto the sanitized surface axis. */
  def mapOffset(original: Int): Int =
    if original >= originalLength then payload.text.length - (originalLength - original)
    else
      payload.offsets.find(_._1.contains(original)) match
        case Some((_, target)) => target.start
        case None              => original + deltaBefore(original)

  /** Map an original exclusive end onto the sanitized surface axis. */
  def mapOffsetEnd(originalEnd: Int): Int =
    if originalEnd <= 0 then 0
    else if originalEnd >= originalLength then payload.text.length - (originalLength - originalEnd)
    else
      payload.offsets.find(_._1.contains(originalEnd - 1)) match
        case Some((_, target)) => target.endExclusive
        case None              => originalEnd + deltaBefore(originalEnd)

  /** Project an original evidence span onto the sanitized surface axis. */
  def mapSpan(span: TextSpan): Either[DomainError, TextSpan] =
    if span.isEmpty then TextSpan.of(mapOffset(span.start), mapOffset(span.start))
    else TextSpan.of(mapOffset(span.start), mapOffsetEnd(span.endExclusive))

  override def toString: String =
    s"PseudonymizedTranscript(<redacted>, length=${payload.text.length}, replacements=${payload.offsets.size})"

/** The local-only power to reverse one exact [[PseudonymizedTranscript]].
  *
  * Original surfaces are occurrence-indexed, so several originals may share one relational
  * pseudonym without making reversal ambiguous. Construction and contents stay inside `interview`;
  * rendered diagnostics reveal only counts.
  */
final class ReidentificationKey private (
    private val policyId: PrivacyPolicyId,
    private val keyId: KeyId,
    private val payloadDigest: ReceiptDigest.Keyed,
    private val entries: Vector[ReidentificationKey.Entry],
    private val originalLength: Int
):
  override def toString: String =
    s"ReidentificationKey(<redacted>, replacements=${entries.size})"

object ReidentificationKey:
  private final case class Entry(span: TextSpan, surface: String)

  private[interview] def create(
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      payloadDigest: ReceiptDigest.Keyed,
      originals: Vector[(TextSpan, String)],
      originalLength: Int
  ): ReidentificationKey =
    new ReidentificationKey(
      policyId,
      keyId,
      payloadDigest,
      originals.map(Entry.apply),
      originalLength
    )

  private[interview] def restore(
      transcript: PseudonymizedTranscript,
      key: ReidentificationKey
  ): Either[DomainError, String] =
    val payload = transcript.payload
    val bindingIsExact =
      key.policyId == payload.policyId && key.keyId == payload.keyId &&
        key.payloadDigest == payload.digest && key.originalLength == transcript.originalLength &&
        key.entries.map(_.span) == payload.offsets.map(_._1)
    if !bindingIsExact then
      Left(
        DomainError.InvariantViolation(
          "pseudonymize/reverse",
          "reidentification key is not bound to this payload"
        )
      )
    else
      val restored = new StringBuilder
      var destinationCursor = 0
      var valid = true
      payload.offsets.zip(key.entries).foreach { case ((sourceSpan, destinationSpan), entry) =>
        restored.append(payload.text.substring(destinationCursor, destinationSpan.start))
        if restored.length != sourceSpan.start || entry.surface.length != sourceSpan.length then
          valid = false
        restored.append(entry.surface)
        destinationCursor = destinationSpan.endExclusive
      }
      restored.append(payload.text.substring(destinationCursor))
      if valid && restored.length == transcript.originalLength then Right(restored.toString)
      else
        Left(
          DomainError.InvariantViolation(
            "pseudonymize/reverse",
            "reidentification key shape is inconsistent with the payload"
          )
        )

/** Deterministic relational pseudonymization (design record §73).
  *
  * Why relational pseudonyms: replacing every person with `[PERSON]` collapses identity and
  * destroys coreference; `[PERSON_1]`, `[SISTER_OF_SPEAKER]` keep the graph intact. Matching is
  * longest-surface-first and whole-word. Unicode letters, digits, and combining marks are word
  * characters, checked by code point so supplementary-plane names are not split. Matching uses the
  * exact canonical-text representation: it is normalization- and diacritic-sensitive; the opt-in
  * case-insensitive mode uses locale-independent Unicode simple case matching.
  */
object Pseudonymizer:
  private final case class Match(span: TextSpan, entry: PseudonymEntry)

  private def regionMatches(text: String, at: Int, surface: String, ci: Boolean): Boolean =
    if !ci then text.regionMatches(false, at, surface, 0, surface.length)
    else
      var textIndex = at
      var surfaceIndex = 0
      val textEnd = at + surface.length
      var same = textEnd <= text.length
      while same && textIndex < textEnd && surfaceIndex < surface.length do
        val textCodePoint = text.codePointAt(textIndex)
        val surfaceCodePoint = surface.codePointAt(surfaceIndex)
        same = textCodePoint == surfaceCodePoint ||
          Character.toUpperCase(textCodePoint) == Character.toUpperCase(surfaceCodePoint) ||
          Character.toLowerCase(textCodePoint) == Character.toLowerCase(surfaceCodePoint)
        textIndex += Character.charCount(textCodePoint)
        surfaceIndex += Character.charCount(surfaceCodePoint)
      same && textIndex == textEnd && surfaceIndex == surface.length

  /** Whole-word occurrences through embed-core's closed detector, exposed only for fixture laws. */
  private[interview] def occurrences(
      text: String,
      surface: String,
      caseInsensitive: Boolean
  ): Vector[TextSpan] =
    PseudonymizationDetector
      .wholeWordTable(
        Vector(PseudonymizationTableEntry(surface, "[FIXTURE]", caseInsensitive))
      )
      .flatMap(_.detectedSpans(text))
      .getOrElse(Vector.empty)

  private def detector(
      table: Vector[PseudonymEntry]
  ): Either[DomainError, PseudonymizationDetector] =
    PseudonymizationDetector.wholeWordTable(
      table.map(entry =>
        PseudonymizationTableEntry(entry.surface, entry.pseudonym, entry.caseInsensitive)
      )
    )

  private def matchesForDetection(
      text: String,
      table: Vector[PseudonymEntry],
      spans: Vector[TextSpan]
  ): Either[DomainError, Vector[Match]] =
    val ordered =
      table.sortBy(entry => (-entry.surface.length, entry.surface, entry.pseudonym))
    spans.zipWithIndex.foldLeft[Either[DomainError, Vector[Match]]](Right(Vector.empty)) {
      case (matches, (span, index)) =>
        matches.flatMap { accepted =>
          ordered
            .find(entry =>
              entry.surface.length == span.length &&
                regionMatches(text, span.start, entry.surface, entry.caseInsensitive)
            )
            .map(entry => accepted :+ Match(span, entry))
            .toRight(
              DomainError.InvariantViolation(
                s"pseudonymize/detection/$index",
                "detected span has no canonical table entry"
              )
            )
        }
    }

  /** Bind this exact pseudonym table and case policy for a remote-policy detector allowlist. */
  def detectorPolicyIdentity(
      table: Vector[PseudonymEntry],
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, DetectorPolicyIdentity] =
    for
      stableKeys <- PseudonymizationDetector.snapshotKeys(keyId, keys)
      tableDetector <- detector(table)
      identity <- tableDetector.policyIdentity(keyId, stableKeys)
    yield identity

  /** Produce a sanitized transcript and a separately held local reidentification key.
    *
    * Every source occurrence detected by `table` is replaced, and the same detector must find zero
    * occurrences in the destination. The returned key is bound to the exact payload identity.
    */
  def pseudonymize(
      source: StorySource,
      table: Vector[PseudonymEntry],
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, (PseudonymizedTranscript, ReidentificationKey)] =
    for
      stableKeys <- PseudonymizationDetector.snapshotKeys(keyId, keys)
      detector <- detector(table)
      sourceDetection <- detector.detect(source.canonicalText, keyId, stableKeys)
      _ <- Either.cond(
        sourceDetection.spans.nonEmpty,
        (),
        DomainError.InvariantViolation(
          "pseudonymize/detection",
          "the detector found no replaceable surface"
        )
      )
      matches <- matchesForDetection(source.canonicalText, table, sourceDetection.spans)
      rendered = render(source.canonicalText, matches)
      (text, offsets, originals) = rendered
      sanitized <- StorySource.fromText(
        rawText = text,
        title = None,
        language = source.language,
        metadata = Map(
          "pseudonymized" -> "true",
          "privacyPolicyId" -> policyId.value,
          "keyId" -> keyId.value
        )
      )
      _ <- Either.cond(
        sanitized.canonicalText == text,
        (),
        DomainError.InvariantViolation(
          "pseudonymize/canonical",
          "rewritten text changed under canonicalization; offset map would be inexact"
        )
      )
      payload <- PseudonymizedText.checked(
        policyId,
        keyId,
        source.canonicalText,
        sanitized.canonicalText,
        offsets,
        sourceDetection,
        detector,
        stableKeys
      )
      transcript = new PseudonymizedTranscript(sanitized, payload, source.canonicalText.length)
      key = ReidentificationKey.create(
        policyId,
        keyId,
        payload.digest,
        originals,
        source.canonicalText.length
      )
    yield transcript -> key

  private def render(
      sourceText: String,
      matches: Vector[Match]
  ): (String, Vector[(TextSpan, TextSpan)], Vector[(TextSpan, String)]) =
    val text = new StringBuilder
    val offsets = Vector.newBuilder[(TextSpan, TextSpan)]
    val originals = Vector.newBuilder[(TextSpan, String)]
    var cursor = 0
    matches.foreach { matched =>
      text.append(sourceText.substring(cursor, matched.span.start))
      val targetStart = text.length
      text.append(matched.entry.pseudonym)
      offsets += matched.span -> TextSpan.unsafe(targetStart, text.length)
      originals += matched.span -> sourceText.substring(
        matched.span.start,
        matched.span.endExclusive
      )
      cursor = matched.span.endExclusive
    }
    text.append(sourceText.substring(cursor))
    (text.toString, offsets.result(), originals.result())

  /** Restore the exact original canonical text using the separately held local key. */
  def reverse(
      transcript: PseudonymizedTranscript,
      key: ReidentificationKey
  ): Either[DomainError, String] =
    ReidentificationKey.restore(transcript, key)
