package storymodel4s.pipeline

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import storymodel4s.codec.Canonical
import storymodel4s.core.{Checksum, SurfaceUnitKind}

/** Local public-corpus replay qualification emits only identities and counts, never recall prose.
  */
object SherlockDisclosureWitness:
  def main(args: Array[String]): Unit =
    require(args.length == 2, "usage: SherlockDisclosureWitness PATH_MAP LINEAGE")
    def checked[E, A](result: Either[E, A]): A =
      result.fold(e => throw new IllegalStateException(e.toString), identity)
    val paths = checked(Canonical.parse(Files.readString(Path.of(args(0)))))
      .as[Map[String, String]]
      .toOption
      .get
    require(paths.size == 17, "the complete admitted source population is required")
    val lineage = Files.readAllBytes(Path.of(args(1)))
    val root = Files.createTempDirectory("sherlock-disclosure-witness-")
    try
      Files.write(root.resolve("lineage.json"), lineage): Unit
      Files.writeString(root.resolve("source.txt"), "Anna arrived."): Unit
      val rows = paths.toVector.sortBy(_._1).map { (id, path) =>
        val csv = Files.readAllBytes(Path.of(path))
        val admitted = checked(RecallDisclosure.sherlock(id, csv, lineage))
        Files.write(root.resolve("recall.csv"), csv): Unit
        val config = StudyPreparation.Config(
          StudyPreparation.SourceInput.Text("source.txt", SurfaceUnitKind.Sentence),
          Vector(
            StudyPreparation.RecallInput(
              id,
              "released",
              "recall.csv",
              None,
              StudyPreparation.Admission.Sherlock(id, "lineage.json")
            )
          ),
          StudyPreparation.RecallProfile
        )
        val prepared = checked(StudyPreparation.prepare(root, config))
        val recall = checked(prepared.outcomes.head.result)
        require(recall.disclosure == admitted, "preparation lost the retained public basis")
        val publication = checked(StudyPreparation.publishRecall(recall))
        require(
          StudyPreparation.decode(publication, prepared.source.edition).isLeft,
          "receipt description minted authority"
        )
        require(
          checked(StudyPreparation.decode(publication, prepared.source.edition, admitted)) == recall
        )
        val corrupt = csv.clone()
        corrupt(0) = (corrupt(0) ^ 1).toByte
        require(
          RecallDisclosure.sherlock(id, corrupt, lineage) == Left(
            RecallDisclosure.Refusal.SherlockByteIdentityMismatch
          )
        )
        Json.obj(
          "source" -> Json.fromString(id),
          "csvDigest" -> Json.fromString(Checksum.ofBytes(csv).hex),
          "replayDigest" -> Json.fromString(Checksum.ofText(admitted.text).hex),
          "publicationDigest" -> Json.fromString(Checksum.ofText(publication).hex),
          "units" -> Json.fromInt(recall.inventory.units.size),
          "words" -> Json.fromInt(recall.inventory.words.size)
        )
      }
      println(
        Canonical.print(
          Json.obj(
            "qualification" -> Json.fromString("local-disclosure-replay"),
            "sources" -> Json.fromValues(rows)
          )
        )
      )
    finally
      val stream = Files.walk(root)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(p => { Files.deleteIfExists(p): Unit })
      finally stream.close()
