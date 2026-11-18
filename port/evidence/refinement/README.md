# Relation-preservation Scala witness

`relation-preservation.json` is the compact numeric oracle consumed by the Python refinement
regression. It has directed, positive source edges only. `e2-hear → e5-find` and
`e3-search → e5-find` are world-time reachable through intermediate nodes; the reverse is not.
The distorted `e3-search` mass remains anchored source mass, while external mass is excluded from
the diagnostic denominator and receives no reweighting bonus.

From a standalone clone of the recorded Scala revision, copy the probe into the align test source
tree and run the command stored in `reference.command`. The probe prints the machine-readable
`RELATION_PRESERVATION_PARITY=` result and asserts every stored value.
