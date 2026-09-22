package storymodel4s.recall

import cats.syntax.all.*
import storymodel4s.core.*
import storymodel4s.recall.RecallGraphStatus.Checked

object RecallWordId extends OpaqueId("RecallWordId")
type RecallWordId = RecallWordId.T

/** Identity of parsed words in one exact input artifact; this does not certify a parser. */
final class WordIdPolicy private (val name: String, val artifact: Checksum)
object WordIdPolicy:
  def inputArtifact(artifact: Checksum): WordIdPolicy =
    new WordIdPolicy("input-artifact-sha256+zero-based-parsed-word-index/v1", artifact)

enum UnassignedWordReason:
  case NotInAnyUnitSpan
enum WordMembership:
  case Member(unit: RecallUnitId)
  case Unassigned(reason: UnassignedWordReason)
enum DecompositionReason:
  case NoDecompositionDetector
enum DecompositionStatus:
  case NotAssessed(reason: DecompositionReason)
enum MappingSemantics:
  case CategoricalReferent

/** Binds unit IDs to the exact unitization, independently of word parsing. */
final class SegmentationId private (val digest: Checksum):
  override def equals(other: Any): Boolean = other match
    case that: SegmentationId => digest == that.digest
    case _                    => false
  override def hashCode: Int = digest.hashCode
object SegmentationId:
  def of(graph: RecallGraph[Checked]): SegmentationId =
    new SegmentationId(
      InventoryRender.digest(
        Vector(
          "segmentation/v1",
          graph.transcript.canonicalChecksum.hex,
          graph.transcript.canonicalText,
          InventoryRender.sequence(
            graph.ordered.map(u =>
              InventoryRender.sequence(
                Vector(
                  u.id.value,
                  InventoryRender.span(u.span)
                )
              )
            )
          )
        )
      )
    )

type RecallWord = RecallInventory.Word
type InventoryUnit = RecallInventory.Unit

/** Complete immutable accounting for one caller-supplied parsing and checked unitization. */
final class RecallInventory private (
    val transcript: Checksum,
    val segmentation: SegmentationId,
    val idPolicy: WordIdPolicy,
    val words: Vector[RecallWord],
    val units: Vector[InventoryUnit],
    val membership: Map[RecallWordId, WordMembership]
):
  def describes(graph: RecallGraph[Checked]): Boolean =
    transcript == graph.transcript.canonicalChecksum && segmentation == SegmentationId.of(graph)

  def digest: Checksum = InventoryRender.digest(
    Vector(
      "recall-inventory/v1",
      transcript.hex,
      segmentation.digest.hex,
      idPolicy.name,
      idPolicy.artifact.hex,
      InventoryRender.sequence(
        words.map(w =>
          InventoryRender.sequence(
            Vector(
              w.id.value,
              w.index.toString,
              w.span.start.toString,
              w.span.endExclusive.toString,
              membership(w.id) match
                case WordMembership.Member(unit) =>
                  InventoryRender.sequence(Vector("member", unit.value))
                case WordMembership.Unassigned(reason) =>
                  InventoryRender.sequence(Vector("unassigned", reason.toString))
            )
          )
        )
      ),
      InventoryRender.sequence(
        units.map(u =>
          InventoryRender.sequence(
            Vector(
              u.id.value,
              u.ordinal.toString,
              InventoryRender.span(u.span),
              InventoryRender.sequence(u.words.map(_.value)),
              u.decomposition.toString,
              u.semantics.toString
            )
          )
        )
      )
    )
  )

object RecallInventory:
  final class Word private (val id: RecallWordId, val index: Int, val span: TextSpan)
  object Word:
    private[RecallInventory] def derived(id: RecallWordId, index: Int, span: TextSpan): Word =
      new Word(id, index, span)

  final class Unit private (
      val id: RecallUnitId,
      val ordinal: Int,
      val span: SpanSet,
      val words: Vector[RecallWordId]
  ):
    val decomposition: DecompositionStatus =
      DecompositionStatus.NotAssessed(DecompositionReason.NoDecompositionDetector)
    val semantics: MappingSemantics = MappingSemantics.CategoricalReferent
  object Unit:
    private[RecallInventory] def derived(u: RecallUnit, words: Vector[RecallWordId]): Unit =
      new Unit(u.id, u.ordinal, u.span, words)

  /** Word spans must be nonempty, ordered, disjoint and within canonical UTF-16 text. A word
    * overlapping two units refuses; words outside every unit remain explicit unassigned rows.
    */
  def of(
      graph: RecallGraph[Checked],
      wordSpans: Vector[TextSpan],
      policy: WordIdPolicy
  ): Either[DomainError, RecallInventory] =
    val text = graph.transcript.canonicalText
    def boundary(i: Int): Boolean =
      i == 0 || i == text.length ||
        !(Character.isHighSurrogate(text.charAt(i - 1)) && Character.isLowSurrogate(text.charAt(i)))
    val malformed = wordSpans.zipWithIndex.exists { case (span, i) =>
      span.isEmpty || span.endExclusive > text.length ||
      !boundary(span.start) || !boundary(span.endExclusive) ||
      (i > 0 && wordSpans(i - 1).endExclusive > span.start)
    }
    if malformed then
      Left(
        DomainError.InvariantViolation(
          "recall/inventory/words",
          "word spans must be nonempty, ordered, disjoint and within UTF-16 boundaries"
        )
      )
    else
      val words = wordSpans.zipWithIndex.map { case (span, index) =>
        Word.derived(RecallWordId.unsafe(s"${policy.artifact.hex}:word:$index"), index, span)
      }
      words
        .traverse { word =>
          val owners = graph.ordered.filter(_.span.spans.toVector.exists(_.overlaps(word.span)))
          owners match
            case Vector() =>
              Right(word.id -> WordMembership.Unassigned(UnassignedWordReason.NotInAnyUnitSpan))
            case Vector(owner) => Right(word.id -> WordMembership.Member(owner.id))
            case _             =>
              Left(
                DomainError.InvariantViolation(
                  "recall/inventory/membership",
                  s"word ${word.index} overlaps multiple units"
                )
              )
        }
        .map { entries =>
          val membership = entries.toMap
          val units = graph.ordered.map(u =>
            Unit.derived(
              u,
              words.collect {
                case w if membership(w.id) == WordMembership.Member(u.id) => w.id
              }
            )
          )
          new RecallInventory(
            graph.transcript.canonicalChecksum,
            SegmentationId.of(graph),
            policy,
            words,
            units,
            membership
          )
        }

/** Length-delimited tokens preserve boundaries and optional presence on every backend. */
private object InventoryRender:
  def sequence(values: Vector[String]): String =
    values.size.toString + ":" + values.map(v => s"${v.length}:$v").mkString
  // Core IDs and StorySource currently admit unpaired surrogate code units. UTF-8 encoding
  // replaces those, so hashing raw tokens would merge different admitted identities. Hash an
  // ASCII rendering of every code unit; supplement the legacy transcript hash above with text.
  def digest(values: Vector[String]): Checksum =
    Checksum.ofText(sequence(values).iterator.map(c => f"${c.toInt}%04x").mkString)
  def span(value: SpanSet): String = sequence(value.refs.toVector.map { ref =>
    sequence(
      Vector(
        ref.span.start.toString,
        ref.span.endExclusive.toString,
        ref.unit.fold(sequence(Vector("none")))(id => sequence(Vector("some", id.value)))
      )
    )
  })
