<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Pull request review aid

Advisory diff for a pull request: how the Maven reactor graph changed, and how each generated OpenAPI document changed. The result is a sticky pull request comment, the job summary, and the `pr-review-aid` artifact. The workflow is not a required check. It fails only when `oasdiff breaking --fail-on ERR` reports an error-level breaking change, after the comment and artifact exist.

`pull_request_target` is not used. The workflow runs with the pull request's code and a token that can write comments only in this repository. A fork pull request skips the comment write. The summary and the artifact are still published.

There is no path filter on the workflow. If a later push leaves no relevant files, the builds are skipped and an existing comment is removed.

## What is compared

Relevant changes are any `pom.xml`, anything under `src/main/`, and files in this directory. Other changes skip the Maven builds.

The comparison base is the merge base of the base branch tip and the pull request head, not the tip of the base branch. Commits that landed on the base branch after the branch point are not shown as changes the pull request reverted. Classification and the base checkout both use that merge base.

The module graph is read from `pom.xml` files. It does not run Maven. Declared dependencies that point at another reactor module are edges. Parent links are recorded separately and are not drawn. External dependencies keep their version, or `managed` when the pom omits one. Properties such as `${revision}` are resolved when the reactor defines them.

A module version change is not listed on its own when it matches the reactor root: the root module is the pom at the checkout root, and a module whose old version equals the root's old version and whose new version equals the root's new version is part of that one move. An external dependency in the root's group is treated the same way when its version moves with the root. Parent links and reactor edges do not carry a version, so a parent or reactor dependency that only follows the root version is not a per-module change. The move is one line, `Reactor version: <old> -> <new>`, and only when something else changed too. A reactor version move with no other module or API change produces no comment.

A module whose version moves differently from the root is still listed. A module whose version changes while the root version stays the same is still listed.

OpenAPI documents are `target/classes/openapi.yaml` from a compile of the modules that generate one, plus the reactor dependencies those modules need. A document that exists on both sides is passed to the `oasdiff` CLI (`changelog` and `breaking --fail-on ERR`). The CLI does not upload specs. A document only on the head is reported as new. A document only on the base is reported as removed. An empty changelog is omitted. The changelog's top title line is dropped and the remaining headings are shifted down by two levels, then wrapped in a details block. The block is open when that spec is the only OpenAPI change. Error-level breaking changes are listed above the block, under `Breaking changes (ERR)`.

The diagram shows modules that were added, removed, or changed, plus one hop of dependency neighbours in either direction. An edge is drawn only when at least one end is an added, removed, or changed module, so an edge between two neighbours is omitted. Added modules are green, removed modules are red, changed modules are yellow, and neighbours are grey. Above 60 nodes the comment keeps the table and the graph is written as `module-graph.dot`. `module-graph.svg` is written when Graphviz `dot` is on `PATH`. The 60 count is touched modules plus neighbours.

When nothing changed, no comment is created and an existing comment is deleted. The summary says `No module graph or API changes`. A reactor version move on its own counts as nothing changed.

## Run it locally

Check out the merge base beside the head. One way is a detached worktree:

```bash
git worktree add --detach /tmp/review-base "$(git merge-base <base-tip> HEAD)"
```

Compile each side and collect specs:

```bash
CHECKOUT=/tmp/review-base SPECS_OUT=/tmp/review-out/base-specs bash scripts/pr-review-aid/build-specs.sh
CHECKOUT="$PWD" SPECS_OUT=/tmp/review-out/head-specs bash scripts/pr-review-aid/build-specs.sh
```

`build-specs.sh` prints the Maven command it runs. It is a compile, not a test run: `./mvnw -ntp -B -DskipTests -Darchetype.test.skip=true -pl <modules> -am compile`. The module list is every pom that sets `<outputFileName>openapi</outputFileName>`.

Then render the report. `--dry-run` prints the comment action instead of calling `gh`:

```bash
OASDIFF_BIN=oasdiff node scripts/pr-review-aid/run.mjs \
  --base /tmp/review-base \
  --head "$PWD" \
  --base-specs /tmp/review-out/base-specs \
  --head-specs /tmp/review-out/head-specs \
  --out /tmp/review-out \
  --pr 1 \
  --repo vertiquehq/vertique \
  --dry-run
```

Outputs under `--out`:

| File | When |
| --- | --- |
| `report.md` | Always. The full report, or one line when nothing changed. |
| `comment.md` | When there is something to post. Starts with `<!-- vertique-pr-review-aid -->`. Cut at 60,000 characters on a section boundary. |
| `module-graph-base.json`, `module-graph-head.json` | Reactor models. |
| `module-graph.dot`, `module-graph.svg` | When the diagram is larger than 60 nodes. SVG only if `dot` is installed. |
| `oasdiff/` | Changelog and breaking output for each compared spec. |
| `breaking` | `true` or `false`. |

The reactor model alone:

```bash
node scripts/pr-review-aid/maven-graph.mjs --root .
```

Set `PR_REVIEW_AID_NODE_CAP` or pass `--node-cap` to `run.mjs` to change the inline diagram limit.

## Refresh the golden files

From the repository root:

```bash
UPDATE_GOLDEN=1 node --test release/tests/pr-review-aid.test.mjs
```

The real `oasdiff` test runs when `oasdiff` is on `PATH` or `OASDIFF_BIN` points at it. Otherwise that test is skipped.
