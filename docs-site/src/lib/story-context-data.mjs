const RecordLine = /^(wog:sit:[^:]+): (event|state) \/ ([^\n]+)$/gm;

export function parseStoryContextOutput(raw) {
  const records = [...raw.matchAll(RecordLine)].map((match, index, matches) => {
    const start = match.index + match[0].length;
    const end = matches[index + 1]?.index ?? raw.length;
    const block = raw.slice(start, end);
    const context = block.match(/^  context: (.+) \((wog:ctx:[^)]+)\)$/m);
    const parent = block.match(/^  context parent: (none|wog:ctx:[^\n]+)$/m);
    const status = block.match(/^  polarity: ([^;]+); modality: ([^\n]+)$/m);
    const evidence = block.match(/^  evidence sentence (\d+): ([^\n]+)$/m);
    const description = block.match(/^  description: ([^\n]+)$/m);
    if (!context || !parent || !status || !evidence || !description) {
      throw new Error(`incomplete context record for ${match[1]}`);
    }
    return {
      id: match[1],
      kind: match[2],
      predicate: match[3],
      context: { label: context[1], id: context[2], parent: parent[1] === 'none' ? null : parent[1] },
      polarity: status[1],
      modality: status[2],
      sentence: Number(evidence[1]),
      evidence: evidence[2],
      description: description[1],
    };
  });

  if (records.length !== 2) throw new Error(`expected two context records, found ${records.length}`);
  if (records[0].sentence !== records[1].sentence || records[0].evidence !== records[1].evidence) {
    throw new Error('the two context records do not cite the same sentence');
  }
  const root = records.find((record) => record.context.parent === null);
  const nested = records.find((record) => record.context.parent !== null);
  if (!root || !nested || nested.context.parent !== root.context.id) {
    throw new Error('the context records do not form one root and one nested context');
  }

  return { sentence: root.sentence, evidence: root.evidence, root, nested };
}

export function storyContextIdentity(data) {
  return JSON.stringify(data);
}
