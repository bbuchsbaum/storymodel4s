"""Regression controls for the hosted sbt log format."""
import unittest

from qualify import plain_log


class LogFormatTest(unittest.TestCase):
    def test_hosted_junit_totals_survive_color(self):
        # Shape observed in Linux run 38751013555; color splits the parser's tokens.
        colored = ("\x1b[0m[\x1b[0minfo\x1b[0m] \x1b[34mTest run \x1b[0m"
                   "org.scalanative.testsuite.javalib.lang.\x1b[33mStringTest\x1b[0m"
                   "\x1b[34m finished: \x1b[0m\x1b[34m0 failed\x1b[0m"
                   "\x1b[34m, \x1b[0m\x1b[33m1 ignored\x1b[0m"
                   "\x1b[34m, 55 total, 0.021s\x1b[0m")
        self.assertEqual(plain_log(colored),
                         "[info] Test run org.scalanative.testsuite.javalib.lang.StringTest"
                         " finished: 0 failed, 1 ignored, 55 total, 0.021s")

    def test_plain_evidence_is_unchanged(self):
        evidence = "PASS literal-nul\nScalaNative: Unhandled signal 11, si_addr=0x55\n"
        self.assertEqual(plain_log(evidence), evidence)


if __name__ == "__main__":
    unittest.main()
