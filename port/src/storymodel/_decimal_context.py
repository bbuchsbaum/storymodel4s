"""Private arithmetic contexts keep scientific outputs independent of caller Decimal settings."""

from decimal import ROUND_HALF_EVEN, Context, DivisionByZero, InvalidOperation, Overflow


def computation_context(precision: int) -> Context:
    """Return a fresh fully specified context; each algorithm retains its precision contract."""
    return Context(
        prec=precision,
        rounding=ROUND_HALF_EVEN,
        Emin=-999999,
        Emax=999999,
        capitals=1,
        clamp=0,
        flags=[],
        traps=[InvalidOperation, DivisionByZero, Overflow],
    )
