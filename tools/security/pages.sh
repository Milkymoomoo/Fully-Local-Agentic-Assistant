#!/usr/bin/env bash
# Helpers for the workflows that write to the gh-pages branch (security history, docs site).
#   pages.sh open  DIR          check out gh-pages into DIR (a git worktree; creates the branch if it doesn't exist)
#   pages.sh push  DIR "msg"    commit DIR and push, rebasing on top of a concurrent writer if needed
# All writers also share the concurrency group "gh-pages" so they run one at a time.
set -euo pipefail
cmd="$1"; dir="$2"
git config --global user.name "github-actions[bot]"
git config --global user.email "41898282+github-actions[bot]@users.noreply.github.com"
case "$cmd" in
  open)
    if git ls-remote --exit-code --heads origin gh-pages >/dev/null; then
      git fetch --depth=1 origin gh-pages
      git worktree add "$dir" FETCH_HEAD && git -C "$dir" checkout -q -B gh-pages
    else
      git worktree add --orphan -b gh-pages "$dir"
      touch "$dir/.nojekyll"
    fi
    mkdir -p "$dir/security" ;;
  push)
    cd "$dir"; git add -A
    git diff --cached --quiet && { echo "nothing to commit"; exit 0; }
    git commit -q -m "$3"
    for i in 1 2 3 4 5; do
      git push -q origin HEAD:gh-pages && exit 0
      echo "push rejected, rebasing (try $i)"; git fetch -q --depth=50 origin gh-pages; git rebase -q FETCH_HEAD || { git rebase --abort; exit 1; }
    done
    exit 1 ;;
esac
