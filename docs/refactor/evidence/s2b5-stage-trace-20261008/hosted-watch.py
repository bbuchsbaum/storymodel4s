import json,subprocess,time,sys,datetime
from pathlib import Path
SHA="655c0b3921539ad0c2cdf46181f0711480379f12"
REPO="bbuchsbaum/storymodel4s"
GH="/Users/bbuchsbaum/.local/bin/gh-bbuchsbaum"
OUT=Path("/private/tmp/storymodel4s-s2b5-hosted");OUT.mkdir(exist_ok=True)
def api(path):
 r=subprocess.run([GH,"api",path],capture_output=True,text=True)
 if r.returncode: raise RuntimeError(r.stderr)
 return json.loads(r.stdout)
last=None;deadline=time.monotonic()+7200
while time.monotonic()<deadline:
 data=api(f"repos/{REPO}/actions/runs?head_sha={SHA}&per_page=20")
 (OUT/"runs.json").write_text(json.dumps(data,indent=2)+"\n")
 selected={}
 for r in data["workflow_runs"]:
  if r["name"] in ["Continuous Integration","Documentation"] and r["name"] not in selected: selected[r["name"]]=r
 states=[]
 for name,r in selected.items():
  assert r["head_sha"]==SHA
  slug="source" if name=="Continuous Integration" else "docs"
  jobs=api(f"repos/{REPO}/actions/runs/{r['id']}/jobs?per_page=100")
  (OUT/f"{slug}-run.json").write_text(json.dumps(r,indent=2)+"\n")
  (OUT/f"{slug}-jobs.json").write_text(json.dumps(jobs,indent=2)+"\n")
  states.append({"name":name,"id":r["id"],"status":r["status"],"conclusion":r["conclusion"],"jobs":[{"id":j["id"],"name":j["name"],"status":j["status"],"conclusion":j["conclusion"]} for j in jobs["jobs"]]})
 state=json.dumps(states,sort_keys=True)
 if state!=last:
  print(state,flush=True)
  with (OUT/"events.jsonl").open("a") as f:f.write(json.dumps({"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"sha":SHA,"states":states})+"\n")
  last=state
 if len(selected)==2 and all(r["status"]=="completed" for r in selected.values()):
  sys.exit(0 if all(r["conclusion"]=="success" for r in selected.values()) else 1)
 time.sleep(60)
raise TimeoutError("Hosted qualification exceeded7200s; state retained")
