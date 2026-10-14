"""Independent finite-grid witness; run beside provider-series.json."""
import json, math
from pathlib import Path
rows=json.loads(Path(__file__).with_name("provider-series.json").read_text())
for row in rows:
    def mass(start,end):
        return sum(c["weight"] * sum(max(0,min(end,b)-max(start,a)) for a,b in c["intervals"]) / sum(b-a for a,b in c["intervals"]) for c in row["components"])
    for actual,expected in [(mass(0,100),row["fullMass"]),(mass(10,15),row["partialMass"]),(mass(0,0),row["emptyMass"])]:
        assert math.isclose(actual,expected,rel_tol=0,abs_tol=1e-14)
    target=mass(0,100)/2
    shortest=next(width for width in range(101) if any(mass(start,start+width)>=target-1e-14 for start in range(101-width)))
    assert shortest==int(row["width"])
print("PASS: both unequal rows, full/partial/empty regions and exhaustive shortest-window widths")
