package storymodel4s.embed

import cats.{Order, Show}

import storymodel4s.core.{DomainError, OpaqueId, TextSpan}

/** How sensitive an input is. Autobiographical transcripts are `Sensitive` by construction. */
enum Sensitivity:
  case Public
  case Internal
  case Sensitive

object Sensitivity:
  given Order[Sensitivity] = Order.by {
    case Sensitivity.Public    => 0
    case Sensitivity.Internal  => 1
    case Sensitivity.Sensitive => 2
  }

/** Where a provider runs. `Remote` providers never receive `EmbedPayload.Raw` sensitive text. */
enum Locality:
  case Local
  case Remote

/** The most sensitive input a provider is permitted to see as raw text. */
enum PrivacyClass:
  case PublicOnly
  case InternalOk
  case SensitiveOk

  def admits(s: Sensitivity): Boolean = (this, s) match
    case (SensitiveOk, _)                   => true
    case (InternalOk, Sensitivity.Public)   => true
    case (InternalOk, Sensitivity.Internal) => true
    case (PublicOnly, Sensitivity.Public)   => true
    case _                                  => false

object PrivacyPolicyId extends OpaqueId("PrivacyPolicyId")
type PrivacyPolicyId = PrivacyPolicyId.T

/** Identifies a pseudonymization detector algorithm and version in durable evidence. */
object PseudonymizationDetectorId extends OpaqueId("PseudonymizationDetectorId")
type PseudonymizationDetectorId = PseudonymizationDetectorId.T

/** The exact keyed detector configuration a remote policy is willing to trust. */
final class DetectorPolicyIdentity private[embed] (
    val detectorId: PseudonymizationDetectorId,
    val configurationDigest: ReceiptDigest.Keyed
):
  /** Safe policy rendering: algorithm/version and configuration HMAC, never configuration text. */
  def render: String = ReceiptRendering.detectorPolicy(this)

  override def equals(other: Any): Boolean = other match
    case that: DetectorPolicyIdentity =>
      detectorId == that.detectorId && configurationDigest == that.configurationDigest
    case _ => false

  override def hashCode: Int = (detectorId, configurationDigest).##

  override def toString: String = render

/** Detector-relative evidence over one exact canonical text.
  *
  * The detector configuration and source identities are HMACs under `keyId`; only the ordered
  * detected spans remain visible. Construction is confined to embed-core so a detector
  * implementation can find spans but cannot mint its own evidence.
  */
final class PseudonymizationDetection private[embed] (
    val detectorId: PseudonymizationDetectorId,
    val configurationDigest: ReceiptDigest.Keyed,
    val sourceDigest: ReceiptDigest.Keyed,
    val spans: Vector[TextSpan]
):
  /** Detector/configuration identity suitable for a [[RemotePolicy]] allowlist. */
  def policyIdentity: DetectorPolicyIdentity =
    new DetectorPolicyIdentity(detectorId, configurationDigest)

  /** Canonical safe rendering for the enclosing payload receipt. */
  def render: String = ReceiptRendering.detection(this)

  override def equals(other: Any): Boolean = other match
    case that: PseudonymizationDetection =>
      detectorId == that.detectorId && configurationDigest == that.configurationDigest &&
      sourceDigest == that.sourceDigest && spans == that.spans
    case _ => false

  override def hashCode: Int = (detectorId, configurationDigest, sourceDigest, spans).##

  override def toString: String = render

/** One row in the closed whole-word detector algorithm.
  *
  * The pseudonym is both part of configuration identity and the only destination replacement this
  * row can certify. [[PseudonymizationDetector.wholeWordTable]] validates the complete table before
  * constructing a detector.
  */
final case class PseudonymizationTableEntry(
    surface: String,
    pseudonym: String,
    caseInsensitive: Boolean = false
):
  override def toString: String =
    s"PseudonymizationTableEntry(<redacted>, <redacted>, caseInsensitive=$caseInsensitive)"

