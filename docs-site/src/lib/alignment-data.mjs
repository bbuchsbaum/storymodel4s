const UnitLine =
  /^unit (\d+) -> ([^;\n]+); source=(\d+(?:\.\d+)?) external=(\d+(?:\.\d+)?)$/gm;

export function parseAlignmentRows(raw) {
  const rows = [...raw.matchAll(UnitLine)].map((match) => ({
    unit: Number(match[1]),
    anchor: match[2],
    source: Number(match[3]),
    external: Number(match[4]),
  }));

  if (rows.length !== 2) {
    throw new Error(`expected exactly 2 alignment rows, found ${rows.length}`);
  }

  rows.forEach((row, index) => {
    if (row.unit !== index) {
      throw new Error(`expected unit ${index}, found unit ${row.unit}`);
    }
    for (const [name, value] of [
      ['source', row.source],
      ['external', row.external],
    ]) {
      if (!Number.isFinite(value) || value < 0 || value > 1) {
        throw new Error(`unit ${row.unit} ${name} mass is outside [0, 1]: ${value}`);
      }
    }
    const total = row.source + row.external;
    if (Math.abs(total - 1) > 0.0001) {
      throw new Error(`unit ${row.unit} masses sum to ${total}, expected 1`);
    }
  });

  return rows;
}

export function alignmentRowsIdentity(rows) {
  return JSON.stringify(
    rows.map(({ unit, anchor, source, external }) => ({ unit, anchor, source, external }))
  );
}
