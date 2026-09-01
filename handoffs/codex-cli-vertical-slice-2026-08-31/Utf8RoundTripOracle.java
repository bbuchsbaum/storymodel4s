import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;

public final class Utf8RoundTripOracle {
  private static long checked = 0L;

  private static boolean roundTripAccepts(byte[] bytes) {
    return java.util.Arrays.equals(
        bytes, new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
  }

  private static boolean reportingDecoderAccepts(byte[] bytes) {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      decoder.decode(ByteBuffer.wrap(bytes));
      return true;
    } catch (CharacterCodingException ignored) {
      return false;
    }
  }

  private static void check(byte[] bytes) {
    boolean roundTrip = roundTripAccepts(bytes);
    boolean reporting = reportingDecoderAccepts(bytes);
    checked += 1L;
    if (roundTrip != reporting) {
      throw new AssertionError(
          "mismatch for "
              + HexFormat.of().formatHex(bytes)
              + ": roundTrip="
              + roundTrip
              + ", REPORT="
              + reporting);
    }
  }

  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value.replace(" ", ""));
  }

  public static void main(String[] args) {
    check(new byte[0]);

    for (int first = 0; first <= 0xff; first++) {
      check(new byte[] {(byte) first});
    }

    for (int pair = 0; pair <= 0xffff; pair++) {
      check(new byte[] {(byte) (pair >>> 8), (byte) pair});
    }

    String[] boundaries = {
      "00", "7f", "c2 80", "df bf", "e0 a0 80", "ed 9f bf", "ee 80 80",
      "ef bf bd", "f0 90 80 80", "f4 8f bf bf", "80", "bf", "c0 80", "c1 bf",
      "c2", "e0 80 80", "ed a0 80", "f0 80 80 80", "f4 90 80 80",
      "f5 80 80 80", "ff", "e2 82", "61 80"
    };
    for (String boundary : boundaries) {
      check(hex(boundary));
    }

    var random = new Random(0x5a17c0deL);
    for (int sample = 0; sample < 1_000_000; sample++) {
      byte[] bytes = new byte[random.nextInt(13)];
      random.nextBytes(bytes);
      check(bytes);
    }

    System.out.println(
        "PASS: round-trip UTF-8 admission matched CharsetDecoder REPORT for "
            + checked
            + " inputs (all lengths 0-2, boundary vectors, and 1,000,000 fixed-seed samples of length 0-12)");
  }
}
