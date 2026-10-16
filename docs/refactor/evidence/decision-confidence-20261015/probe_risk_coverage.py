"""Reproduce instrument failures using synthetic rows only; never open gold/corpus files."""
from pathlib import Path
import csv,hashlib,json,math,subprocess,sys,tempfile
repo=Path(sys.argv[1]).resolve()
sys.path.insert(0,str(repo/'tools/recall-study'))
import risk_coverage as risk

def at_two_thirds(units):
    points=[accuracy for coverage,accuracy in risk.curve(units,0) if coverage==2/3]
    assert points and len(set(points))==1
    return points[0]
old=[(1,1,.99),(1,2,.9),(1,1,.7)]
actual=[(1,1,.99),(1,2,.1),(1,1,.7)]
assert at_two_thirds(old)==50.0 and at_two_thirds(actual)==100.0

def load(rows):
    with tempfile.TemporaryDirectory(prefix='sm4s-risk-synthetic-') as directory:
        with (Path(directory)/'recall-map-NN03.tsv').open('w',newline='') as stream:
            writer=csv.writer(stream,delimiter='\t');writer.writerow(['recallOnsetSeconds','group','mapAnchorMass']);writer.writerows(rows)
        return risk.load_scored(directory,risk.DEFAULT_CONFIDENCE)
rows,dropped=load([['1','1','.9'],['2','','.8'],['3','2','']])
kept=next(iter(rows.values()));assert len(kept)==1 and dropped==1
points=risk.curve([(1,predicted,score) for _,predicted,score in kept],0)
assert points[-1]==(1.0,100.0)
nonfinite,nonfinite_dropped=load([['1','1','NaN'],['2','2','inf']])
assert len(next(iter(nonfinite.values())))==2 and nonfinite_dropped==0
assert sum(not math.isfinite(row[2]) for row in next(iter(nonfinite.values())))==2
tied=risk.curve([(1,1,.5),(1,2,.5),(1,1,.5)],0)
assert len(tied)==20 and {point[0] for point in tied}=={1.0}
print(json.dumps({'assessedCommit':subprocess.check_output(['git','rev-parse','HEAD'],cwd=repo,text=True).strip(),'sourceSha256':hashlib.sha256((repo/'tools/recall-study/risk_coverage.py').read_bytes()).hexdigest(),'readScope':'Only synthetic temporary TSVs and in-repository Python code; no gold, corpus or saved model outputs opened.','rankReversal':{'rawArgmaxAccuracyAtTwoThirds':at_two_thirds(old),'emittedOwnMassAccuracyAtTwoThirds':at_two_thirds(actual)},'shrinkingPopulation':{'stipulatedEligible':3,'retained':len(kept),'reportedDropped':dropped,'reportedFullCoverage':points[-1][0],'reportedAccuracy':points[-1][1],'stipulatedPopulationAccuracyPercent':100/3},'nonfinite':{'accepted':2,'reportedDropped':nonfinite_dropped},'ties':{'nominalGridPoints':len(tied),'distinctActualCoverages':sorted({p[0] for p in tied})}},indent=2,allow_nan=False))
