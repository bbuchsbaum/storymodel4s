class RecallClockIdCharacters {
  public static void main(String[] args) {
    for (int value = 0; value <= 65535; value++) {
      char c = (char) value;
      if (Character.isWhitespace(c) || Character.isISOControl(c)) {
        System.out.println(value);
      }
    }
  }
}