/** A closed detector whose identity completely determines its span-finding behaviour.
  *
  * The only public factory is [[PseudonymizationDetector.wholeWordTable]]; callers supply typed
  * table data, never an executable finder. The final `detect` method runs embed-core's fixed
  * Unicode whole-word algorithm and alone mints evidence; checked transformations must also use the
  * exact configured pseudonym for every winning table entry.
  */
final class PseudonymizationDetector private (
    val id: PseudonymizationDetectorId,
    configuration: String,
    entries: Vector[PseudonymizationTableEntry]
):
  private def policyIdentityUnder(
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, DetectorPolicyIdentity] =
    PseudonymizationDetector
      .keyedDigest(
        "pseudonymizationDetector/configuration",
        keyId,
        ReceiptRendering.detectorConfiguration(id, configuration),
        keys
      )
      .map(digest => new DetectorPolicyIdentity(id, digest))

  /** Bind this exact algorithm/version and configuration for a remote-policy allowlist. */
  def policyIdentity(
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, DetectorPolicyIdentity] =
    for
      stableKeys <- PseudonymizationDetector.snapshotKeys(keyId, keys)
      identity <- policyIdentityUnder(keyId, stableKeys)
    yield identity

  /** Detect spans in `text` and bind them to this detector under `keyId`. */
  def detect(
      text: String,
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, PseudonymizationDetection] =
    for
      _ <- PseudonymizedText.validateUtf16(text, "pseudonymizationDetector/source")
      stableKeys <- PseudonymizationDetector.snapshotKeys(keyId, keys)
      identity <- policyIdentityUnder(keyId, stableKeys)
      sourceDigest <- PseudonymizationDetector.keyedDigest(
        "pseudonymizationDetector/source",
        keyId,
        ReceiptRendering.detectorSource(id, identity.configurationDigest, text),
        stableKeys
      )
      spans <- detectedSpans(text)
    yield new PseudonymizationDetection(id, identity.configurationDigest, sourceDigest, spans)

  private[storymodel4s] def detectedSpans(text: String): Either[DomainError, Vector[TextSpan]] =
    for
      _ <- PseudonymizedText.validateUtf16(text, "pseudonymizationDetector/source")
      spans = PseudonymizationDetector.detectTable(text, entries)
      _ <- PseudonymizationDetector.validateSpans(text, spans)
    yield spans

  private[embed] def validateTransformation(
      sourceText: String,
      destinationText: String,
      offsets: Vector[(TextSpan, TextSpan)]
  ): Either[DomainError, Unit] =
    val matches = PseudonymizationDetector.detectTableMatches(sourceText, entries)
    if matches.size != offsets.size then
      Left(
        DomainError.InvariantViolation(
          "pseudonymizedText/offsets",
          "detected source spans must have one configured destination replacement"
        )
      )
    else
      matches
        .zip(offsets)
        .zipWithIndex
        .collectFirst {
          case ((matched, (source, _)), offsetIndex) if matched.span != source =>
            DomainError.InvariantViolation(
              s"pseudonymizationDetector/table/${matched.entryIndex}",
              s"mapped source at offset index $offsetIndex does not equal the detector match"
            )
          case ((matched, (_, destination)), offsetIndex)
              if destinationText.substring(destination.start, destination.endExclusive) !=
                matched.pseudonym =>
            DomainError.InvariantViolation(
              s"pseudonymizationDetector/table/${matched.entryIndex}",
              s"mapped destination at offset index $offsetIndex does not equal the configured pseudonym"
            )
        }
        .toLeft(())

  override def toString: String = s"PseudonymizationDetector(${id.value}, <redacted>)"

