from pathlib import Path
import hashlib,json,difflib
r=Path(__file__).resolve().parents[2]
a=r/'docs/review-artifacts'
files=sorted([r/'README.md',*list((r/'docs').glob('*.md')),r/'.gitignore',r/'pom.xml',*list((r/'src').rglob('*.*'))],key=lambda p:p.relative_to(r).as_posix())
entries=[dict(path=p.relative_to(r).as_posix(),sha256=hashlib.sha256(p.read_bytes()).hexdigest(),bytes=p.stat().st_size) for p in files]
canonical=''.join(f"{e['sha256']}  {e['path']}\n" for e in entries)
manifest=dict(status='WORKING_TREE_REVIEW_SUBMISSION_NOT_APPROVED',algorithm='SHA-256 of raw file bytes; composite SHA-256 of UTF-8 lines: lowercase hash, two spaces, forward-slash relative path, LF; ordinal path order',exclusions='review-artifacts directory (including this manifest), future reviews and baseline receipt; original Review #1 is included',composite_sha256=hashlib.sha256(canonical.encode()).hexdigest(),files=entries)
(a/'review2-manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
changes=[]
for p in files:
 if p.name=='PHASE_0_SENIOR_REVIEW.md' or not p.suffix=='.md':continue
 old=a/'review1-input'/p.relative_to(r)
 before=old.read_text(encoding='utf-8').splitlines(keepends=True) if old.exists() else []
 changes.extend(difflib.unified_diff(before,p.read_text(encoding='utf-8').splitlines(keepends=True),fromfile='a/'+p.relative_to(r).as_posix() if old.exists() else '/dev/null',tofile='b/'+p.relative_to(r).as_posix()))
(a/'review2-changes.diff').write_text(''.join(changes),encoding='utf-8')
print(len(entries),'manifest entries; diff lines',len(changes))
