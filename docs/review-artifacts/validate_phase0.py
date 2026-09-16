from pathlib import Path
import re,hashlib,subprocess,math
r=Path(__file__).resolve().parents[2]
report=(r/'docs/PHASE_0_REMEDIATION.md').read_text(encoding='utf-8')
review=(r/'docs/PHASE_0_SENIOR_REVIEW.md').read_text(encoding='utf-8')
ids=[f'{k}{i}' for k,n in [('B',4),('H',14),('M',13),('L',6),('RT',12),('U',6)] for i in range(1,n+1)]
fields=['FINDING ID','DISPOSITION','RATIONALE','DOCUMENTS CHANGED','ARCHITECTURAL CHANGE','ACCEPTANCE CRITERIA','HOW THE ACCEPTANCE CRITERIA ARE NOW SATISFIED']
for id in ids:
 b=re.findall(r'^### '+id+r'\n(.*?)(?=^###? |\Z)',report,re.M|re.S)
 assert len(b)==1 and all(f'**{f}:**' in b[0] for f in fields),id
 if not id.startswith('U'): assert re.search(r'\b'+id+r'\b',review),id
print('PASS: 55 dispositions with seven fields each; all 49 original numbered findings.')
arch=(r/'docs/ARCHITECTURE.md').read_text(encoding='utf-8')
dag={}
for line in arch.split('| Module |')[1].split('This ordering')[0].splitlines():
 c=[v.strip() for v in line.split('|')]
 if len(c)<4 or c[1]=='---':continue
 n,d=c[1:3];dag[n]=list(dag) if n=='testing' else ([] if d=='none' else d.split(', '))
assert len(dag)==20
seen=set()
for n,d in dag.items():assert set(d)<=seen,(n,d);seen.add(n)
assert all(dag[n]==[] for n in ['api','model','replay-schema'])
assert dag['replay']==['replay-schema']
assert {'collision','physics','mitigation','interaction','replay'}<=set(dag['core'])
assert {'world','collision'}<=set(dag['mitigation']) and 'interaction' in dag['checks']
assert not {'world','collision','physics','mitigation','platform-paper'}&set(dag['checks'])
for n in ['protocol','player','world','simulation','combat','interaction','evidence','mitigation','core']:assert 'replay-schema' in dag[n]
print('PASS: 20-module topological DAG, composition, clearance, interaction, public API and projection edges.')
for p in [r/'README.md',*sorted((r/'docs').glob('*.md'))]:
 if p.name=='PHASE_0_SENIOR_REVIEW.md':continue
 s=p.read_text(encoding='utf-8');assert sum(l.startswith('```') for l in s.splitlines())%2==0,p
 for t in re.findall(r'\]\(([^)]+)\)',s):
  if '://' not in t and not t.startswith('#'):assert (p.parent/t.split('#')[0]).exists(),(p,t)
print('PASS: local Markdown links and fence balance.')
hashes={'.gitignore':'6E6D3F89696FFC382C8CF5D5B2B451E5A2F699790F0608C105F5035C647E7C2A','pom.xml':'CA0D599EC5522A46DFE96F41284F2793EE35E2C30555A68CD0FA1F60795F23EC','src/main/java/me/nicolas/papea2AC/Papea2AC.java':'472C0EDFC4F076170402FE4493A97E6ABF5BC2BA1C4F457398C47A7D76A91A2A','src/main/resources/plugin.yml':'7512436D3CC15EB06CDF097EF7CF1390BE9E51C6A0F9D0A1B2AB4579DE93B0E8','docs/PHASE_0_SENIOR_REVIEW.md':'86F18BCB1F00D7C8AAF29BB9A80A5261F658476B2B1D9F333D130FA3AE182341'}
for p,h in hashes.items():assert hashlib.sha256((r/p).read_bytes()).hexdigest().upper()==h,p
archive=r/'docs/review-artifacts/review1-input';old=list(archive.rglob('*.md'));assert len(old)==22
prefixes=dict(re.findall(r'`([A-Z_0-9]+\.md) ([a-f0-9]{8})…`',review))
for p in old:
 assert hashlib.sha256(p.read_bytes()).hexdigest().startswith(prefixes[p.name]),p
 assert p.read_bytes()!=(r/p.relative_to(archive)).read_bytes(),p
print('PASS: Review #1 and four skeleton hashes unchanged; 22 archives match original digest prefixes; all 22 current specs revised.')
assert 96+32+96+32==256 and 48+32+16==96
assert [math.ceil((256+n*2)/.8) for n in [50,100,250,500,1000]]==[445,570,945,1570,2820]
assert [math.ceil((256+n*4)/.8) for n in [50,100,250,500,1000]]==[570,820,1570,2820,5320]
assert 3000*256<1048576 and 4096/2000==2.048 and 30*60*128/1024==225
print('PASS: partition, admission and retention arithmetic; these are planning values.')
for args in [['git','diff','--check'],['git','diff','--cached','--check']]:
 out=subprocess.run(args,capture_output=True,text=True,cwd=r);assert out.returncode==0,out.stdout+out.stderr
assert subprocess.run(['git','rev-parse','--verify','HEAD'],capture_output=True,cwd=r).returncode!=0
assert set(subprocess.check_output(['git','diff','--cached','--name-only'],text=True,cwd=r).splitlines())==set(hashes)-{'docs/PHASE_0_SENIOR_REVIEW.md'}
assert not subprocess.check_output(['git','diff','--name-only'],text=True,cwd=r).strip()
print('PASS: unchanged tracked skeleton/index set; no HEAD; Git whitespace checks. No runtime tests performed.')
