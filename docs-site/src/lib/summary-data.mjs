function number(raw, label) {
  const value = Number(raw);
  if (!Number.isFinite(value) || value < 0 || value > 1) {
    throw new Error(`${label} is outside [0, 1]: ${raw}`);
  }
  return value;
}

function one(raw, pattern, label) {
  const match = raw.match(pattern);
  if (!match) throw new Error(`missing or malformed ${label}`);
  return match;
}

function ratio(raw, key, label) {
  const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const match = one(
    raw,
    new RegExp(`^${escaped}: (n/a|\\d+(?:\\.\\d+)?) \\(support (\\d+(?:\\.\\d+)?) = (\\d+(?:\\.\\d+)?)/(\\d+(?:\\.\\d+)?)\\)$`, 'm'),
    key
  );
  const value = match[1] === 'n/a' ? null : number(match[1], `${key} value`);
  const support = number(match[2], `${key} support`);
  const conditioning = Number(match[3]);
  const total = Number(match[4]);
  if (!Number.isFinite(conditioning) || !Number.isFinite(total) || conditioning < 0 || total < 0) {
    throw new Error(`${key} support masses are malformed`);
  }
  const derivedSupport = total === 0 ? 0 : conditioning / total;
  if (Math.abs(derivedSupport - support) > 0.0001) {
    throw new Error(`${key} support ${support} disagrees with ${conditioning}/${total}`);
  }
  if ((value === null) !== (conditioning === 0)) {
    throw new Error(`${key} value and conditioning mass disagree`);
  }
  return { key, label, value, support, detail: `${conditioning.toFixed(4)}/${total.toFixed(4)}` };
}

export function parseSummaryMetrics(raw) {
  const uniform = one(raw, /^uniform coverage: (\d+(?:\.\d+)?)$/m, 'uniform coverage');
  const weighted = one(
    raw,
    /^importance-weighted coverage: (n\/a|\d+(?:\.\d+)?) \(conditioning weight (\d+(?:\.\d+)?); coverage (\d+)\/(\d+)\)$/m,
    'importance-weighted coverage'
  );
  const weightedValue = weighted[1] === 'n/a' ? null : number(weighted[1], 'weighted coverage');
  const weightedObserved = Number(weighted[3]);
  const weightedEligible = Number(weighted[4]);
  const weightedSupport = weightedEligible === 0 ? 0 : weightedObserved / weightedEligible;
  if ((weightedValue === null) !== (weightedObserved === 0)) {
    throw new Error('importance-weighted coverage value and observed count disagree');
  }

  const external = one(
    raw,
    /^external mass: external\(attributed\)=(\d+(?:\.\d+)?) unranked=(\d+(?:\.\d+)?) ranked=(\d+(?:\.\d+)?)$/m,
    'external mass'
  );
  const attributed = number(external[1], 'attributed external mass');
  const unranked = number(external[2], 'unranked mass');
  const ranked = number(external[3], 'ranked mass');
  if (Math.abs(unranked + ranked - 1) > 0.0001) {
    throw new Error('ranked and unranked mass do not sum to 1');
  }

  return [
    {
      key: 'uniform-coverage',
      label: 'Uniform coverage',
      value: number(uniform[1], 'uniform coverage'),
      support: null,
      detail: '71 source situations',
    },
    {
      key: 'weighted-coverage',
      label: 'Importance-weighted coverage',
      value: weightedValue,
      support: weightedSupport,
      detail: `${weightedObserved}/${weightedEligible} weighted situations`,
    },
    ratio(raw, 'fidelity', 'Fidelity'),
    ratio(raw, 'discourse chronology', 'Discourse chronology'),
    ratio(raw, 'story-world chronology', 'Story-world chronology'),
    ratio(raw, 'causal preservation', 'Causal preservation'),
    {
      key: 'external-mass',
      label: 'Attributed external mass',
      value: attributed,
      support: ranked,
      detail: `ranked mass ${ranked.toFixed(4)}`,
    },
  ];
}

export function summaryMetricsIdentity(metrics) {
  return JSON.stringify(metrics);
}
