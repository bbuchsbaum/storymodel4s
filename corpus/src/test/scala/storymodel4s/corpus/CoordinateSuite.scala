package storymodel4s.corpus

import munit.FunSuite

class CoordinateSuite extends FunSuite:
  private val art = ArtifactId.unsafe("FriendsRecallScoring.xlsx")

  private def at(container: String, row: Int, column: String): SourceCoordinate =
    SourceCoordinate.at(art, container, row, column).fold(e => fail(e.message), identity)

  test("a coordinate is 1-based and refuses a row before the first line") {
    assert(SourceCoordinate.at(art, "s1", 0, "WhichEvent").isLeft)
    assert(SourceCoordinate.at(art, "s1", -1, "WhichEvent").isLeft)
    assert(SourceCoordinate.at(art, "s1", 1, "WhichEvent").isRight)
  }

  test("a coordinate refuses an empty column, because an index does not survive an insert") {
    assert(SourceCoordinate.at(art, "s1", 1, "").isLeft)
  }

  test("equality is structural over all four components") {
    assertEquals(at("s1", 7, "WhichEvent"), at("s1", 7, "WhichEvent"))
    assertNotEquals(at("s1", 7, "WhichEvent"), at("s2", 7, "WhichEvent"))
    assertNotEquals(at("s1", 7, "WhichEvent"), at("s1", 8, "WhichEvent"))
    assertNotEquals(at("s1", 7, "WhichEvent"), at("s1", 7, "WhichStoryline"))
    assertEquals(at("s1", 7, "WhichEvent").hashCode(), at("s1", 7, "WhichEvent").hashCode())
  }

  test("rendering carries the address and never a value") {
    assertEquals(at("s1", 7, "WhichEvent").toString, "FriendsRecallScoring.xlsx!s1:7:WhichEvent")
    assertEquals(at("", 3, "Words").toString, "FriendsRecallScoring.xlsx:3:Words")
  }

  test("ordering is by artifact, container, row, column") {
    val sorted = Vector(at("s2", 1, "a"), at("s1", 9, "a"), at("s1", 2, "b"), at("s1", 2, "a"))
      .sorted
      .map(_.toString)
    assertEquals(
      sorted,
      Vector(
        "FriendsRecallScoring.xlsx!s1:2:a",
        "FriendsRecallScoring.xlsx!s1:2:b",
        "FriendsRecallScoring.xlsx!s1:9:a",
        "FriendsRecallScoring.xlsx!s2:1:a"
      )
    )
  }

  test("a Raw carries the joined value, literal and coordinate") {
    val c = at("s1", 7, "WhichEvent")
    val r = Raw.of(37, c, "37")
    assertEquals(r.value, 37)
    assertEquals(r.literal, "37")
    assertEquals(r.at, c)
    assertEquals(r, Raw.of(37, c, "37"))
    // the same decoded value from a different literal is NOT the same claim
    assertNotEquals(r, Raw.of(37, c, "37.0"))
  }

  test("a Raw renders its address and neither the literal nor the value") {
    val r = Raw.of("Ross parenting", at("FriendsNarrComb", 5, "EventModel1"), "Ross parenting")
    assertEquals(r.toString, "Raw@FriendsRecallScoring.xlsx!FriendsNarrComb:5:EventModel1")
  }
