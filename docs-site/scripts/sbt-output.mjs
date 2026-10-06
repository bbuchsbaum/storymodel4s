import { stripVTControlCharacters } from 'node:util';

const escapeRegex = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// Decorated logger lines identify the envelope. The example body remains byte-for-byte raw.
export function extractExampleOutput(transcript, main) {
  const marker = new RegExp(`^\\[info\\] running (?:\\(fork\\) )?${escapeRegex(main)}(?: .*)?$`);
  let offset = 0;
  let start = null;
  for (const raw of transcript.split('\n')) {
    const logger = stripVTControlCharacters(raw);
    if (start === null && marker.test(logger)) {
      start = offset + raw.length + 1;
    } else if (start !== null && /^\[success\] Total time:/.test(logger)) {
      return transcript.slice(start, offset);
    } else if (start !== null && /^\[info\] running /.test(logger)) {
      throw new Error(`could not find sbt success marker after ${main}`);
    }
    offset += raw.length + 1;
  }
  if (start === null) throw new Error(`could not find sbt run marker for ${main}`);
  throw new Error(`could not find sbt success marker after ${main}`);
}
