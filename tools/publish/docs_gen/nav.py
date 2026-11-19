"""Docs generator (run from anywhere): see AGENTS.md §6 "Add a setting"."""
import os
_HERE = os.path.dirname(os.path.abspath(__file__))
# the docs live in repo_files/docs in the dev repo and in docs/ in the public copy
DOCS = next(p for p in (os.path.join(_HERE, "..", "repo_files", "docs"), os.path.join(_HERE, "..", "..", "..", "docs")) if os.path.isdir(p))
import re,os
D=DOCS
order=['README.md','features/app-basics.md','features/wake-word.md','features/speech-recognition.md','features/thinking-model.md','features/phone-actions.md','features/web-and-news.md','features/voices.md','features/memory.md','features/assistants-and-personalities.md','features/stories.md','features/read-to-me.md','features/avatar-studio.md','features/avatar-2d.md','features/avatar-3d.md','features/autorig3d.md','features/widget.md','features/photo-to-3d.md','features/models-and-downloads.md','features/privacy-permissions-storage.md','settings-reference.md','architecture.md','status.md','troubleshooting.md','glossary.md','SECURITY.md']
def title(p):
    for l in open(os.path.join(D,p)):
        if l.startswith('# '): return l[2:].strip().replace('Milky Moo\'s Assistant: documentation','Docs home')
for i,p in enumerate(order):
    f=os.path.join(D,p)
    if not os.path.exists(f): print('missing',p); continue
    rel=lambda q: os.path.relpath(os.path.join(D,q),os.path.dirname(f))
    parts=[]
    if i>0: parts.append(f'<a href="{rel(order[i-1])}">← {title(order[i-1])}</a>')
    if p!='README.md': parts.append(f'<a href="{rel("README.md")}">Docs home</a>')
    if i<len(order)-1: parts.append(f'<a href="{rel(order[i+1])}">{title(order[i+1])} →</a>')
    nav='<!-- nav -->\n<p align="center"><sub>'+' &nbsp;·&nbsp; '.join(parts)+'</sub></p>\n<!-- /nav -->'
    s=open(f).read()
    s=re.sub(r'<!-- nav -->\n<p align="center"><sub>.*?</sub></p>\n<!-- /nav -->',nav,s)
    s=re.sub(r'<!-- nav -->(?!\n<p align="center"><sub>)',nav,s)
    open(f,'w').write(s)
print('done')
