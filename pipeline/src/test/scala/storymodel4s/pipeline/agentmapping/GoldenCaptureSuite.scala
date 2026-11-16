package storymodel4s.pipeline.agentmapping

import munit.FunSuite
import storymodel4s.core.Checksum

/** A legacy bundle is byte-identical to one written before producer conditions were typed.
  *
  * The digests below were captured by running the same fixture on 5078f169f, the parent of the
  * producer-condition slice. A change to legacy encoding, policy identifiers or receipt inputs
  * changes them.
  */
class GoldenCaptureSuite extends FunSuite:
  import ConditionFixture.*

  private val BundleSha256 = "cbb2a9f44204edbe9a8620592ec6e2a636cc819262f9265b205e4ca1dc078cdc"
  private val RecordDigests = Map(
    "narrative" -> "d2319da54da1976e2a1f629a6c67f0176e2dd897577fd742cb99e53e30056ebd",
    "referent" -> "1c71bd70dfc7d69ae9f94d8e9e6b5a6989deaccd9ebd1cff8ebad26d0db63c81"
  )

  test("a pre-change v2 bundle's bytes and record digests reproduce exactly"):
    val c = legacy("annotation")
    assertEquals(Checksum.ofText(bundle(c).noSpaces).hex, BundleSha256)
    StudyBundle.Fields.foreach(f => assertEquals(records(c)(f).digest.hex, RecordDigests(f)))
