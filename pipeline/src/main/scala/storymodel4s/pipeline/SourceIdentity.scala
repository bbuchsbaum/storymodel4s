package storymodel4s.pipeline

import io.circe.{ACursor, Json}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, LinkOption, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import storymodel4s.core.*
import storymodel4s.document.*

/** ADR 0027 §5: the resolver run over a producer's source-identity inputs.
  *
  * Why a command and not a library type: the producer is outside Scala and proposes; this command
  * is the only place its proposals meet the checked factories of ADR 0024, so every mention,
  * presence claim and link is rebuilt here and every refusal is written down rather than dropped.
  */
object SourceIdentity:
  val InputSchema: String = "source-identity-inputs/v1"
  val GraphSchema: String = "source-identity-graph/v1"
  val Usage: String = "sourceIdentity build INPUTS OUTPUT"

  /** Input roles a recall-blind run may read (ADR 0027 §1); anything else refuses the run. The
    * recognizer's `asr-transcript` carries its words, segments and source identity in one file.
    */
  val InputRoles: Set[String] =
    Set(
      "media",
      "annotation",
      "asr-transcript",
      "asr-words",
      "asr-segments",
      "role-labels",
      "model-recordings"
    )

  private type Result[A] = Either[String, A]

  private def str(c: ACursor, field: String): Result[String] =
    c.downField(field).as[String].left.map(_ => s"missing or invalid $field")
  private def long(c: ACursor, field: String): Result[Long] =
    c.downField(field).as[Long].left.map(_ => s"missing or invalid $field")
  private def arr(c: ACursor, field: String): Result[Vector[Json]] =
    c.downField(field).as[Vector[Json]].left.map(_ => s"missing or invalid $field")
  private def traverse[A, B](as: Vector[A])(f: A => Result[B]): Result[Vector[B]] =
    as.foldLeft[Result[Vector[B]]](Right(Vector.empty))((acc, a) =>
      acc.flatMap(bs => f(a).map(bs :+ _))
    )
  private def domain[A](e: Either[DomainError, A]): Result[A] = e.left.map(_.message)

  private def streamsOn(
      axis: PresentationAxisId,
      specs: Vector[((StreamKind, Checksum, Long), StreamId)],
      start: Long,
      tb: RationalTimebase
  ): Result[Vector[SourceStream]] =
    traverse(specs) { case ((kind, sum, stop), sid) =>
      domain(AxisExtent.playbackTicks(start, stop, tb)).flatMap(extent =>
        domain(SourceStream.of(sid, kind, sum, axis, extent, Some(tb), Vector.empty))
      )
    }

  /** The bundle with one picture and one audio stream on the edition's playback axis. */
  private def bundle(edition: ACursor): Result[SourceBundle] =
    for
      id <- str(edition, "edition").flatMap(e => domain(EditionId.from(e)))
      tb <- arr(edition, "timebase").flatMap {
        case Vector(n, d) =>
          (n.as[Long], d.as[Long]) match
            case (Right(a), Right(b)) => domain(RationalTimebase.of(a, b))
            case _                    => Left("invalid timebase")
        case _ => Left("timebase must be [numerator, denominator]")
      }
      start <- long(edition, "start")
      end <- long(edition, "end")
      specs <- arr(edition, "streams").flatMap(traverse(_) { j =>
        val c = j.hcursor
        for
          kind <- str(c, "kind").flatMap {
            case "picture" => Right(StreamKind.Picture)
            case "audio"   => Right(StreamKind.Audio)
            case other     => Left(s"unsupported stream kind $other")
          }
          sum <- str(c, "checksum").flatMap(h => domain(Checksum.from(h)))
          stop <- long(c, "end")
        yield (kind, sum, stop)
      })
      _ <- Either.cond(
        specs.map(_._1).sortBy(_.toString) == Vector(StreamKind.Audio, StreamKind.Picture),
        (),
        "the edition needs exactly one picture and one audio stream"
      )
      ids <- traverse(specs)(s => domain(StreamId.from(ContentAddress.of("stream", s._2.hex))))
      // Streams name the axis they are on; the axis is content-addressed over the streams. As in
      // `SourceBundle.filmEdition`, a placeholder axis yields the drafts the real axis is derived
      // from, and the streams are then rebuilt on it. The picture stream is the authority track.
      placeholder <- domain(
        SourceBundleId.from(ContentAddress.of("bundle", "source-identity-placeholder", id.value))
      )
      draftAxis <- domain(PresentationAxis.editionPlayback(placeholder, id, start, end, tb))
      drafts <- streamsOn(draftAxis.id, specs.zip(ids), start, tb)
      authority = specs.zip(ids).collect { case ((StreamKind.Picture, _, _), sid) => sid }
      axis <- domain(SourceBundle.editionPlaybackAxis(id, drafts, authority, start, end, tb))
      real <- streamsOn(axis.id, specs.zip(ids), start, tb)
      built <- domain(
        SourceBundle.of(Some(id), SourceKind.FilmEdition, real, axis, authority, Vector.empty)
      )
    yield built

  private def stream(b: SourceBundle, kind: String): Result[SourceStream] =
    val wanted = kind match
      case "picture" => Right(StreamKind.Picture)
      case "audio"   => Right(StreamKind.Audio)
      case other     => Left(s"unsupported stream $other")
    wanted.flatMap(k => b.streams.find(_.kind == k).toRight(s"no $kind stream"))

  private def media(b: SourceBundle, c: ACursor): Result[EvidenceAnchor.MediaTime] =
    for
      s <- str(c, "stream").flatMap(stream(b, _))
      spans <- arr(c, "intervals").flatMap(traverse(_) { j =>
        j.as[Vector[Long]] match
          case Right(Vector(a, z)) => domain(PlaybackInterval.on(b.primaryAxis, a, z))
          case _                   => Left("an interval is [start, endExclusive]")
      })
      set <- domain(PlaybackIntervalSet.of(spans))
    yield EvidenceAnchor.MediaTime(b.id, s.id, b.primaryAxis.id, set)

  private def mention(b: SourceBundle, j: Json): Result[ReferentMention] =
    val c = j.hcursor
    for
      id <- str(c, "id").flatMap(i => domain(ReferentMentionId.from(i)))
      origin <- str(c, "origin").flatMap {
        case "visual"   => Right(MentionOrigin.Visual)
        case "dialogue" =>
          str(c, "context").flatMap(x => domain(ContextId.from(x))).map(MentionOrigin.Dialogue(_))
        case other => Left(s"unsupported origin $other")
      }
      anchor <- media(b, c)
      support <- domain(EvidenceSupport.of(b, Vector(anchor)))
      name <- c.downField("name").as[Option[String]] match
        case Right(Some(n)) => ProperName.of(n).map(Some(_)).left.map(_.toString)
        case Right(None)    => Right(None)
        case Left(_)        => Left("invalid name")
      built <- ReferentMention.anchored(id, origin, b, support, name).left.map(_.toString)
    yield built

  private def presence(b: SourceBundle, j: Json): Result[PresenceClaim] =
    val c = j.hcursor
    for
      _ <- str(c, "basis").flatMap {
        case "human-reviewed" => Right(())
        case other => Left(s"presence basis $other is not admitted in a source-identity run")
      }
      coverage <- media(b, c)
      r = c.downField("receipt")
      receipt <-
        for
          algorithm <- str(r, "algorithm")
          parameters <- str(r, "parameters")
          inputs <- arr(r, "inputs").flatMap(
            traverse(_)(h =>
              h.as[String].left.map(_.message).flatMap(x => domain(Checksum.from(x)))
            )
          )
          made <- domain(SourceDerivationReceipt.of(algorithm, parameters, inputs))
        yield made
      claim <- PresenceClaim
        .of(b, coverage, PresenceBasis.HumanReviewed(receipt))
        .left
        .map(_.toString)
    yield claim

  /** Raw `[start, endExclusive]` pairs, read leniently so that a refused record can still withhold;
    * `None` when any interval is unreadable or empty, since an empty span overlaps nothing.
    */
  private def rawSpans(j: Json): Option[Vector[(Long, Long)]] =
    j.hcursor.downField("intervals").as[Vector[Vector[Long]]].toOption.flatMap { v =>
      val pairs = v.collect { case Vector(a, z) if 0 <= a && a < z => (a, z) }
      Option.when(pairs.nonEmpty && pairs.size == v.size)(pairs)
    }

  /** Why a presence claim is withheld: a refused mention that may be a person on the picture
    * overlaps it, so the claim would certify fewer people than the producer listed (ADR 0027 §2). A
    * refused mention with unreadable intervals withholds every claim.
    */
  private def withholding(
      claim: Json,
      refused: Vector[(String, Option[Vector[(Long, Long)]])]
  ): Option[String] =
    val covered = rawSpans(claim).getOrElse(Vector.empty)
    refused.collectFirst {
      case (id, spans)
          if spans.forall(_.exists((a, z) => covered.exists((c, d) => a < d && c < z))) =>
        s"withheld: refused mention $id may be a person present"
    }

  /** One proposal as the producer handed it over, with the producer's own guard outcome. */
  private final case class Proposal(
      id: String,
      kind: String,
      left: String,
      right: String,
      name: Option[String],
      hint: Option[(ContributoryBasis, HintDirection)],
      producer: Option[String]
  )

  private def proposal(j: Json): Result[Proposal] =
    val c = j.hcursor
    for
      id <- str(c, "id")
      kind <- str(c, "kind").flatMap {
        case k @ ("named" | "deictic" | "hint") => Right(k)
        case other                              => Left(s"unsupported proposal kind $other")
      }
      left <- str(c, "left")
      right <- str(c, "right")
      name = c.downField("name").as[String].toOption
      hint <-
        if kind != "hint" then Right(None)
        else
          for
            basis <- str(c, "basis").flatMap(x =>
              ContributoryBasis.values.find(_.toString == x).toRight(s"unknown hint basis $x")
            )
            dir <- str(c, "direction").flatMap(x =>
              HintDirection.values.find(_.toString == x).toRight(s"unknown direction $x")
            )
          yield Some(basis -> dir)
      guard = c.downField("producer")
      admitted <- guard.downField("admitted").as[Boolean].left.map(_ => "missing producer.admitted")
      refusal = if admitted then None else Some(guard.downField("reason").as[String].getOrElse(""))
    yield Proposal(id, kind, left, right, name, hint, refusal)

  private def outcome(status: String, reason: Option[String] = None): Json =
    Json.obj(
      ("status" -> Json.fromString(status)) +: reason.map("reason" -> Json.fromString(_)).toSeq*
    )

  /** Builds the graph document from an input document; pure, so the courts run without I/O. */
  def graph(inputText: String): Result[Json] =
    for
      doc <- io.circe.parser.parse(inputText).left.map(_.message)
      c = doc.hcursor
      _ <- str(c, "schemaVersion").filterOrElse(
        _ == InputSchema,
        s"schemaVersion must be $InputSchema"
      )
      manifest <- arr(c, "inputs")
      roles <- traverse(manifest)(m => str(m.hcursor, "role"))
      _ <- roles
        .find(r => !InputRoles(r))
        .map(r => Left(s"input role $r is not admitted"))
        .getOrElse(Right(()))
      b <- bundle(c.downField("edition"))
      rawMentions <- arr(c, "mentions")
      rawPresence <- arr(c, "presence")
      rawProposals <- arr(c, "proposals").flatMap(traverse(_)(proposal))
      // Each mention and presence claim either builds or is refused with its reason; a refusal is
      // a result of the run, not a failure of it (rule 12).
      mentionIds = rawMentions.map(j => j.hcursor.downField("id").as[String].getOrElse(""))
      builtMentions = rawMentions.map(mention(b, _))
      // Only a mention that is certainly dialogue cannot be a person on the picture.
      refusedPeople = rawMentions
        .zip(builtMentions)
        .collect {
          case (j, Left(_)) if !j.hcursor.get[String]("origin").contains("dialogue") =>
            j.hcursor.get[String]("id").getOrElse("") -> rawSpans(j)
        }
        .sortBy(_._1)
      builtPresence = rawPresence.map(j =>
        presence(b, j).flatMap(p => withholding(j, refusedPeople).toLeft(p))
      )
      mentions = builtMentions.collect { case Right(m) => m }
      context <- ReferentContext
        .of(mentions, Vector.empty, builtPresence.collect { case Right(p) => p })
        .left
        .map(_.toString)
      submitted = rawProposals.map { p =>
        p.producer match
          case Some(reason) => p -> Left(outcome("refused-by-producer", Some(reason)))
          case None         =>
            val ids = for
              l <- domain(ReferentMentionId.from(p.left))
              r <- domain(ReferentMentionId.from(p.right))
            yield (l, r)
            p -> ids
              .flatMap { (l, r) =>
                p.kind match
                  case "named" =>
                    p.name
                      .toRight("a named proposal needs a name")
                      .flatMap(n => ProperName.of(n).left.map(_.toString))
                      .flatMap(n =>
                        IdentityLink
                          .of(context, l, r, IdentityBasis.NamedReference(n))
                          .left
                          .map(_.toString)
                      )
                      .map(Left(_))
                  case "deictic" =>
                    IdentityLink
                      .of(context, l, r, IdentityBasis.DeicticReference)
                      .left
                      .map(_.toString)
                      .map(Left(_))
                  case _ =>
                    val (basis, dir) = p.hint.get
                    if context.mention(l).isLeft || context.mention(r).isLeft then
                      Left(s"hint names a mention outside the context")
                    else Right(Right(IdentityHint(l, r, basis, dir)))
              }
              .left
              .map(reason => outcome("refused", Some(reason)))
      }
      links = submitted.collect { case (_, Right(Left(link))) => link }
      hints = submitted.collect { case (_, Right(Right(hint))) => hint }
      resolution <- ReferentIdentity
        .resolve(context, links, Vector.empty, hints)
        .left
        .map(_.toString)
      relations <- relationsOf(resolution, mentions)
    yield
      def relationJson(r: ReferentRelation): Json = r match
        case ReferentRelation.Open(cs) =>
          Json.obj(
            "relation" -> Json.fromString("Open"),
            "candidates" -> Json.fromValues(
              cs.map(m => Json.fromString(m.value)).sorted(using jsonOrder)
            )
          )
        case other => Json.obj("relation" -> Json.fromString(other.toString))
      Json.obj(
        "schemaVersion" -> Json.fromString(GraphSchema),
        "inputSha256" -> Json.fromString(Checksum.ofText(inputText).hex),
        "inputs" -> Json.fromValues(manifest),
        "bundle" -> Json.fromString(b.id.value),
        "mentions" -> Json.fromValues(mentionIds.zip(builtMentions).sortBy(_._1).map {
          case (id, Right(_)) =>
            Json.obj("id" -> Json.fromString(id), "outcome" -> outcome("admitted"))
          case (id, Left(e)) =>
            Json.obj("id" -> Json.fromString(id), "outcome" -> outcome("refused", Some(e)))
        }),
        "presence" -> Json.fromValues(builtPresence.zipWithIndex.map {
          case (Right(_), i) =>
            Json.obj("index" -> Json.fromInt(i), "outcome" -> outcome("admitted"))
          case (Left(e), i) =>
            Json.obj("index" -> Json.fromInt(i), "outcome" -> outcome("refused", Some(e)))
        }),
        "classes" -> Json.fromValues(
          resolution.classes
            .map(_.toVector.map(_.value).sorted)
            .sortBy(_.head)
            .map(c => Json.fromValues(c.map(Json.fromString)))
        ),
        "relations" -> Json.fromValues(relations.map { (d, v, r, hs) =>
          Json
            .obj(
              "dialogue" -> Json.fromString(d.value),
              "visual" -> Json.fromString(v.value),
              "hints" -> Json.fromValues(
                hs.map(h =>
                  Json.obj(
                    "basis" -> Json.fromString(h.basis.toString),
                    "direction" -> Json.fromString(h.direction.toString)
                  )
                )
              )
            )
            .deepMerge(relationJson(r))
        }),
        "proposals" -> Json.fromValues(submitted.sortBy(_._1.id).map { (p, result) =>
          val resolver = result match
            case Left(refusal)   => refusal
            case Right(Left(_))  => outcome("licensed")
            case Right(Right(_)) => outcome("recorded-hint")
          Json.obj(
            "id" -> Json.fromString(p.id),
            "kind" -> Json.fromString(p.kind),
            "left" -> Json.fromString(p.left),
            "right" -> Json.fromString(p.right),
            "outcome" -> resolver
          )
        })
      )

  private val jsonOrder: Ordering[Json] = Ordering.by(_.noSpaces)

  /** Each admitted dialogue mention against each class holding a visual mention, represented by
    * that class's least visual mention id.
    */
  private def relationsOf(
      resolution: ReferentResolution,
      mentions: Vector[ReferentMention]
  ): Result[
    Vector[(ReferentMentionId, ReferentMentionId, ReferentRelation, Vector[IdentityHint])]
  ] =
    val visual = mentions.filter(_.origin == MentionOrigin.Visual).map(_.id).toSet
    val representatives = resolution.classes
      .flatMap(c => c.toVector.filter(visual).sortBy(_.value).headOption)
      .sortBy(_.value)
    val dialogue = mentions
      .collect { case m if m.origin.isInstanceOf[MentionOrigin.Dialogue] => m.id }
      .sortBy(_.value)
    traverse(for d <- dialogue; v <- representatives yield (d, v)) { (d, v) =>
      resolution
        .relation(d, v)
        .left
        .map(_.toString)
        .map(r => (d, v, r, resolution.hintsBetween(d, v)))
    }

  private def publish(output: Path)(body: Path => Result[Json]): Result[Json] =
    if Files.exists(output, LinkOption.NOFOLLOW_LINKS) then Left("output already exists")
    else
      val temporary =
        Files.createTempDirectory(output.toAbsolutePath.getParent, ".source-identity-")
      try
        body(temporary).flatMap { result =>
          val _ = Files.move(temporary, output)
          Right(result)
        }
      finally
        if Files.exists(temporary) then
          val paths = Files.walk(temporary)
          try
            paths
              .iterator()
              .asScala
              .toVector
              .reverse
              .foreach(p => { val _ = Files.deleteIfExists(p) })
          finally paths.close()

  /** Tests and embedders receive a typed failure; only main owns process exit status. */
  def run(args: Vector[String]): Result[Json] =
    try
      args match
        case Vector("build", inputs, destination) =>
          val text = new String(Files.readAllBytes(Path.of(inputs)), UTF_8)
          publish(Path.of(destination)) { temporary =>
            graph(text).map { g =>
              val _ = Files.writeString(temporary.resolve("graph.json"), g.spaces2)
              Json.obj(
                "graph" -> Json.fromString("graph.json"),
                "graphSha256" -> Json.fromString(Checksum.ofText(g.spaces2).hex)
              )
            }
          }
        case _ => Left(Usage)
    catch case NonFatal(e) => Left(s"sourceIdentity I/O refused: ${e.getClass.getSimpleName}")

  def main(args: Array[String]): Unit = run(args.toVector) match
    case Right(result) => println(result.spaces2)
    case Left(error)   => System.err.println(error); sys.exit(2)
