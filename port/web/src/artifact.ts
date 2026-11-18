// Read only the fields displayed by the inspector. Measurements come from the producer.
type Row = Record<string, unknown>;
const record = (v: unknown): Row => {
  if (v === null || typeof v !== "object" || Array.isArray(v)) throw Error("Expected a record");
  return v as Row;
};
const array = (v: unknown): unknown[] => {
  if (!Array.isArray(v)) throw Error("Expected an array");
  return v;
};
const text = (v: unknown): string => {
  if (typeof v !== "string") throw Error("Expected text");
  return v;
};
const number = (v: unknown): number => {
  if (typeof v !== "number" || !Number.isFinite(v)) throw Error("Expected a finite number");
  return v;
};
const mean = (v: unknown): string => v === null ? "Unavailable" : String(number(v));
export type Section = { label: string; columns: string[]; rows: string[][] };
export type Inspection = { title: string; sections: Section[] };

export function inspectArtifact(value: unknown): Inspection {
  const data = record(value);
  if (data.schema_version === "storymodel.feature-windows/v1") {
    const wordMap = new Map(array(data.recall_words).map(raw => {
      const w = record(raw); return [text(w.occurrence), text(w.form)];
    }));
    const windows = array(data.windows).map(raw => {
      const w = record(raw), bin = record(w.interval), words = record(w.words);
      const lex = record(words.lexicon), length = record(words.token_length);
      return [text(bin.start), text(bin.end), array(words.occurrences).map(id => {
        const form = wordMap.get(text(id)); if (form === undefined) throw Error("Unknown occurrence");
        return form;
      }).join(" · "), mean(length.mean), mean(lex.mean), String(number(lex.measured)),
        String(number(lex.missing)), String(array(w.detail_ids).length)];
    });
    const details = array(data.details).flatMap(raw => {
      const d = record(raw), r = record(d.recall);
      const base = [text(r.detail), d.onset_tick === null ? "Missing" : text(d.onset_tick), text(r.reference_status)];
      const rows = array(d.correspondence).map(rawRef => {
        const ref = record(rawRef);
        return [...base, text(ref.meaning), text(ref.target ?? ref.external ?? "Unavailable"),
          ref.alternative === true ? "Yes" : "No"];
      });
      return rows.length ? rows : [[...base, "—", "—", "—"]];
    });
    return { title: text(record(data.lexicon).name), sections: [
      {label:"Recording windows", columns:["Start tick","End tick (exclusive)","Words","Mean length","Mean score","Scored","Missing score","Detail onsets"], rows:windows},
      {label:"Recall details", columns:["Detail","Unit onset tick","Reference status","Meaning","Target","Alternative"], rows:details},
      {label:"Unplaced words", columns:["Word","Occurrence","Timing"], rows:
        (["missing_onset","outside_windows"] as const).flatMap(key => array(data[key]).map(raw => {
          const id=text(raw), form=wordMap.get(id); if(form===undefined) throw Error("Unknown occurrence");
          return [form,id,key==="missing_onset"?"Missing onset":"Outside windows"];
        }))},
    ]};
  }
  if (data.schema_version === "storymodel.analysis-replay/v1" || data.schema_version === "storymodel.analysis-replay/v2") {
    const views=record(data.views);
    return {title:"Saved recall analysis", sections:[
      {label:"Recall details", columns:["Detail","Unit","Category","Reference status"], rows:array(views.recall).map(v=>{
        const r=record(v); return [r.detail,r.unit,r.category,r.reference_status].map(text);
      })},
      {label:"Source coverage",columns:["Source unit","Coverage","Contributing details"],rows:array(views.source).map(v=>{
        const r=record(v); return [text(r.source_unit),text(r.coverage),array(r.contributing_details).map(text).join(", ")];
      })},
    ]};
  }
  throw Error("Choose a feature-window or saved analysis replay JSON file.");
}