object PseudonymizationDetector:
  private final case class IndexedEntry(index: Int, entry: PseudonymizationTableEntry)
  private final case class TableMatch(span: TextSpan, pseudonym: String, entryIndex: Int)

  private val WholeWordTableId =
    PseudonymizationDetectorId.unsafe("storymodel4s.whole-word-table/v1")
  private val WholeWordTableVersion = "whole-word-table/v1"

  /** Create the fixed Unicode whole-word detector from typed table data.
    *
    * There is deliberately no factory accepting a function: equal detector identities therefore
    * imply equal span-finding behaviour. Case-insensitive rows compare code points by Unicode
    * simple upper- or lower-case mappings: U+0130 compares equal to ASCII `i`, so `ismail` detects
    * `İSMAIL`.
    */
  def wholeWordTable(
      entries: Vector[PseudonymizationTableEntry]
  ): Either[DomainError, PseudonymizationDetector] =
    validateTable(entries).map { _ =>
      val canonical = canonicalTable(entries)
      new PseudonymizationDetector(WholeWordTableId, canonical, entries)
    }

  private def keyedDigest(
      path: String,
      keyId: KeyId,
      rendering: String,
      keys: SensitiveKeyProvider
  ): Either[DomainError, ReceiptDigest.Keyed] =
    ReceiptDigest
      .keyedUnder(keyId, rendering, keys)
      .left
      .map {
        case error @ EmbedError.NoKey(_) =>
          DomainError.InvariantViolation(PseudonymizedText.KeyPath, error.message)
        case error => DomainError.InvariantViolation(path, error.message)
      }

  private[storymodel4s] def snapshotKeys(
      keyId: KeyId,
      keys: SensitiveKeyProvider
  ): Either[DomainError, SensitiveKeyProvider] =
    SensitiveKeySnapshot
      .capture(keyId, keys)
      .left
      .map(error => DomainError.InvariantViolation(PseudonymizedText.KeyPath, error.message))
      .map(_.provider)

  private def validateTable(
      entries: Vector[PseudonymizationTableEntry]
  ): Either[DomainError, Unit] =
    entries.zipWithIndex
      .foldLeft[Either[DomainError, Unit]](Right(())) { case (validated, (entry, index)) =>
        validated.flatMap { _ =>
          if entry.surface.isEmpty then
            Left(
              DomainError.InvariantViolation(
                s"pseudonymizationDetector/table/$index/surface",
                "detector surfaces must be nonempty"
              )
            )
          else if entry.pseudonym.isEmpty then
            Left(
              DomainError.InvariantViolation(
                s"pseudonymizationDetector/table/$index/pseudonym",
                "relational pseudonyms must be nonempty"
              )
            )
          else
            PseudonymizedText
              .validateUtf16(entry.surface, s"pseudonymizationDetector/table/$index/surface")
              .flatMap(_ =>
                PseudonymizedText.validateUtf16(
                  entry.pseudonym,
                  s"pseudonymizationDetector/table/$index/pseudonym"
                )
              )
        }
      }
      .flatMap { _ =>
        entries
          .combinations(2)
          .collectFirst {
            case Vector(left, right)
                if left.pseudonym != right.pseudonym &&
                  left.surface.length == right.surface.length &&
                  (left.surface == right.surface ||
                    (left.caseInsensitive || right.caseInsensitive) &&
                    regionMatches(left.surface, 0, right.surface, caseInsensitive = true)) =>
              DomainError.InvariantViolation(
                "pseudonymizationDetector/table",
                "overlapping detector surfaces cannot map to multiple pseudonyms"
              )
          }
          .toLeft(())
      }

  private def canonicalTable(entries: Vector[PseudonymizationTableEntry]): String =
    val ordered = canonicalEntries(entries).map(_.entry)
    (Vector(WholeWordTableVersion, s"table|entry-count=${ordered.size}") ++
      ordered.map(entry =>
        s"entry|surface=${ReceiptRendering.esc(entry.surface)}|pseudonym=${ReceiptRendering.esc(entry.pseudonym)}|case-insensitive=${entry.caseInsensitive}"
      )).mkString("\n")

  private def canonicalEntries(
      entries: Vector[PseudonymizationTableEntry]
  ): Vector[IndexedEntry] =
    entries
      .sortBy(entry => (entry.surface, entry.pseudonym, entry.caseInsensitive))
      .zipWithIndex
      .map((entry, index) => IndexedEntry(index, entry))

  private def isWordCodePoint(codePoint: Int): Boolean =
    Character.isLetterOrDigit(codePoint) || (Character.getType(codePoint) match
      case Character.NON_SPACING_MARK | Character.COMBINING_SPACING_MARK |
          Character.ENCLOSING_MARK =>
        true
      case _ => false)

  private def boundaryBefore(text: String, index: Int): Boolean =
    index == 0 || !isWordCodePoint(text.codePointBefore(index))

  private def boundaryAfter(text: String, index: Int): Boolean =
    index >= text.length || !isWordCodePoint(text.codePointAt(index))

  private def regionMatches(
      text: String,
      at: Int,
      surface: String,
      caseInsensitive: Boolean
  ): Boolean =
    if !caseInsensitive then text.regionMatches(false, at, surface, 0, surface.length)
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

  private def occurrences(
      text: String,
      surface: String,
      caseInsensitive: Boolean
  ): Vector[TextSpan] =
    val out = Vector.newBuilder[TextSpan]
    var index = 0
    val width = surface.length
    while index + width <= text.length do
      if regionMatches(text, index, surface, caseInsensitive) && boundaryBefore(
          text,
          index
        ) && boundaryAfter(text, index + width)
      then
        out += TextSpan.unsafe(index, index + width)
        index += width
      else index += Character.charCount(text.codePointAt(index))
    out.result()

  private def detectTable(
      text: String,
      entries: Vector[PseudonymizationTableEntry]
  ): Vector[TextSpan] =
    detectTableMatches(text, entries).map(_.span)

  private def detectTableMatches(
      text: String,
      entries: Vector[PseudonymizationTableEntry]
  ): Vector[TableMatch] =
    canonicalEntries(entries)
      .sortBy(indexed =>
        (
          -indexed.entry.surface.length,
          indexed.entry.surface,
          indexed.entry.pseudonym,
          indexed.entry.caseInsensitive,
          indexed.index
        )
      )
      .flatMap { indexed =>
        val entry = indexed.entry
        occurrences(text, entry.surface, entry.caseInsensitive).map(span =>
          TableMatch(span, entry.pseudonym, indexed.index)
        )
      }
      .sortBy(matched => (matched.span.start, -matched.span.length, matched.entryIndex))
      .foldLeft(Vector.empty[TableMatch]) { (accepted, candidate) =>
        if accepted.exists(_.span.overlaps(candidate.span)) then accepted else accepted :+ candidate
      }

  private def validateSpans(text: String, spans: Vector[TextSpan]): Either[DomainError, Unit] =
    var previousEnd = 0
    var index = 0
    var problem: Option[DomainError] = None
    while index < spans.size && problem.isEmpty do
      val span = spans(index)
      val path = s"pseudonymizationDetector/spans/$index"
      problem = PseudonymizedText
        .validateSpan(text, span, path, allowEmpty = false)
        .orElse(
          Option.when(span.start < previousEnd)(
            DomainError
              .InvariantViolation(path, "detected spans must be ordered and nonoverlapping")
          )
        )
      previousEnd = span.endExclusive
      index += 1
    problem.toLeft(())

