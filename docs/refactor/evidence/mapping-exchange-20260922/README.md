# Mapping exchange component — focused evidence

Source commit `f408a435` compiled and passed seven codec JVM tests and three
pipeline publication tests. The focused run was made before committing those
unchanged sources; [focused.json](focused.json) records that boundary explicitly.
It is not the clean, exact-revision `checkAll` acceptance gate.

The independent Python reader reproduces authored raw scores, normalized mass,
failed/unassigned outcome accounting, half-open support, point atoms and exact large
ticks. Seven rehashed malformed-package controls and an extra-directory control
refuse. [focused-logs.tar.gz](focused-logs.tar.gz) retains the command/exit receipts,
initial failed test attempt, passing producer output and independent-reader receipt.
Archive SHA-256: `ae4c3276a9d00e181c400be5d33e1049de434a70c5691df6c79d8e92d900bea6`.

Cold review caught and repaired Python binary64 spelling, incomplete relational
joins and ignored directories. Publication tests include partial staging-manifest
writes. The first JVM attempt exposed an incorrect test witness: identical source
and inventory remain a valid context across different measure policies. The test
now supplies an actually foreign source.

Pending: the committed three-mutation runner, JS/Native and full clean merge gate,
and the facade's paired reference/reconstruction/lambda envelope. This branch must
not be presented as the completed exchange ticket or a 1.0 release.
