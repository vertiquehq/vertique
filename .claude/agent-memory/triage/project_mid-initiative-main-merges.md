---
name: mid-initiative-main-merges
description: On this repo an initiative's earlier slices can already be merged into local main, so "introduced by this branch" must be checked against the pre-branch commit, not the merge-base
metadata:
  type: project
---

An initiative's earlier slices are sometimes merged into **local `main`** while the
initiative is still in flight (seen on `feat/param-shape-parity`: `git merge-base main HEAD`
was the branch's own S1–S4 merge commit, so `git diff main...HEAD` showed *nothing* for files
the branch had rewritten).

**Why:** it matters for adjudication — a regression the review calls "newly introduced by this
branch" looks pre-existing if you diff against `main`, which flips a mandatory FIX_NOW into a
deferrable "pre-existing" item. That misclassification is exactly what the Adjacent Defects
Rule forbids.

**How to apply:** when a finding's verdict depends on "did this branch introduce it", locate the
introducing commit directly (`git log --oneline --follow -- <file>` and read that commit's diff)
rather than trusting `git diff main...HEAD` or the merge-base. Treat every commit of the unit of
work as in-scope for the PR under review, whether or not it already sits on `main`.