/** Text carrying an explained pseudonymization transformation under a named policy and key.
  *
  * This value carries no re-identification key; the key is a separately held `ReidentificationKey`
  * in the interview module. Construction is private so unchecked text cannot be laundered into
  * [[EmbedPayload.Sanitized]] by choosing that enum case. A checked value retains the exact source
  * detection and the zero-residual destination detection that certified it.
  */
final class PseudonymizedText private[embed] (
    val policyId: PrivacyPolicyId,
    val keyId: KeyId,
    val text: String,
    val offsets: Vector[(TextSpan, TextSpan)],
    /** Source receipt whose detected spans exactly equal the source side of `offsets`. */
    val sourceDetection: Option[PseudonymizationDetection],
    /** Destination receipt from the same detector; a certified payload has no detected spans. */
    val destinationDetection: Option[PseudonymizationDetection],
    /** Keyed identity of this payload under its pseudonymization key (ADR 0001 D6): an HMAC over
      * the canonical `pseudo/v2` rendering, minted by [[PseudonymizedText.checked]] and never a
      * plain hash of the sanitized text.
      */
    val digest: ReceiptDigest.Keyed,
    private val detectorCertified: Boolean
):
  private[embed] def isDetectorCertified: Boolean =
    detectorCertified && sourceDetection
      .zip(destinationDetection)
      .exists { case (source, destination) =>
        source.policyIdentity == destination.policyIdentity && destination.spans.isEmpty
      }

  override def equals(other: Any): Boolean = other match
    case that: PseudonymizedText =>
      policyId == that.policyId && keyId == that.keyId && text == that.text &&
      offsets == that.offsets && sourceDetection == that.sourceDetection &&
      destinationDetection == that.destinationDetection && digest == that.digest &&
      detectorCertified == that.detectorCertified
    case _ => false

  override def hashCode: Int =
    (
      policyId,
      keyId,
      text,
      offsets,
      sourceDetection,
      destinationDetection,
      digest,
      detectorCertified
    ).##

  override def toString: String =
    s"PseudonymizedText(<redacted>, length=${text.length}, replacements=${offsets.size})"

