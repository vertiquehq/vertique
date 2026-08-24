import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { join } from "node:path";

const argumentsByName = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  argumentsByName.set(process.argv[index], process.argv[index + 1]);
}

const fixtureRoot = argumentsByName.get("--fixture-root");
const serverUrl = argumentsByName.get("--url");
const scenario = argumentsByName.get("--scenario");
if (!fixtureRoot || !serverUrl || !scenario) {
  console.error("required arguments: --fixture-root <path> --url <url> --scenario <id>");
  process.exit(2);
}

const runner = join(
  fixtureRoot,
  "node_modules",
  "@modelcontextprotocol",
  "conformance",
  "dist",
  "index.js",
);
if (!existsSync(runner)) {
  const install = spawnSync(
    "npm",
    ["ci", "--ignore-scripts", "--no-audit", "--no-fund", "--prefix", fixtureRoot],
    { stdio: "inherit" },
  );
  if (install.error) {
    throw install.error;
  }
  if (install.status !== 0) {
    process.exit(install.status ?? 1);
  }
}

const run = spawnSync(
  process.execPath,
  [
    runner,
    "server",
    "--url",
    serverUrl,
    "--scenario",
    scenario,
    "--spec-version",
    "2026-07-28",
    "--output-dir",
    "results",
  ],
  { stdio: "inherit" },
);
if (run.error) {
  throw run.error;
}
process.exit(run.status ?? 1);
