import json, sys, glob, os, collections
arm = json.load(open(sys.argv[1])); bdir = sys.argv[2]
print("arm keys", list(arm.keys())[:12])
parts = arm["participants"]
print("arm participant keys", list(parts[0].keys()), "unit keys", list(parts[0]["units"][0].keys()))
tot = mism = 0; kinds = collections.Counter(); ref = collections.Counter(); differ = 0; rowsn = 0; unres = 0; conf = collections.Counter(); alts = collections.Counter()
for p in parts:
    name = p["participant"]
    b = json.load(open(os.path.join(bdir, name + ".bundle.json")))
    rows = {r["row"] for r in b["source"]["rows"]}
    scene_of = {s["target"]: s["scene"] for s in b["source"]["scenes"]}
    rec = {o["unit"]: o for o in b["interpretations"]["narrative"]["record"]["outcomes"]}
    units = {u["unit"]: u for u in b["units"]}
    assert len(units) == len(p["units"]) == len(rec), (name, len(units), len(p["units"]))
    for au in p["units"]:
        tot += 1
        u = units[au["id"]]; out = au["outcome"]
        f = u["narrative"]
        mine = ("label", f["value"]) if f["status"] == "value" else ("abstention", None) if f["status"] == "null" else ("other", f["status"])
        theirs = (out["kind"], out.get("scene"))
        o = rec[au["id"]]
        d = o["decision"]["value"]["decoded_target_id"] if o["processing_status"]["status"] == "complete" else None
        recscene = scene_of[d["value"]] if d and d["status"] == "present" else None
        if mine != theirs or (mine[0] == "label" and recscene != mine[1]): mism += 1
        kinds[theirs[0]] += 1; ref[u["referent"]["status"]] += 1; conf[u["confidence"]["status"]] += 1; alts[u["alternatives"]["status"]] += 1
        if f["status"] == "value" and u["referent"]["status"] == "value" and f["value"] != u["referent"]["value"]: differ += 1
        if u["rows"]["status"] == "value":
            rowsn += len(u["rows"]["value"]); unres += sum(1 for r in u["rows"]["value"] if r not in rows)
        else: kinds["rows:" + u["rows"]["status"]] += 1
print(json.dumps(dict(participants=len(parts), units=tot, narrative_mismatches=mism, arm_kinds=kinds, referent=ref, narrative_referent_differ=differ, confidence=conf, alternatives=alts, cited_rows=rowsn, unresolved_rows=unres), indent=1))