object PseudonymizedText:
  /** Invariant path reported when the pseudonymization key `keyId` is unavailable in `keys`. */
  val KeyPath: String = "pseudonymization/key"

  /** Check an exact offset map and detector evidence, then mint the payload's keyed identity.
    *
    * Each pair maps a nonempty source span to the exact pseudonym configured for the detector's
    * winning table entry. Text outside mapped spans must be unchanged, so callers cannot hide an
    * unexplained transformation or present raw text with an empty/fictitious map. Validation errors
    * report only positions, canonical table-entry indices, and invariant names, never source or
    * destination text. The source receipt must be the exact result of the supplied detector and its
    * spans must equal the mapped source spans; the same detector is run independently on the
    * destination and must find zero spans. A missing key fails closed with
    * `InvariantViolation(KeyPath, …)`: no certified `PseudonymizedText` exists without a keyed
    * digest.
    */
  def checked(
      policyId: PrivacyPolicyId,
      keyId: KeyId,
      sourceText: String,
      text: String,
      offsets: Vector[(TextSpan, TextSpan)],
      sourceDetection: PseudonymizationDetection,
      detector: PseudonymizationDetector,
      keys: SensitiveKeyProvider
  ): Either[DomainError, PseudonymizedText] =
    for
      _ <- validateUtf16(sourceText, "source")
      _ <- validateUtf16(text, "destination")
      _ <- validateMap(sourceText, text, offsets)
      stableKeys <- PseudonymizationDetector.snapshotKeys(keyId, keys)
      verifiedSource <- detector.detect(sourceText, keyId, stableKeys)
      _ <- Either.cond(
        sourceDetection == verifiedSource,
        (),
        DomainError.InvariantViolation(
          "pseudonymizedText/sourceDetection",
          "source detection receipt is not bound to this detector and source"
        )
      )
      _ <- Either.cond(
        sourceDetection.spans.nonEmpty,
        (),
        DomainError.InvariantViolation(
          "pseudonymizedText/sourceDetection/spans",
          "a detector-certified transformation requires at least one detected source span"
        )
      )
      _ <- Either.cond(
        sourceDetection.spans == offsets.map(_._1),
        (),
        DomainError.InvariantViolation(
          "pseudonymizedText/sourceDetection/spans",
          "detected source spans must equal mapped source spans"
        )
      )
      _ <- detector.validateTransformation(sourceText, text, offsets)
      destinationDetection <- detector.detect(text, keyId, stableKeys)
      _ <- Either.cond(
        destinationDetection.spans.isEmpty,
        (),
        DomainError.InvariantViolation(
          "pseudonymizedText/destinationDetection",
          s"destination retains ${destinationDetection.spans.size} detector-recognized spans"
        )
      )
      digest <- ReceiptDigest
        .keyedUnder(
          keyId,
          ReceiptRendering.pseudonymizedV2(
            policyId,
            keyId,
            text,
            offsets,
            sourceDetection,
            destinationDetection
          ),
          stableKeys
        )
        .left
        .map(e => DomainError.InvariantViolation(KeyPath, e.message))
    yield new PseudonymizedText(
      policyId,
      keyId,
      text,
      offsets,
      Some(sourceDetection),
      Some(destinationDetection),
      digest,
      detectorCertified = true
    )

  private[embed] def validateMap(
      sourceText: String,
      destinationText: String,
      offsets: Vector[(TextSpan, TextSpan)]
  ): Either[DomainError, Unit] =
    if sourceText.isEmpty && destinationText.isEmpty && offsets.isEmpty then Right(())
    else if sourceText == destinationText then
      Left(
        DomainError.InvariantViolation(
          "pseudonymizedText/text",
          "pseudonymization must change nonempty source text"
        )
      )
    else if offsets.isEmpty then
      Left(
        DomainError.InvariantViolation(
          "pseudonymizedText/offsets",
          "a nonempty transformation requires at least one replacement"
        )
      )
    else
      var sourceCursor = 0
      var destinationCursor = 0
      var index = 0
      var problem: Option[DomainError] = None

      while index < offsets.size && problem.isEmpty do
        val (source, destination) = offsets(index)
        val path = s"pseudonymizedText/offsets/$index"
        problem = validateSpan(sourceText, source, s"$path/source", allowEmpty = false)
          .orElse(
            validateSpan(destinationText, destination, s"$path/destination", allowEmpty = true)
          )
          .orElse(
            Option.when(source.start < sourceCursor)(
              DomainError.InvariantViolation(
                s"$path/source",
                "source replacements must be ordered and nonoverlapping"
              )
            )
          )
          .orElse(
            Option.when(destination.start < destinationCursor)(
              DomainError.InvariantViolation(
                s"$path/destination",
                "destination replacements must be ordered and nonoverlapping"
              )
            )
          )
          .orElse(
            Option.when(
              sourceText.substring(sourceCursor, source.start) !=
                destinationText.substring(destinationCursor, destination.start)
            )(
              DomainError.InvariantViolation(
                path,
                "text outside replacement spans must be unchanged"
              )
            )
          )
          .orElse {
            val sourceSurface = sourceText.substring(source.start, source.endExclusive)
            val replacement = destinationText.substring(destination.start, destination.endExclusive)
            Option.when(containsCaseInsensitively(replacement, sourceSurface))(
              DomainError.InvariantViolation(
                path,
                "a replacement must not contain a case variant of its source span text"
              )
            )
          }
        sourceCursor = source.endExclusive
        destinationCursor = destination.endExclusive
        index += 1

      problem
        .orElse(
          Option.when(
            sourceText.substring(sourceCursor) != destinationText.substring(destinationCursor)
          )(
            DomainError.InvariantViolation(
              "pseudonymizedText/offsets",
              "text outside replacement spans must be unchanged"
            )
          )
        )
        .toLeft(())

  private def containsCaseInsensitively(text: String, surface: String): Boolean =
    var start = 0
    var found = surface.isEmpty
    while !found && start < text.length do
      var textIndex = start
      var surfaceIndex = 0
      var same = true
      while same && textIndex < text.length && surfaceIndex < surface.length do
        val textCodePoint = text.codePointAt(textIndex)
        val surfaceCodePoint = surface.codePointAt(surfaceIndex)
        same = textCodePoint == surfaceCodePoint ||
          Character.toUpperCase(textCodePoint) == Character.toUpperCase(surfaceCodePoint) ||
          Character.toLowerCase(textCodePoint) == Character.toLowerCase(surfaceCodePoint)
        textIndex += Character.charCount(textCodePoint)
        surfaceIndex += Character.charCount(surfaceCodePoint)
      found = same && surfaceIndex == surface.length
      start += Character.charCount(text.codePointAt(start))
    found

  private[embed] def validateSpan(
      text: String,
      span: TextSpan,
      path: String,
      allowEmpty: Boolean
  ): Option[DomainError] =
    if !allowEmpty && span.isEmpty then
      Some(DomainError.InvalidSpan(span.start, span.endExclusive, s"$path must be nonempty"))
    else if span.endExclusive > text.length then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path exceeds text length ${text.length}"
        )
      )
    else if !isCodePointBoundary(text, span.start) then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path starts inside a UTF-16 surrogate pair"
        )
      )
    else if !isCodePointBoundary(text, span.endExclusive) then
      Some(
        DomainError.InvalidSpan(
          span.start,
          span.endExclusive,
          s"$path ends inside a UTF-16 surrogate pair"
        )
      )
    else None

  private[embed] def validateUtf16(text: String, path: String): Either[DomainError, Unit] =
    var index = 0
    var problem: Option[DomainError] = None
    while index < text.length && problem.isEmpty do
      val char = text.charAt(index)
      if Character.isHighSurrogate(char) then
        if index + 1 < text.length && Character.isLowSurrogate(text.charAt(index + 1)) then
          index += 2
        else
          problem = Some(
            DomainError.InvalidSpan(index, index + 1, s"$path contains an unpaired high surrogate")
          )
      else if Character.isLowSurrogate(char) then
        problem = Some(
          DomainError.InvalidSpan(index, index + 1, s"$path contains an unpaired low surrogate")
        )
      else index += 1
    problem.toLeft(())

  private[embed] def isCodePointBoundary(text: String, offset: Int): Boolean =
    offset >= 0 && offset <= text.length &&
      (offset == 0 || offset == text.length ||
        !(Character.isHighSurrogate(text.charAt(offset - 1)) &&
          Character.isLowSurrogate(text.charAt(offset))))

