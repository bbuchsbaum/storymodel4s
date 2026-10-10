package storymodel4s.pipeline

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.FunSuite
import storymodel4s.core.*
import storymodel4s.recall.RecallTiming
import storymodel4s.codec.TextSourceArtifactCodec

/** Admission: original synthetic non-participant controls; no licensed story or recall data. The
  * Anna/Bob control is reused from RecallTimingBuildSuite. Unicode is a parser-only token.
  */
class StudyPreparationSuite extends FunSuite:
  import StudyPreparation.*
  private val text = "Anna arrived. Bob left."
  private def right[E, A](value: Either[E, A]): A = value.fold(e => fail(e.toString), identity)
  private def inTemp(test: Path => Unit): Unit =
    val root = Files.createTempDirectory("study-preparation-")
    try test(root)
    finally
      val stream = Files.walk(root)
      try
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { p =>
          Files.deleteIfExists(p): Unit
        }
      finally stream.close()
  private def write(root: Path, name: String, value: String): Unit =
    Files.writeString(root.resolve(name), value): Unit
  private def config(rows: Vector[RecallInput]): Config =
    Config(SourceInput.Text("source.txt", SurfaceUnitKind.Sentence), rows, RecallProfile)
  private def inputs = Vector(
    RecallInput("p1", "s1", "recall.txt"),
    RecallInput("p2", "s1", "recall.txt"),
    RecallInput("p1", "s2", "recall.txt")
  )
  private def files(root: Path): Unit =
    write(root, "source.txt", text)
    write(root, "recall.txt", text)

  test("two subjects and a repeated session retain independent identities and decode") {
    inTemp { root =>
      files(root)
      val study = right(prepare(root, config(inputs)))
      val values = study.outcomes.map(o => right(o.result))
      assertEquals(values.map(_.observation).distinct.size, 3)
      assertEquals(values.flatMap(_.inventory.words.map(_.id)).distinct.size, 12)
      values.foreach { r =>
        val decoded = right(decode(encode(r), edition(study.source)))
        assertEquals(decoded, r)
        assertEquals(decoded.graph.transcript.rawText, text)
        assertEquals(decoded.inventory.words.size, 4)
        assertEquals(decoded.timing, None)
      }
    }
  }

  test("failed subjects remain accounted and do not erase source-only preparation") {
    inTemp { root =>
      files(root)
      val study = right(prepare(root, config(inputs :+ RecallInput("p3", "s1", "absent.txt"))))
      assertEquals(study.outcomes.size, 4)
      assertEquals(study.outcomes.count(_.result.isRight), 3)
      assertEquals(study.outcomes.last.result, Left(Refusal.InputRead))
      assertEquals(right(prepare(root, config(Vector.empty))).outcomes, Vector.empty)
    }
  }

  test("changed source edition refuses an old prepared recall") {
    inTemp { root =>
      files(root)
      val old = right(prepare(root, config(inputs.take(1))))
      val encoded = encode(right(old.outcomes.head.result))
      write(root, "source.txt", "Bob left. Anna arrived.")
      val changed = right(prepare(root, config(inputs.take(1))))
      assertNotEquals(edition(old.source), edition(changed.source))
      assertEquals(decode(encoded, edition(changed.source)), Left(Refusal.ArtifactMismatch))
    }
  }

  test("timing preserves exact onset missingness and intervals without changing words") {
    inTemp { root =>
      files(root)
      val raw = right(prepare(root, config(inputs.take(1))))
      val plain = right(raw.outcomes.head.result)
      write(
        root,
        "times.csv",
        "participant,session,word,onset,end\n" +
          "p1,s1,Anna,0.10000000000000001,\n" +
          "p1,s1,arrived,,\n" + "p1,s1,Bob,2,3\n" + "p1,s1,left,3,\n"
      )
      val study = right(prepare(root, config(Vector(inputs.head.copy(timing = Some("times.csv"))))))
      val timed = right(study.outcomes.head.result)
      assertEquals(timed.observation, plain.observation)
      assertEquals(timed.inventory.words.map(_.id), plain.inventory.words.map(_.id))
      val entries = timed.timing.get.entries
      assertEquals(
        entries.head.observation.asInstanceOf[RecallTiming.Observation.OnsetOnly].seconds,
        right(RecallTiming.decimalSeconds("0.10000000000000001"))
      )
      assert(entries(1).observation.isInstanceOf[RecallTiming.Observation.Missing])
      assert(entries(2).observation.isInstanceOf[RecallTiming.Observation.Interval])
      assertEquals(right(decode(encode(timed), edition(study.source))), timed)
      val foreign = right(prepare(root, config(Vector(inputs(2).copy(timing = Some("times.csv"))))))
      assertEquals(foreign.outcomes.head.result, Left(Refusal.ForeignTiming))
    }
  }

  test("malformed timing fails locally and quoted words use the existing CSV parser") {
    inTemp { root =>
      files(root)
      val csv = "participant,session,word,onset,end\np1,s1,\"Anna\",NaN,\n" +
        "p1,s1,arrived,,\np1,s1,Bob,2,3\np1,s1,left,3,\n"
      write(root, "times.csv", csv)
      val study = right(
        prepare(root, config(Vector(inputs.head.copy(timing = Some("times.csv")), inputs(1))))
      )
      assertEquals(study.outcomes.head.result, Left(Refusal.InvalidTiming))
      assert(study.outcomes(1).result.isRight)
      write(root, "times.csv", csv.replace("NaN", "0.1"))
      assert(
        right(
          prepare(root, config(Vector(inputs.head.copy(timing = Some("times.csv")))))
        ).outcomes.head.result.isRight
      )
      write(root, "times.csv", csv.replace("NaN", "0.1").replace("p1,s1,Bob", "p1,s1,Anna"))
      assertEquals(
        right(
          prepare(root, config(Vector(inputs.head.copy(timing = Some("times.csv")))))
        ).outcomes.head.result,
        Left(Refusal.ForeignTiming)
      )
    }
  }

  test("Unicode and raw line endings survive public decoding") {
    inTemp { root =>
      files(root)
      val unicode = "Anna arrived.\r\nBob left. \u00e9 \ud83c\udfb5"
      write(root, "recall.txt", unicode)
      val study = right(prepare(root, config(inputs.take(1))))
      val value = right(study.outcomes.head.result)
      assertEquals(
        right(decode(encode(value), edition(study.source))).graph.transcript.rawText,
        unicode
      )
      assertEquals(value.graph.transcript.rawChecksum, Checksum.ofText(unicode))
    }
  }

  test("supplied checked text preserves source identity and video remains missing capability") {
    inTemp { root =>
      files(root)
      val original = right(prepare(root, config(Vector.empty)))
      write(root, "source.json", original.source.artifact)
      val supplied = right(
        prepare(
          root,
          config(Vector.empty)
            .copy(source = SourceInput.CheckedText("source.json", SurfaceUnitKind.Sentence))
        )
      )
      assertEquals(edition(supplied.source), edition(original.source))
      assertEquals(
        prepare(root, config(Vector.empty).copy(source = SourceInput.Video("movie.mp4"))),
        Left(Refusal.MissingCapability("configured-video-source-perception"))
      )
    }
  }

  test("outside symlink and directory refuse while lawful nested input works") {
    inTemp { root =>
      files(root)
      val outside = Files.createTempFile("outside-study-", ".txt")
      try
        Files.createSymbolicLink(root.resolve("external.txt"), outside): Unit
        assertEquals(read(root, "external.txt"), Left(Refusal.UnsafePath))
        Files.createDirectory(root.resolve("nested")): Unit
        write(root, "nested/recall.txt", text)
        assert(read(root, "nested/recall.txt").isRight)
        assertEquals(read(root, "nested"), Left(Refusal.UnsafePath))
      finally Files.deleteIfExists(outside): Unit
    }
  }

  test("duplicate observations and invalid config refuse before output") {
    inTemp { root =>
      files(root)
      assertEquals(
        prepare(root, config(Vector(inputs.head, inputs.head))),
        Left(Refusal.DuplicateObservation)
      )
      assertEquals(
        prepare(root, config(inputs).copy(profile = "implicit")),
        Left(Refusal.InvalidConfig)
      )
    }
  }

  test("completion marker is last and interrupted output refuses resume") {
    inTemp { root =>
      files(root)
      val prepared = right(prepare(root, config(inputs)))
      val (payloads, manifest) = StudyCli.artifacts(prepared)
      val output = root.resolve("output")
      val result = StudyArtifacts.publish(
        output,
        payloads,
        manifest,
        false,
        (path, bytes) => {
          if path.getFileName.toString == ".manifest.pending" then
            throw new java.io.IOException("synthetic interruption")
          Files.write(path, bytes): Unit
        }
      )
      assertEquals(result, Left(StudyArtifacts.Refusal.OutputWrite))
      assert(!Files.exists(output.resolve("manifest.json")))
      assertEquals(
        StudyArtifacts.publish(output, payloads, manifest, true),
        Left(StudyArtifacts.Refusal.Incomplete)
      )
    }
  }

  test("resume verifies every artifact and does not overwrite history") {
    inTemp { root =>
      files(root)
      val prepared = right(prepare(root, config(inputs)))
      val (payloads, manifest) = StudyCli.artifacts(prepared)
      val output = root.resolve("output")
      val receipt = right(StudyArtifacts.publish(output, payloads, manifest, false))
      assertEquals(StudyArtifacts.publish(output, payloads, manifest, true), Right(receipt))
      assertEquals(
        StudyArtifacts.publish(output, payloads, manifest, false),
        Left(StudyArtifacts.Refusal.OutputExists)
      )
      write(output, payloads.last._1, "corrupt")
      assertEquals(
        StudyArtifacts.publish(output, payloads, manifest, true),
        Left(StudyArtifacts.Refusal.Corrupt)
      )
      assertEquals(Files.readString(output.resolve(payloads.last._1)), "corrupt")
    }
  }

  test("a truncated completion write never becomes a published manifest") {
    inTemp { root =>
      files(root)
      val prepared = right(prepare(root, config(inputs)))
      val (payloads, manifest) = StudyCli.artifacts(prepared)
      val output = root.resolve("output")
      val result = StudyArtifacts.publish(
        output,
        payloads,
        manifest,
        false,
        (path, bytes) => {
          val written =
            if path.getFileName.toString == ".manifest.pending" then bytes.take(10) else bytes
          Files.write(path, written): Unit
        }
      )
      assertEquals(result, Left(StudyArtifacts.Refusal.Corrupt))
      assert(!Files.exists(output.resolve("manifest.json")))
    }
  }

  test("public CLI template validates and publishes a roundtrippable source and recalls") {
    inTemp { root =>
      files(root)
      write(root, "study.json", StudyCli.Template)
      val out = Vector.newBuilder[String]
      val err = Vector.newBuilder[String]
      assertEquals(
        StudyCli.run(
          List("validate", root.resolve("study.json").toString),
          s => { out += s; () },
          s => { err += s; () }
        ),
        0
      )
      val output = root.resolve("prepared")
      assertEquals(
        StudyCli.run(
          List("prepare", root.resolve("study.json").toString, output.toString),
          s => { out += s; () },
          s => { err += s; () }
        ),
        0
      )
      val source =
        right(TextSourceArtifactCodec.decode(Files.readString(output.resolve("source.json"))))
      assertEquals(source.source.rawText, text)
      assertEquals(err.result(), Vector.empty)
      assertEquals(out.result().size, 2)
    }
  }

  test("invalid CLI output path returns refusal without throwing") {
    inTemp { root =>
      files(root)
      write(root, "study.json", StudyCli.Template)
      var errors = Vector.empty[String]
      assertEquals(
        StudyCli.run(
          List(
            "prepare",
            root.resolve("study.json").toString,
            root.resolve("never-created").toString + "\u0000"
          ),
          _ => (),
          e => errors :+= e
        ),
        2
      )
      assertEquals(errors.size, 1)
      assert(!Files.exists(root.resolve("never-created")))
    }
  }

  test("timed annotations preserve points disjoint intervals missing loci and part clocks") {
    inTemp { root =>
      files(root)
      val descriptor =
        """[{"part":"a","start":"0","end":"100","numerator":"1","denominator":"10"}]"""
      val csv = TimedAnnotationInput.Header.mkString(",") + "\n" +
        "1,Anna arrived.,a,10,20,1,Scene,,Anna\n" +
        "2,Bob left.,a,30,40,1,Scene,,Bob\n" +
        "3,Anna arrived.,a,25,,1,Scene,,Anna\n" +
        "4,Bob left.,,,,1,Scene,,Bob\n"
      write(root, "parts.json", descriptor)
      write(root, "annotations.csv", csv)
      val checked = right(
        TimedAnnotationInput.prepare(
          csv.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          descriptor.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        )
      )
      assertEquals(checked.segments.size, 4)
      val prepared = right(
        prepare(
          root,
          Config(
            SourceInput.TimedAnnotations("annotations.csv", "parts.json"),
            inputs.take(1),
            RecallProfile
          )
        )
      )
      val view = right(TimedAnnotationInput.decode(prepared.source.artifact))
      assertEquals(view.worldOrder, None)
      val group = view.nodes.find(_.level == 1).get.ref
      val part = view.parts("a")
      assertEquals(part.sourceKind, SourceKind.AnnotationTable)
      assertEquals(part.edition, None)
      val support = right(
        storymodel4s.align.TemporalSupport.read(
          view.representation,
          group,
          storymodel4s.align.TemporalSupport.Selection.Part(part.identity)
        )
      )
      assertEquals(
        support.selected.included.get.intervals.map(i => i.start -> i.endExclusive),
        Vector(10L -> 20L, 30L -> 40L)
      )
      assertEquals(support.selected.included.get.points.map(_.at), Vector(25L))
      assertEquals(support.unlocatedDescendants.size, 1)
      val mapping = right(
        MappingPipeline.batch(
          prepared,
          MappingPipeline.Config(
            MappingPipeline.LexicalProfile,
            1.0,
            20,
            200,
            Vector(MappingPipeline.Capability.Mapping)
          )
        )
      )
      assert(mapping.outcomes.head.run.nonEmpty, mapping.outcomes.head.failure.toString)
      assertEquals(
        mapping.outcomes.head.run.get.execution.reference.evidence.nominated.flatten.toSet,
        view.nodes.filter(_.level == 0).map(_.ref).toSet
      )
      write(root, "checked.json", prepared.source.artifact)
      val replay = right(
        prepare(
          root,
          Config(SourceInput.CheckedTimed("checked.json"), inputs.take(1), RecallProfile)
        )
      )
      assertEquals(replay.source.representation.digest, prepared.source.representation.digest)
      assertEquals(edition(replay.source), edition(prepared.source))
      write(root, "annotations.csv", csv.replace(",25,,", ",101,,"))
      assert(
        prepare(
          root,
          Config(
            SourceInput.TimedAnnotations("annotations.csv", "parts.json"),
            Vector.empty,
            RecallProfile
          )
        ).isLeft
      )
    }
  }
