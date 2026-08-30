import { existsSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const argumentsByName = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  argumentsByName.set(process.argv[index], process.argv[index + 1]);
}

const serverUrl = argumentsByName.get("--url");
const scenario = argumentsByName.get("--scenario");
if (!serverUrl || !scenario) {
  console.error("required arguments: --url <url> --scenario <scenario>");
  process.exit(2);
}

const fixtureRoot = dirname(fileURLToPath(import.meta.url));
const clientEntry = join(
  fixtureRoot,
  "node_modules",
  "@modelcontextprotocol",
  "client",
  "dist",
  "index.mjs",
);
if (!existsSync(clientEntry)) {
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

const { Client, StreamableHTTPClientTransport } = await import(
  pathToFileURL(clientEntry),
);
const PUBLIC_TOOL = "interop.public";
const RESTRICTED_TOOL = "interop.restricted";
const tokens = {
  shouldCallTheRestrictedToolAsBearerAlice: "alice",
  shouldFailInvalidBearerWithoutAnonymousDowngrade: "invalid",
};

const authProvider = tokens[scenario]
  ? { token: async () => tokens[scenario] }
  : undefined;
const client = new Client(
  { name: "vertique-t028-client", version: "1.0.0" },
  { versionNegotiation: { mode: { pin: "2026-07-28" } } },
);
const transport = new StreamableHTTPClientTransport(new URL(serverUrl), {
  authProvider,
});

let report;
try {
  await client.connect(transport);
  const discover = client.getDiscoverResult();
  const listed = await client.listTools();
  const calledTool =
    scenario === "shouldDiscoverListAndCallAsAnonymous"
      ? PUBLIC_TOOL
      : scenario === "shouldCallTheRestrictedToolAsBearerAlice"
        ? RESTRICTED_TOOL
        : undefined;
  if (!calledTool) {
    throw new Error(`scenario unexpectedly connected: ${scenario}`);
  }
  const called = await client.callTool({ name: calledTool, arguments: {} });
  report = {
    scenario,
    protocolEra: client.getProtocolEra(),
    negotiatedProtocolVersion: client.getNegotiatedProtocolVersion(),
    discoveredProtocolVersions: discover?.supportedVersions,
    toolNames: listed.tools.map((tool) => tool.name),
    calledTool,
    result: called,
  };
} catch (error) {
  if (scenario !== "shouldFailInvalidBearerWithoutAnonymousDowngrade") {
    throw error;
  }
  report = {
    scenario,
    failed: true,
    errorName: error?.constructor?.name ?? "Error",
    errorMessage: String(error?.message ?? error),
  };
} finally {
  await client.close();
}

console.log(`T028_RESULT=${JSON.stringify(report)}`);