/** Outcome of a policy evaluation; recorded in receipts, never containing payload text. */
enum PolicyDecision:
  case Allowed(policyId: PrivacyPolicyId, capability: RemoteCapability)
  case LocalOnly(id: RequestId, policyId: Option[PrivacyPolicyId], reason: String)
  case Denied(id: RequestId, policyId: Option[PrivacyPolicyId], reason: String)

  /** A required store key was absent: the item (or, with `None`, the batch) failed closed. */
  case KeyUnavailable(id: Option[RequestId], keyId: KeyId)

  def render: String = this match
    case Allowed(p, c)         => s"allowed:${p.value}:${c.render}"
    case LocalOnly(id, p, r)   => s"local-only:${id.value}:${p.fold("-")(_.value)}:$r"
    case Denied(id, p, r)      => s"denied:${id.value}:${p.fold("-")(_.value)}:$r"
    case KeyUnavailable(id, k) => s"key-unavailable:${id.fold("-")(_.value)}:${k.value}"

/** A time-bounded authorization whose public construction path is [[RemotePolicy.evaluate]].
  *
  * Time is supplied by the caller (epoch millis) so the module stays clock-free and testable. The
  * fields are the evidence of a policy decision, not caller-asserted transport metadata.
  */
final class RemoteCapability private[embed] (
    val provider: ProviderFingerprint,
    val model: PolicyModelIdentity,
    val purpose: String,
    val policyId: PrivacyPolicyId,
    val expiresAtEpochMillis: Long,
    val budgetTokens: Long,
    val detectorIdentity: DetectorPolicyIdentity,
    val payloadDigest: ReceiptDigest.Keyed
):
  def render: String =
    s"cap:${provider.render.take(12)}:${model.render}:$purpose:${policyId.value}:$expiresAtEpochMillis:$budgetTokens:${detectorIdentity.render}:${payloadDigest.render.takeRight(12)}"

  override def equals(other: Any): Boolean = other match
    case that: RemoteCapability =>
      provider == that.provider &&
      model == that.model &&
      purpose == that.purpose &&
      policyId == that.policyId &&
      expiresAtEpochMillis == that.expiresAtEpochMillis &&
      budgetTokens == that.budgetTokens &&
      detectorIdentity == that.detectorIdentity &&
      payloadDigest == that.payloadDigest
    case _ => false

  override def hashCode(): Int =
    (
      provider,
      model,
      purpose,
      policyId,
      expiresAtEpochMillis,
      budgetTokens,
      detectorIdentity,
      payloadDigest
    ).hashCode

  override def toString: String = s"RemoteCapability(${render})"

