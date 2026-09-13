#!/usr/bin/env python3
import argparse, hashlib, json, struct

def read_glb(p):
    b=open(p,'rb').read(); assert b[:4]==b'glTF', 'Not a GLB/VRM file'
    length=struct.unpack_from('<I',b,8)[0]; off=12
    while off<length:
        clen,ctype=struct.unpack_from('<II',b,off); chunk=b[off+8:off+8+clen]
        if ctype==0x4E4F534A:return b,json.loads(chunk.rstrip(b' \t\r\n\0').decode())
        off+=8+clen
    raise RuntimeError('JSON chunk missing')

ap=argparse.ArgumentParser();ap.add_argument('vrm');ap.add_argument('--manifest',default='app/src/main/assets/avatar/avatar_manifest.json');a=ap.parse_args()
b,doc=read_glb(a.vrm); vrm=doc.get('extensions',{}).get('VRM'); assert vrm, 'VRM extension missing'
meta=vrm.get('meta',{}); groups=vrm.get('blendShapeMaster',{}).get('blendShapeGroups',[]); names={g.get('name') for g in groups}
out={"avatar":"Elina","format":"VRM","version":vrm.get('specVersion','unknown'),"file":"elina.vrm","sha256":hashlib.sha256(b).hexdigest(),"humanoid":bool(vrm.get('humanoid',{}).get('humanBones')),"lookAt":False,"springBone":bool(vrm.get('secondaryAnimation',{}).get('boneGroups')),"expressions":{"happy":any(x in names for x in ['Joy','Fun']),"angry":'Angry' in names,"sad":'Sorrow' in names,"surprised":'Surprised' in names,"aa":'A' in names,"ih":'I' in names,"ou":'U' in names,"ee":'E' in names,"oh":'O' in names}}
open(a.manifest,'w').write(json.dumps(out,indent=2)+'\n')
print(f"VRM {out['version']}, humanoid={out['humanoid']}, springBone={out['springBone']}, expressions={sorted(names)}")
print(f"License metadata: {meta.get('licenseName','unspecified')}")
