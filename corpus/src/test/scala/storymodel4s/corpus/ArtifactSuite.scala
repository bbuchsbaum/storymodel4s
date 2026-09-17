package storymodel4s.corpus

import storymodel4s.core.Checksum

import munit.FunSuite

class ArtifactSuite extends FunSuite:

  test("a safe relative path is accepted") {
    assert(RelativeArtifactPath.from("friends/FriendsRecallScoring.xlsx").isRight)
    assert(RelativeArtifactPath.from("a.tsv").isRight)
    assert(RelativeArtifactPath.from("a/b/c/d.csv").isRight)
  }

  test("anything that could leave the snapshot root is refused") {
    val bad = Vector(
      "", // empty
      "/etc/passwd", // absolute
      "C:/data/x.xlsx", // windows drive root
      "a\\b.xlsx", // backslash
      "../outside.xlsx", // parent escape
      "a/../../outside.xlsx", // parent escape mid-path
      "a/./b.xlsx", // single-dot segment
      "a//b.xlsx", // empty segment
      "a\u0000b", // NUL
      "a\nb", // newline
      "a\tb" // tab
    )
    bad.foreach { raw =>
      assertEquals(
        RelativeArtifactPath.from(raw),
        Left(IntakeRefusal.UnsafeRelativePath),
        s"expected refusal for ${raw.map(c => if c.isControl then '?' else c)}"
      )
    }
  }

  test("a path never discloses its filename, because filenames identify participants") {
    val p = RelativeArtifactPath.from("friends/s12_recall.xlsx").toOption.get
    assertEquals(p.toString, "<external-artifact>")
    assert(!p.toString.contains("s12"))
  }

  test("a child path is checked, so a traversing filename cannot be appended") {
    val dir = RelativeArtifactPath.from("friends").toOption.get
    assert(RelativeArtifactPath.child(dir, "ratings.zip").isRight)
    assert(RelativeArtifactPath.child(dir, "../escape").isLeft)
    assert(RelativeArtifactPath.child(dir, "/abs").isLeft)
  }

  test("a receipt carries a byte COUNT and renders no path") {
    val id = ArtifactId.unsafe("FriendsRecallScoring.xlsx")
    val p = RelativeArtifactPath.from("friends/FriendsRecallScoring.xlsx").toOption.get
    val sum = Checksum.ofText("payload")
    val r = new ArtifactReceipt(id, p, 2180116L, sum)
    assertEquals(r.bytes, 2180116L)
    assert(r.toString.contains("FriendsRecallScoring.xlsx"))
    assert(!r.toString.contains("friends/"))
    assertEquals(r, new ArtifactReceipt(id, p, 2180116L, sum))
  }

  test("a request renders its artifact identity and not its path") {
    val id = ArtifactId.unsafe("eventseg.zip")
    val p = RelativeArtifactPath.from("friends/eventseg.zip").toOption.get
    assertEquals(ArtifactRequest.of(id, p).toString, "ArtifactRequest(eventseg.zip)")
  }

  test("every refusal renders without content, identity or an absolute path") {
    val id = ArtifactId.unsafe("x.xlsx")
    val all = Vector(
      IntakeRefusal.UnsafeRelativePath,
      IntakeRefusal.RootUnavailable(RootIssue.NotDirectory),
      IntakeRefusal.MissingArtifact(id),
      IntakeRefusal.NotRegularFile(id),
      IntakeRefusal.PathEscapesRoot(id),
      IntakeRefusal.ReadFailed(id, ReadOperation.Read)
    )
    all.foreach(r => assert(r.message.nonEmpty && !r.message.contains("/")))
  }
