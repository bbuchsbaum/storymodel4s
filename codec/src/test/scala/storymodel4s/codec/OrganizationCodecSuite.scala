package storymodel4s.codec

import munit.FunSuite
import storymodel4s.align.{MeasurementCompatibility, OrganizationReadout}
import storymodel4s.recall.RecallUnitId

/** Portable readback refuses malformed wire data while retaining genuine unavailable values. */
class OrganizationCodecSuite extends FunSuite:
  private def empty = OrganizationReadout
    .compute(
      Vector.empty,
      OrganizationReadout.Order.declared(Vector.empty).toOption.get,
      OrganizationReadout.Dependence.DeclaredIndependentRows
    )
    .toOption
    .get

  test("canonical roundtrip preserves unavailable denominator source and bounds") {
    val original = empty
    assertEquals(
      OrganizationCodecs.decode(OrganizationCodecs.encode(original), original),
      Right(original)
    )
    val missing = OrganizationReadout
      .compute(
        Vector(OrganizationReadout.Row.unavailable(RecallUnitId.unsafe("missing"))),
        original.order,
        OrganizationReadout.Dependence.DeclaredIndependentRows
      )
      .toOption
      .get
    assertEquals(
      OrganizationCodecs.decode(OrganizationCodecs.encode(missing), missing),
      Right(missing)
    )
  }

  test("malformed JSON refuses before the portable key scanner") {
    Vector("{", "}", "[", "\"unterminated", "{\"a\":").foreach(text =>
      assert(OrganizationCodecs.decode(text, empty).isLeft)
    )
  }

  test("duplicate keys escaped aliases and foreign axis cannot acquire context") {
    val text = OrganizationCodecs.encode(empty)
    Vector("schemaVersion", "schema\\u0056ersion").foreach { key =>
      val duplicate = "{\"" + key + "\":\"organization-readout/v1\"," + text.drop(1)
      assert(OrganizationCodecs.decode(duplicate, empty).isLeft)
    }
    assert(
      OrganizationCodecs
        .decode(text.replace("Discourse", MeasurementCompatibility.Axis.StoryWorld.toString), empty)
        .isLeft
    )
  }
