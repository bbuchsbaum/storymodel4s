"""Exact native coordinate parsing shared by intake and playback evidence."""

import re
from fractions import Fraction

_INTEGER = re.compile(r"-?[0-9]+")
_EXACT = re.compile(r"-?[0-9]+(?:\.[0-9]+|/[1-9][0-9]*)?")


def tick(value: str) -> int:
    if not isinstance(value, str) or _INTEGER.fullmatch(value) is None:
        raise ValueError("native ticks must be integer strings")
    return int(value)


def exact(value: str) -> Fraction:
    """Exact decimal or fraction input; JSON floating numbers are not clock declarations."""
    if not isinstance(value, str) or _EXACT.fullmatch(value) is None:
        raise ValueError("clock quantities must be exact decimal or fraction strings")
    return Fraction(value)


def fraction_text(value: Fraction) -> str:
    return f"{value.numerator}/{value.denominator}"