/** A remote policy: which providers/models/purposes and detector configs may send sanitized data.
  */
final case class RemotePolicy(
    id: PrivacyPolicyId,
    allowedProviders: Set[ProviderFingerprint],
    allowedModels: Set[PolicyModelIdentity],
    allowedPurposes: Set[String],
    allowedDetectors: Set[DetectorPolicyIdentity],
    maxBudgetTokens: Long,
    ttlMillis: Long
)

/** The only request type a remote transport accepts.
  *
  * [[PseudonymizedText]] keeps raw text out of the payload type. The sole public construction path,
  * [[RemotePolicy.evaluate]], additionally proves that the sanitized payload, provider, model,
  * purpose, detector, budget, and expiry passed one named policy together.
  */
final class AuthorizedRemoteRequest private[embed] (
    val id: RequestId,
    val space: GeometryId,
    val payload: PseudonymizedText,
    val capability: RemoteCapability
):
  override def equals(other: Any): Boolean = other match
    case that: AuthorizedRemoteRequest =>
      id == that.id &&
      space == that.space &&
      payload == that.payload &&
      capability == that.capability
    case _ => false

  override def hashCode(): Int = (id, space, payload, capability).hashCode

  override def toString: String =
    s"AuthorizedRemoteRequest(id=${id.value}, space=${space.value}, payload=<redacted>, " +
      s"capability=${capability.render})"

