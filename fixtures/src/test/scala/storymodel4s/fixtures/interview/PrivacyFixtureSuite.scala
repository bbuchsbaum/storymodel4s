package storymodel4s.fixtures.interview

import munit.FunSuite

import storymodel4s.core.TextSpan
import storymodel4s.embed.{KeyId, PrivacyPolicyId, SensitiveKeyProvider}
import storymodel4s.fixtures.wog.WarOfTheGhostsText
import storymodel4s.interview.{PseudonymEntry, Pseudonymizer}

class PrivacyFixtureSuite extends FunSuite:
  private val policy = PrivacyPolicyId.unsafe("fixture-privacy-policy")
  private val keyId = KeyId.unsafe("fixture-reidentification-key")
  private val keys = SensitiveKeyProvider.static(keyId, Array[Byte](2, 3, 5, 7, 11, 13, 17))

  test("Birthday Interview surfaces are absent remotely and exactly reversible only with the key") {
    val table = Vector(
      PseudonymEntry("Claire", "[SISTER_OF_SPEAKER]"),
      PseudonymEntry("Maison Bleue", "[RESTAURANT_1]"),
      PseudonymEntry("Queen Street", "[STREET_1]"),
      PseudonymEntry("Montreal", "[CITY_1]")
    )
    val (transcript, key) = Pseudonymizer
      .pseudonymize(BirthdayInterview.source, table, policy, keyId, keys)
      .toOption
      .get
    val sanitized = transcript.payload.text

    assert(table.forall(entry => !sanitized.contains(entry.surface)))
    assertEquals(
      Pseudonymizer.reverse(transcript, key),
      Right(BirthdayInterview.source.canonicalText)
    )

    val claire = BirthdayInterview.source.canonicalText.indexOf("Claire")
    val mapped = transcript.mapSpan(TextSpan.unsafe(claire, claire + "Claire".length)).toOption.get
    assertEquals(mapped.slice(sanitized), Right("[SISTER_OF_SPEAKER]"))
  }

  test("War of the Ghosts place names preserve the exact fixture surface axis") {
    val source = storymodel4s.core.StorySource.fromText(WarOfTheGhostsText.text).toOption.get
    val table = Vector(
      PseudonymEntry("Egulac", "[PLACE_1]"),
      PseudonymEntry("Kalama", "[PLACE_2]")
    )
    val (transcript, key) = Pseudonymizer
      .pseudonymize(source, table, policy, keyId, keys)
      .toOption
      .get

    assert(!transcript.payload.text.contains("Egulac"))
    assert(!transcript.payload.text.contains("Kalama"))
    assertEquals(Pseudonymizer.reverse(transcript, key), Right(source.canonicalText))

    val egulac = source.canonicalText.indexOf("Egulac")
    val mapped = transcript.mapSpan(TextSpan.unsafe(egulac, egulac + "Egulac".length)).toOption.get
    assertEquals(mapped.slice(transcript.payload.text), Right("[PLACE_1]"))
  }
