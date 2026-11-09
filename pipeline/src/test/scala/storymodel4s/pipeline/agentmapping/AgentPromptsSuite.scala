package storymodel4s.pipeline.agentmapping

import java.nio.charset.StandardCharsets
import munit.FunSuite
import storymodel4s.core.Checksum

class AgentPromptsSuite extends FunSuite:
  import AgentPrompts.{RecallUnit, Refusal}

  private val table =
    "unit\tfunction\trecallText\textra\n" +
      "3\tn\tfirst thing\tz\n" +
      "4\tn\tsecond, with \"quotes\" inside\tz\n" +
      "9\tn\tthird\tz\n"

  test("units are read in file order from the named columns"):
    assertEquals(
      AgentPrompts.units(table),
      Right(
        Vector(
          RecallUnit(3, "first thing"),
          RecallUnit(4, "second, with \"quotes\" inside"),
          RecallUnit(9, "third")
        )
      )
    )

  test("a cell opening with a quote is refused rather than unquoted"):
    assertEquals(
      AgentPrompts.units("unit\trecallText\n0\t\"quoted\"\n"),
      Left(Refusal.QuotedCell(2))
    )

  test("a table without the recall column is refused"):
    assertEquals(
      AgentPrompts.units("unit\ttext\n0\tx\n"),
      Left(Refusal.MissingColumn("recallText"))
    )

  test("a row without its recall cell is refused, not rendered as empty"):
    assertEquals(AgentPrompts.units("unit\trecallText\n3\n"), Left(Refusal.MissingCell(2)))

  test("a non-integer unit index is refused"):
    assertEquals(AgentPrompts.units("unit\trecallText\nx\ty\n"), Left(Refusal.BadIndex(2)))

  test("each chunk prompt carries the whole recall and its own target range"):
    val us = AgentPrompts.units(table).toOption.get
    val ps = AgentPrompts.chunks("OUT", us, 2)
    assertEquals(ps.size, 2)
    assertEquals(
      ps(1),
      "FILM ANNOTATION\nOUT\n\nPARTICIPANT RECALL (all units)\n[3] first thing\n" +
        "[4] second, with \"quotes\" inside\n[9] third\n\n" +
        "TARGET UNITS: map every unit numbered 9 through 9 inclusive."
    )
    assert(ps(0).endsWith("numbered 3 through 4 inclusive."))

  test("the v2 system prompt is the recorded text, byte for byte"):
    val s = AgentPrompts.system(AgentPrompts.Version.V2)
    assertEquals(
      Checksum.ofBytes(s.getBytes(StandardCharsets.UTF_8)).hex,
      "aef7cb6f564b8b1d9c2891d9ed6f000e993cf7c59f8089ed7a27911d7210841a"
    )
