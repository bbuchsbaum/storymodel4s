import { StrictMode, useRef, useState } from "react";
import { createRoot } from "react-dom/client";
import { inspectArtifact, type Inspection } from "./artifact";
import "./style.css";

function Workbench() {
  const [view, setView] = useState<Inspection>();
  const [error, setError] = useState("");
  const [selected, select] = useState(0);
  const epoch = useRef(0);
  async function open(file?: File) {
    const request = ++epoch.current;
    setView(undefined); setError(""); select(0);
    if (!file) return;
    try {
      const result = inspectArtifact(JSON.parse(await file.text()));
      if (epoch.current === request) setView(result);
    } catch (reason) {
      if (epoch.current === request) setError(reason instanceof Error ? reason.message : "Could not read artifact");
    }
  }
  const section=view?.sections[selected];
  return <main><h1>StoryModel workbench</h1>
    <label>Open a saved analysis or feature table <input type="file" accept=".json,application/json" onChange={e=>void open(e.target.files?.[0])}/></label>
    {error && <p role="alert">{error}</p>}
    {view && section && <section><h2>{view.title}</h2>
      <label>Table <select value={selected} onChange={e=>select(Number(e.target.value))}>
        {view.sections.map((s,i)=><option key={s.label} value={i}>{s.label}</option>)}
      </select></label>
      <div className="scroll"><table><caption>{section.label}</caption><thead><tr>
        {section.columns.map(c=><th key={c} scope="col">{c}</th>)}
      </tr></thead><tbody>{section.rows.map((row,i)=><tr key={i}>{row.map((cell,j)=><td key={j}>{cell}</td>)}</tr>)}
      </tbody></table></div>{!section.rows.length && <p>No entries in this table.</p>}
    </section>}
  </main>;
}
const root=document.getElementById("root");
if(!root) throw Error("Missing application root");
createRoot(root).render(<StrictMode><Workbench/></StrictMode>);