object RemotePolicy:
  /** Evaluate the request's sanitized payload against a policy for an embedder and purpose. The
    * provider, version-qualified model identity, and target space are derived from that embedder.
    * Returns the authorized request bound to that exact payload and a capability, or a typed denial
    * (never an exception, never the payload text in the reason).
    */
  def evaluate[F[_]](
      policy: RemotePolicy,
      request: EmbedRequest,
      embedder: Embedder[F],
      purpose: String,
      nowEpochMillis: Long,
      estimatedTokens: Long
  ): Either[PolicyDecision.Denied, AuthorizedRemoteRequest] =
    def deny(reason: String): Either[PolicyDecision.Denied, AuthorizedRemoteRequest] =
      Left(PolicyDecision.Denied(request.id, Some(policy.id), reason))
    val info = embedder.info
    val provider = info.provider
    val model = info.policyModelIdentity
    embedder.space(request.space) match
      case None => deny("requested space is not advertised by embedder")
      case Some(space) if space.provider != provider =>
        deny("advertised space provider does not match embedder provider")
      case Some(_) =>
        request.payload match
          case EmbedPayload.Raw(_, _)          => deny("request payload is not pseudonymized")
          case EmbedPayload.Sanitized(payload) =>
            if !payload.isDetectorCertified then deny("payload lacks detector certification")
            else
              (payload.sourceDetection, payload.destinationDetection) match
                case (Some(source), Some(destination)) =>
                  val identities = Vector(source.policyIdentity, destination.policyIdentity)
                  identities.find(identity => !policy.allowedDetectors.contains(identity)) match
                    case Some(unlisted) =>
                      deny(s"detector configuration not allowed: ${unlisted.render}")
                    case None =>
                      val detectorIdentity = source.policyIdentity
                      if payload.policyId != policy.id then
                        deny("payload pseudonymized under a different policy")
                      else if !policy.allowedProviders.contains(provider) then
                        deny("provider not allowed")
                      else if !policy.allowedModels.contains(model) then deny("model not allowed")
                      else if !policy.allowedPurposes.contains(purpose) then
                        deny("purpose not allowed")
                      else if estimatedTokens < 0 || estimatedTokens > policy.maxBudgetTokens then
                        deny("budget exceeded")
                      else if policy.ttlMillis <= 0 then deny("policy has no validity window")
                      else
                        val cap = new RemoteCapability(
                          provider,
                          model,
                          purpose,
                          policy.id,
                          nowEpochMillis + policy.ttlMillis,
                          estimatedTokens,
                          detectorIdentity,
                          payload.digest
                        )
                        Right(
                          new AuthorizedRemoteRequest(request.id, request.space, payload, cap)
                        )
                case _ => deny("payload lacks detector certification")

  given Show[PolicyDecision] = Show.show(_.render)
