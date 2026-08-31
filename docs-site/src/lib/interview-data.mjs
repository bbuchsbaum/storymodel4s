const CategoryLine =
  /^(InternalEvent|InternalPlace|InternalTime|InternalPerceptual|InternalThoughtEmotion|ExternalEvent|ExternalSemantic|Repetition|Other)\s+point=(\d+(?:\.\d+)?) interval=\[(\d+(?:\.\d+)?), (\d+(?:\.\d+)?)\] hard=(\d+)$/gm;

function parseChannels(raw, family) {
  if (raw.trim() === '') return [];
  return raw.split(', ').map((entry) => {
    const match = entry.match(/^([^=]+)=Some\((\d+(?:\.\d+)?)\)$/);
    if (!match) throw new Error(`invalid ${family} channel: ${entry}`);
    return { name: match[1], value: Number(match[2]) };
  });
}

export function parseInterviewOutput(raw) {
  const head = raw.match(/^recall_units=(\d+) details=(\d+) assessments=(\d+)$/m);
  if (!head) throw new Error('missing interview counts');
  const categories = [...raw.matchAll(CategoryLine)].map((match) => ({
    category: match[1],
    point: Number(match[2]),
    low: Number(match[3]),
    high: Number(match[4]),
    hard: Number(match[5]),
  }));
  if (categories.length !== 9) {
    throw new Error(`expected 9 score categories, found ${categories.length}`);
  }
  categories.forEach((row) => {
    if (![row.point, row.low, row.high].every(Number.isFinite)) {
      throw new Error(`${row.category} contains a non-finite count`);
    }
    if (row.low < 0 || row.low > row.point || row.point > row.high) {
      throw new Error(`${row.category} point lies outside its interval`);
    }
    if (Math.round(row.point) !== row.hard) {
      throw new Error(`${row.category} hard count does not round its point estimate`);
    }
  });

  const coverage = raw.match(/^observed_coverage=(\d+)\/(\d+) \((\d+(?:\.\d+)?)\)$/m);
  if (!coverage) throw new Error('missing observed coverage');
  const observed = Number(coverage[1]);
  const eligible = Number(coverage[2]);
  const fraction = Number(coverage[3]);
  if (eligible === 0 || Math.abs(observed / eligible - fraction) > 0.0001) {
    throw new Error('observed coverage count and fraction disagree');
  }

  const resolution = raw.match(
    /^placement_resolution=phase: resolved=(\d+(?:\.\d+)?) unresolved=(\d+(?:\.\d+)?) excluded=(\d+(?:\.\d+)?) \(threshold (\d+(?:\.\d+)?)\)$/m
  );
  if (!resolution) throw new Error('missing placement resolution');
  const resolved = Number(resolution[1]);
  const unresolved = Number(resolution[2]);
  const excluded = Number(resolution[3]);
  const threshold = Number(resolution[4]);
  if (Math.abs(resolved + unresolved + excluded - 1) > 0.0001) {
    throw new Error('placement masses do not sum to 1');
  }

  const profile = raw.match(
    /^target_mass=(\d+(?:\.\d+)?) event_purity=(\d+(?:\.\d+)?) probe_gain=(\d+(?:\.\d+)?)$/m
  );
  if (!profile) throw new Error('missing profile summary');
  const density = raw.match(
    /^episodic_density_per_word=(\d+(?:\.\d+)?) episodic_density_per_second=(\d+(?:\.\d+)?)$/m
  );
  if (!density) throw new Error('missing episodic density');
  const perceptual = raw.match(/^perceptual=(.*)$/m);
  const mental = raw.match(/^mental=(.*)$/m);
  if (!perceptual || !mental) throw new Error('missing profile channels');

  return {
    counts: {
      recallUnits: Number(head[1]),
      details: Number(head[2]),
      assessments: Number(head[3]),
    },
    categories,
    coverage: { observed, eligible, fraction },
    resolution: { resolved, unresolved, excluded, threshold },
    profile: {
      targetMass: Number(profile[1]),
      eventPurity: Number(profile[2]),
      probeGain: Number(profile[3]),
      densityPerWord: Number(density[1]),
      densityPerSecond: Number(density[2]),
      perceptual: parseChannels(perceptual[1], 'perceptual'),
      mental: parseChannels(mental[1], 'mental'),
    },
  };
}

function channelLabel(name) {
  return name.replace(/([a-z])([A-Z])/g, '$1 $2').toLowerCase();
}

function formatChannels(channels) {
  if (channels.length === 0) return 'no supported channel';
  return channels.map((channel) => `${channelLabel(channel.name)} ${channel.value.toFixed(1)}`).join(' · ');
}

export function interviewFigureLabels(data) {
  const resolution =
    `resolved ${data.resolution.resolved.toFixed(4)} · ` +
    `unresolved ${data.resolution.unresolved.toFixed(4)} · ` +
    `excluded ${data.resolution.excluded.toFixed(4)}`;
  const coverage =
    `observed detail mass: ${data.coverage.observed}/${data.coverage.eligible} · ` +
    `resolution threshold: ${data.resolution.threshold.toFixed(2)}`;
  const profile = `event purity ${data.profile.eventPurity.toFixed(4)} · probe gain ${data.profile.probeGain.toFixed(4)}`;
  const density =
    `episodic density ${data.profile.densityPerWord.toFixed(4)} per word · ` +
    `${data.profile.densityPerSecond.toFixed(4)} per second`;
  const perceptual = `perceptual: ${formatChannels(data.profile.perceptual)}`;
  const mental = `mental: ${formatChannels(data.profile.mental)}`;
  return {
    title: `Birthday Interview: ${data.counts.details} assessed details`,
    description:
      `${data.categories.length} category estimates with modal-only lower bounds and any-mass upper bounds. ` +
      `${resolution}. ${coverage}. ${profile}. ${density}. ${perceptual}. ${mental}.`,
    resolution,
    coverage,
    profile,
    density,
    perceptual,
    mental,
  };
}

export function interviewIdentity(data) {
  return JSON.stringify(data);
}
