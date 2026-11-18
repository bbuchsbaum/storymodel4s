// Copy this file into codec/src/test/scala/storymodel4s/align/ in an isolated clone, then run:
// codecJVM/Test/runMain storymodel4s.align.CaptionExactParityProbe <cases.json>
package storymodel4s.align

import cats.data.NonEmptyVector
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import io.circe.{Decoder, Json}
import io.circe.parser.parse
import storymodel4s.align.*
import storymodel4s.align.bridge.TextSourceView
import storymodel4s.core.*
import storymodel4s.recall.*

/** Executes the production exact-support join against the portable synthetic parity fixture. */
object CaptionExactParityProbe:
  private def right[A](value: Either[?, A]): A =
    value.fold(e => throw RuntimeException(e.toString), identity)
  private def field[A: Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(throw _, identity)
  private final case class Caption(id: String, part: String, start: Long, end: Long, text: String)
  private final case class Target(
      id: String,
      part: String,
      intervals: Vector[(Long, Long)],
      points: Vector[Long]
  )

  private def target(j: Json) = Target(
    field[String](j, "id"),
    field[String](j, "part"),
    field[Vector[Vector[String]]](j, "intervals").map {
      case Vector(a, b) => a.toLong -> b.toLong
      case other        => throw RuntimeException(s"invalid fixture interval: $other")
    },
    field[Vector[String]](j, "points").map(_.toLong)
  )

  private def outcome(
      targets: Vector[Target],
      captions: Vector[Caption],
      bundles: Map[String, SourceBundle]
  ): Either[String, Vector[(String, String)]] =
    // Identical target text isolates caption lookup from target text and target position.
    val text = right(TextSourcePackage.fromText(Vector.fill(targets.size)("XX.").mkString("\n")))
    val textView = right(TextSourceView.of(text, SurfaceUnitKind.Sentence))
    val view = InMemorySourceView(textView.nodes, Map.empty, None, textView.scoringLength)
    val targetByRef = view.nodes.zip(targets).map { (node, target) => node.ref -> target }.toMap
    val physical = targetByRef.map { (ref, target) =>
      val bundle = bundles(target.part)
      val intervals = target.intervals.map { (start, end) =>
        right(PlaybackInterval.on(bundle.primaryAxis, start, end))
      }
      val anchors =
        intervals.map(i =>
          EvidenceAnchor.MediaTime(
            bundle.id,
            bundle.streams.head.id,
            bundle.primaryAxis.id,
            PlaybackIntervalSet.one(i)
          )
        ) ++
          target.points.map(p =>
            EvidenceAnchor.MediaPoint(
              bundle.id,
              bundle.streams.head.id,
              right(PlaybackInstant.on(bundle.primaryAxis, p))
            )
          )
      ref -> SourceSupportStatus.located(
        TypedSupport.Anchored(right(EvidenceSupport.of(bundle, anchors)))
      )
    }
    val representation = right(
      SourceRepresentation.of(
        view,
        NonEmptyVector.fromVectorUnsafe(bundles.values.toVector.map(BundleEntry.media)),
        None,
        physical
      )
    )
    val evidenceByPart = captions.groupBy(_.part).toVector.map { (part, rows) =>
      val bundle = bundles(part)
      val framesIdentity = Checksum.ofText(s"frames-$part")
      val framesChecksum = Checksum.ofText(s"frames-bytes-$part")
      val receipt = right(
        SourceDerivationReceipt.of("caption-parity", part, Vector(framesIdentity, framesChecksum))
      )
      right(
        CaptionEvidence.of(
          bundle,
          framesIdentity,
          framesChecksum,
          receipt,
          rows.zipWithIndex.map { (c, i) =>
            CaptionEvidence.Caption.of(
              c.text,
              Vector(i),
              right(PlaybackInterval.on(bundle.primaryAxis, c.start, c.end))
            ) match
              case Right(value) => value
              case Left(error)  => throw RuntimeException(error.toString)
          }
        )
      )
    }
    val scoring = right(
      CaptionScoring.multipart(
        1.0,
        evidenceByPart,
        Checksum.ofText("caption-parity-correspondence")
      )
    )
    val recall = RecallSegmenter.segment(right(StorySource.fromText("AA. BB.")))
    scoring.session(recall, view, representation).left.map(_.message).map { session =>
      // Distinct AA/BB probe queries make the private session binding observable. At blend 1 the
      // caption actually bound to a target is the only caption query with zero lexical distance.
      val observed = view.nodes.zip(targets).flatMap { (node, target) =>
        captions.flatMap { caption =>
          val query = recall.units(if caption.text == "AA" then 0 else 1)
          val score = right(
            StrictScoring.evaluate(session.channel, query, node, view, None, Some(session))
          )._4.toOption.get
          if score == 0.0 then Vector(target.id -> caption.id) else Vector.empty
        }
      }
      require(
        observed.size == captions.size,
        "bound caption association was not uniquely observable"
      )
      observed
    }

  private def row(id: String, result: Either[String, Vector[(String, String)]]) = result match
    case Right(matches) =>
      Json.obj(
        "id" -> Json.fromString(id),
        "outcome" -> Json.fromString("matched"),
        "matches" -> Json.fromValues(
          matches.map((t, c) =>
            Json.obj("target" -> Json.fromString(t), "caption" -> Json.fromString(c))
          )
        )
      )
    case Left(message) if message.endsWith("ambiguous caption support") =>
      Json.obj(
        "id" -> Json.fromString(id),
        "outcome" -> Json.fromString("refused"),
        "reason" -> Json.fromString("ambiguous-caption-support")
      )
    case Left(message) if message.endsWith("caption has no unique exact target") =>
      Json.obj(
        "id" -> Json.fromString(id),
        "outcome" -> Json.fromString("refused"),
        "reason" -> Json.fromString("caption-has-no-unique-exact-target")
      )
    case Left(other) => throw RuntimeException(other)

  def main(args: Array[String]): Unit =
    require(args.length == 1, "usage: CaptionExactParityProbe <cases.json>")
    val fixture = parse(Files.readString(Paths.get(args(0)))).fold(throw _, identity)
    require(field[String](fixture, "schema") == "storymodel.caption-exact-parity/v1")
    val bundles = field[Vector[Json]](fixture, "parts").map { part =>
      val id = field[String](part, "id")
      id -> right(
        SourceBundle.filmEdition(
          EditionId.unsafe(field[String](part, "physical_source")),
          Checksum.ofText(field[String](part, "physical_source")),
          field[String](part, "start").toLong,
          field[String](part, "end").toLong,
          RationalTimebase.Millisecond
        )
      )
    }.toMap
    val captions = field[Vector[Json]](fixture, "captions").map(c =>
      Caption(
        field[String](c, "id"),
        field[String](c, "part"),
        field[String](c, "start").toLong,
        field[String](c, "end").toLong,
        field[String](c, "text")
      )
    )
    val cases = field[Vector[Json]](fixture, "cases")
    val actualRows = cases.map { c =>
      val ids = field[Vector[String]](c, "caption_ids")
      val selected = ids.flatMap(id => captions.find(_.id == id))
      require(ids.size == selected.size, "unknown caption")
      row(
        field[String](c, "id"),
        outcome(field[Vector[Json]](c, "targets").map(target), selected, bundles)
      )
    }
    val expectedRows = cases.map(c =>
      field[Json](c, "expect").mapObject(_.add("id", Json.fromString(field[String](c, "id"))))
    )
    val actual = Json.obj(
      "schema" -> Json.fromString("storymodel.caption-exact-parity-results/v1"),
      "cases" -> Json.fromValues(actualRows)
    )
    val expected = Json.obj(
      "schema" -> Json.fromString("storymodel.caption-exact-parity-results/v1"),
      "cases" -> Json.fromValues(expectedRows)
    )
    require(
      actual.noSpacesSortKeys
        .getBytes(UTF_8)
        .sameElements(expected.noSpacesSortKeys.getBytes(UTF_8)),
      "production output differs from fixture bytes"
    )
    println(actual.noSpacesSortKeys)
