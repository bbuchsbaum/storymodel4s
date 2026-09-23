from pathlib import Path
import csv,gzip,hashlib,json,struct
root=Path('/Volumes/My Passport for Mac/data/openneuro/ds001132-1.0.0')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
verification=json.loads((root.parent/'ds001132-1.0.0-verification.json').read_text())
files={row['file']:row for row in verification['annexFiles']}
result=[]
for task in ['freerecall','sherlockPart1','sherlockPart2']:
 name=f'sub-03/func/sub-03_task-{task}_bold.nii.gz';image=root/name
 with gzip.open(image,'rb') as f:header=f.read(348)
 assert len(header)==348 and struct.unpack('<i',header[:4])[0]==348 and header[344:348]==b'n+1\0'
 dims=struct.unpack('<8h',header[40:56]);pixdim=struct.unpack('<8f',header[76:108]);units=header[123]
 toffset=struct.unpack('<f',header[136:140])[0]
 sidecar=root/f'task-{task}_bold.json';sidecar_value=json.loads(sidecar.read_text())
 events=root/f'sub-03/func/sub-03_task-{task}_events.tsv'
 with events.open(newline='') as f:rows=list(csv.DictReader(f,delimiter='\t'))
 assert units&0x38==8 and sidecar_value['RepetitionTime']==pixdim[4]==1.5
 result.append(dict(task=task,image=name,imageSha256=files[name]['sha256'],header348Sha256=hashlib.sha256(header).hexdigest(),dimensions=list(dims),pixdim=list(pixdim),xyztUnits=units,timeOffset=toffset,sidecar=str(sidecar.relative_to(root)),sidecarSha256=sha(sidecar),sidecarRepetitionTime=sidecar_value['RepetitionTime'],events=str(events.relative_to(root)),eventsSha256=sha(events),taskEvents=rows))
print(json.dumps(dict(schema='local-openneuro-header-observation/v1',dataset='ds001132',release='1.0.0',revision=verification['commit'],participant='sub-03',runs=result,scope='Development header/task-event observations only. No voxel analysis, transcript identity, video origin, crop application or scanner alignment admitted.'),indent=2))
