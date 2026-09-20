"""Bind unchanged relation/numerical source, separately from executed output parity."""
import re,subprocess,json,hashlib
from pathlib import Path
base='7358eb7c';candidate='369965b2';paths=['story/src/main/scala/storymodel4s/story/alignment.scala','align/src/main/scala/storymodel4s/align/bridge/StorySourceView.scala'];rows=[]
def read(ref,path):return subprocess.check_output(['git','show',ref+':'+path],text=True)
def tokens(s):
 return [m.group() for m in re.finditer(r'"(?:\\.|[^"\\])*"|/\*.*?\*/|//[^\n]*|\w+|[^\w\s]',s,re.S) if not m.group().startswith(('/*','//'))]
for path in paths:
 old=read(base,path);new=read(candidate,path)
 if path.startswith('story/'):
  old=old[old.index('class StoryAlignmentSource'):];old=old[old.index('def discoursePosition'):]
  new=new[new.index('class StoryAlignmentSource'):];new=new[new.index('def discoursePosition'):new.index('  private final class StoryTextAlignmentSource')]
 else:
  old=old[old.index('  private def toRef'):old.index('object StorySourceView:')]
  new=new[new.index('  private def toRef'):new.index('object StorySourceView:')]
 assert tokens(old)==tokens(new),path
 rows.append(dict(path=path,comparison='Exact non-comment/non-whitespace tokens with strings preserved',equal=True,tokenSha256=hashlib.sha256(json.dumps(tokens(old)).encode()).hexdigest()))
x=dict(base=base,candidate=candidate,scope='Alignment relation/query implementation after projection accessor; complete StorySourceView feature/relation implementation after canonical text binding and before companion factories',rows=rows,qualification='Source token preservation only; Scala indentation separately cold-reviewed. Executed numerical preservation is the separate exact per-backend HSMM court.')
Path(__file__).with_name('unchanged-scoring-source.json').write_text(json.dumps(x,indent=2)+'\n');print('source token equivalence passed')
