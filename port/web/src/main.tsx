import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

function Workbench() {
  return (
    <main>
      <h1>StoryModel workbench</h1>
      <p>The computational pilot runs through the Python API and command line.</p>
      <p>Artifact inspection will follow the checked account and analysis contract.</p>
    </main>
  );
}

const root = document.getElementById("root");
if (root === null) throw new Error("Missing application root");
createRoot(root).render(<StrictMode><Workbench /></StrictMode>);
