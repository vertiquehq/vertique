<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Example: workflow load node

A node for running the real workflow engine under load, one process per node, against one PostgreSQL database.

**This is a test harness, not a benchmark.** Whatever a run of it measures belongs to one setup on one machine, with one
synthetic workload and a stand-in for a service. The numbers are not product capacity, are not a promise of what the framework
does in a deployment, and say nothing about hardware other than the one they were taken on. Use them to compare
configurations of the same setup against each other.

The module is an unpublished example. It is in the build so that it keeps compiling, and it is not part of the BOM or the module
index.

## What a node is

A node is the launcher entry point `dev.vertique.launcher.VertiqueApplication` with the `@VertiqueApp` component in this
module. It runs, from product modules only:

- the workflow engine and its executor,
- the outbox relay (with the cron scheduler and job repository its startup validation requires),
- the intake sampler that applies the intake limit,
- the service dispatch infrastructure and the signal endpoint that records relayed signals.

It has no REST layer and no management verticle, so it opens no management port. It binds no observer. Its only listener is a
loopback ingress that belongs to the harness.

## The workload

One definition, `load`: **start, one service dispatch, wait for a signal, one timer, complete.**

An instance therefore records three commands (the start, the callback signal and the timer firing) and one outbox row (the
dispatch). The start segment records the dispatch and reaches the wait in a single transaction.

The dispatch handler is registered on every node. When the relay delivers a dispatch, the handler waits on a Vert.x timer for
`load.handlerDelayMs` (no thread blocks; zero skips the timer), then records the callback signal in a transaction through
`RelayedSignals<SqlClient>` and answers only after that transaction has committed. The signal's dedup key depends only on the
instance key, so a redelivery records nothing new. The callback creates no outbox row, and the handler logs nothing per call.
The delay occupies one relay slot for its duration, as a real service call would.

## The ingress

Each node listens on `load.ingress.host` (default `127.0.0.1`) and `load.ingress.port`. It calls the workflow client's public
`start`, so a driver in another process sees exactly what an application sees.

| Request | Answer |
| --- | --- |
| `POST /start?key=K` | `200 {"outcome":"accepted","workflowId":"..."}` when the start was recorded, or when the key had been started before. `503 {"outcome":"refused"}` when the intake limit refused it; a refusal writes nothing and leaves the key free to send again. `500 {"outcome":"failed","error":"<class>"}` for any other failure. `400` for a missing or malformed key (1 to 128 characters of letters, digits and `. _ : -`). |
| `GET /ready` | `200` once the node has finished starting. The ingress deploys after the services, the executor and the relay. |
| `GET /effective` | `200` with the configuration the node runs on, read from the typed product configuration objects. |

Port `0` lets the system choose one. Set `load.ingress.portFile` and the node writes the bound port there once it listens.

Product configuration ignores keys it does not know, so a misspelled key leaves the default in place and looks like a working
node. Read `/effective` from every node before offering load and compare it with what the run intended. The `load` section is
the exception: the node rejects a key there that it does not know.

## Configuration

`config/application.json` is the baseline, the design as it ships: pool 20, executor concurrency 8 on one instance, one relay
instance, `LISTEN_NOTIFY` with a 1000 ms interval, executor poll 5000 ms, the intake limit on at its defaults, handler delay
20 ms, timer 1000 ms. Per-process settings go through `--conf`, which wins over the file:

```bash
--conf '{"db":{"properties":{"application_name":"load-node-3"}},"load":{"ingress":{"port":18083}}}'
```

A node refuses to start unless

- `flyway.mode` is `DISABLED`, because the schema is migrated once before any node starts, and
- `db.properties.application_name` is set, because the run attributes pooled work to a node by the name of its connections.
  Give every node its own name.

All nodes of a run must use the same `load.timerDelayMs`: the timer length is part of the definition, so a node that differs
builds a different plan and cannot run the commands of the others.

## Migrate once

The workflow, outbox and job table sets all start at version 1, so they cannot share one Flyway history. The admin entry point
runs each as its own named Flyway instance with its own history table (`flyway_schema_history_workflow`,
`flyway_schema_history_inbox_outbox`, `flyway_schema_history_job`). The three sets share the default schema, so it also sets
`baselineOnMigrate` with baseline version `0`, which Flyway needs to let an instance run in a schema that already holds another
instance's tables; every script still runs.

```bash
java -cp "$CP" dev.vertique.examples.workflow.load.admin.LoadAdmin migrate --conf '{"db":{"host":"localhost","port":5432}}'
```

## Arms

An arm changes only configuration and trigger state:

| Arm | Workflow trigger | Outbox trigger | Workflow poll | Outbox strategy, poll |
| --- | --- | --- | --- | --- |
| `ON` | on | on | 5000 ms | `LISTEN_NOTIFY`, 1000 ms |
| `WF_OFF` | off | on | 100 ms | `LISTEN_NOTIFY`, 1000 ms |
| `OUT_OFF` | on | off | 5000 ms | `POLLING`, 50 ms |
| `OFF` | off | off | 100 ms | `POLLING`, 50 ms |
| `ON_MATCHED` | on | on | 100 ms | `POLLING`, 50 ms |

The workflow listener cannot be switched off by configuration. With its trigger disabled it stays connected and receives
nothing, and nothing warns, so a node in an arm with the workflow trigger off still holds a workflow listener connection.

Set the trigger state **once, in the quiet step, before any node starts.** The command refuses while another session's last
statement is a `LISTEN`, and it reads the state back and fails if it differs from the arm:

```bash
java -cp "$CP" dev.vertique.examples.workflow.load.admin.LoadAdmin arm OFF --conf '...'
java -cp "$CP" dev.vertique.examples.workflow.load.admin.LoadAdmin triggers --conf '...'
java -cp "$CP" dev.vertique.examples.workflow.load.admin.LoadAdmin arm-config OFF
```

`arm-config` prints the configuration overlay that every node of the run needs for the arm.

## Running nodes

```bash
./mvnw -ntp -pl examples/vertique-example-workflow-load -am package dependency:build-classpath \
    -DskipTests -Darchetype.test.skip=true -Dmdep.outputFile=target/classpath.txt -Dmdep.includeScope=runtime
CP="examples/vertique-example-workflow-load/target/vertique-example-workflow-load-0.4.0-SNAPSHOT.jar:$(cat examples/vertique-example-workflow-load/target/classpath.txt)"

cd examples/vertique-example-workflow-load      # the launcher reads config/ from the working directory
java -cp "$CP" dev.vertique.launcher.VertiqueApplication --conf '{...per-process settings...}'
```

JVM flags (heap, garbage collector, logging of pauses) go on the `java` command line, and the Vert.x thread pools through the
launcher's `vertx.options` configuration section.

Configuration resolves as for any launcher application: the `config/` directory (or `VERTX_CONFIG_LOCATIONS`), the environment,
system properties, and `--conf` with the highest precedence. The module also builds a container image with
`jib:dockerBuild`, like the other examples, with the same entry point.

## The measurement harness

The node is one half of a load run. The other half is a set of separate processes that offer load, watch, and judge. All of
it lives in this module, in `dev.vertique.examples.workflow.load.harness`. It has no dependency beyond the product's own
modules and the JDK, and it changes no product behavior.

| Process | Class | Job |
| --- | --- | --- |
| Runner | `harness.run.LoadRun` | Carries one run from specification to verdict. |
| Driver | `harness.driver.LoadDriver` | Offers new starts on an open-loop schedule to the node ingresses. |
| Collector | `harness.collect.Collector` | Samples the database, the host and the database VM on one clock. |
| Nodes | `VertiqueApplication` | As above, one process each. |

### What a run does

1. Reads the specification **strictly**, or builds it from the frozen matrix file. An input the harness does not know,
   including a misspelled product setting whose value happens to be the default, fails the run before anything starts (exit
   code 2). So do a build whose jars are not the ones recorded when it was built, and an attempt over the retry ceiling or
   a repeat of a replicate that already has a valid run.
2. Writes an **attempt record** (`attempt.json`) before the first step and finishes it on every exit.
3. Resets the database: drop it, create it empty. No analyze, default autovacuum (see *Cold start* below).
4. In the quiet step, with no node running, migrates once and sets the arm's trigger state, reading it back.
5. Starts the nodes with migrations off and the same heap, thread pools and connection pool whatever the node count. It
   waits until each answers ready, then compares what each reports at `GET /effective` with what the run intended: the arm,
   the node settings, every accepted override, the Vert.x pools as they actually run, and the JVM flags. A difference ends
   the run before any load.
6. Starts the collector and lets the nodes settle, then checks that the listeners the arm expects are in place.
7. Starts the driver. Arrival *k* goes to ingress *k mod n*, and is due at a time that depends on the profile only.
8. Drains to a deadline, stops the collector and the nodes, reads the figures from the database in one snapshot, and writes
   the manifest and the verdict.

```bash
scripts/harness.sh build                                   # package; write the classpath and target/identity.json
scripts/harness.sh pg-up                                   # create or start the database container (wf017...)
scripts/harness.sh run scripts/smoke/ok.json out           # one run into out/<name>
scripts/harness.sh freeze matrix.json                      # make the matrix read-only; print its hash
scripts/harness.sh run-matrix matrix.json out              # every replicate in the matrix's order, retrying up to the ceiling
scripts/harness.sh evaluate out/smoke-ok                   # judge a finished run again from its raw files
scripts/harness.sh report matrix.json report out           # the report of a run matrix
scripts/harness.sh pg-down                                 # stop the container; it is never removed
scripts/smoke.sh                                           # the smoke runs with real nodes
```

The runner never creates, starts, stops or removes a container (except the stop an injection asks for); `pg-up` and
`pg-down` do, and only for a container whose name starts with `wf017`. It signals only the processes it started. Every
command that starts a run refuses when the sources changed since the build, or when the jars differ from those recorded in
`target/identity.json`; the manifest records the hash of the module's jar and of every dependency.

### The frozen matrix

The run matrix and its thresholds are **one file** that the harness loads (`scripts/matrix/proposed-calibration.json` is the
calibration proposal). Each point holds the run specification without its identity; the file holds the only thresholds; the
`order` lists every replicate in the order they run. The hash of the file is recorded in every run, and each run's
configuration has a fingerprint that is a hash of everything except its identity. The report combines runs only when their
matrix hash equals the report's, their fingerprint equals the one the matrix gives their point, and they ran the same
executable; any other run is listed as excluded with the reason. A replicate takes at most three attempts and only its first
valid attempt counts.

### The specification

A JSON file. Every input has a default and the resolved tree, defaults included, is written to the run's `spec.json` and
`manifest.json`, so nothing a run used is implicit. The main inputs:

```json
{
  "name": "scaling-on-n4-r1",
  "set": "scaling", "point": "scaling-on-n4", "diagnostic": false,
  "arm": "ON", "nodes": 4,
  "load": { "kind": "fixed", "ratePerSec": 400, "warmupSec": 30, "windowSec": 150 },
  "timeline": { "settleSec": 15, "recoverySec": 30, "replayRatePerSec": 50, "drainDeadlineSec": 60 },
  "node": { "heapMinMb": 256, "heapMaxMb": 512, "poolSize": 20, "eventLoopThreads": 2, "workerThreads": 4,
            "handlerDelayMs": 20, "timerDelayMs": 1000, "intake": { "enabled": false } },
  "collector": { "lockWatch": false },
  "injections": [ ]
}
```

- `load.kind` is `none`, `fixed`, `profile` (explicit segments with their own rate, burst and window flag) or `staircase`.
  Rates are new starts per second; each start records three commands, so the load in command-equivalents is three times the
  rate. A staircase gives a bracket or a lower bound, not a capacity.
- `node.confOverride` passes further product settings from a short list (`harness.spec.KnownConfig`); each one is compared
  with the value the node reports. Anything else is rejected.
- `injections` (`DB_TABLE_LOCK`, `DB_CONTAINER_STOP`, `NODE_PAUSE`, `DRIVER_PAUSE`, `LISTENER_KILL`) are failures for smoke
  and diagnostic runs only; a run with injections must be marked diagnostic.
- `diagnostics.statements` records every statement the database ran with `pg_stat_statements`. The server has to load the
  library (`harness.sh pg-statements on`), the run must be diagnostic, and the manifest names the extension.

### What a run leaves

| File | Content |
| --- | --- |
| `attempt.json` | When the attempt began, the step it reached, how it ended. |
| `manifest.json` | Code commit and the hashes of the executable, resolved specification, fingerprint, matrix hash, node JVM flags and pools, host, database image and settings, reset policy, timeline. |
| `result.json` | Validity and the rule behind it, outcome, load and completion figures, SQL metrics, stalls, listeners, reconciliation, system results, gates. |
| `stalls.json` | Every sustained dip and latency excursion with the aligned evidence around it. |
| `validity.json`, `listeners.json` | The validity verdict; every listener event, also those that recovered. |
| `driver/` | `arrivals.csv` (one row per planned arrival, nothing dropped), `seconds.csv`, `summary.json`, the JVM log. |
| `collector/` | `heartbeat.csv`, `seconds.csv` (SQL), `listeners.csv`, `statistics.csv`, `lockwatch*.{csv,json}`, `host.csv`, `procs.csv`, `vm.csv`, the JVM log. |
| `nodes/` | Each node's log, JVM log, `GET /effective` answer and exit marker. |
| `sql/` | `metrics.json`, `durable-starts.csv.gz`, and `statements.json` for a statements run. |

All sources are stamped with the host's epoch milliseconds. Database times are moved onto that clock by the offset the runner
measures at the start and at the end of a run (`timeline.json`).

### Validity is about the measurement

A run is `invalid` only when the generator did not keep its schedule or did not finish, the collector's heartbeat or recorder
or the host sampler did not record, the configuration was established, independently of the system's behavior, not to be what
the run intended (the nodes report other values, or a node exits with a configuration error of its own), the harness's own
environment was missing, or **one identifiable process outside the harness** used more than 100% of a core for more than ten
**consecutive** seconds. A busy kernel, system processes, the database VM and many small processes are kept as evidence and
never invalidate.

Engine errors, database stalls and outages, deadlocks, timeouts, a node that did not become ready or ran out of resources or
died, a listener that was not in place or was lost, and a database that is down when the figures are read are **results**.
The first two kinds of system failure at startup give a `system-failed` outcome with the reason; reading the figures from a
database that is down leaves the run without them and says so. The collector's liveness is its heartbeat, written on schedule
whatever the database answers; at every beat it says whether a database query is outstanding and for how long, so an outage
is evidence and not a collector failure.

A stall's cause is always `unresolved`. The evidence lists what coincided; a coincident condition is not an explanation.

### Metrics, as bounds and proxies

The SQL figures carry their limits in their names (`harness.metrics.SqlMetrics`). They are read in one read-only
`REPEATABLE READ` snapshot, whose cutoff is recorded. The command, timer and dispatch figures describe a cohort: the instances
whose START was recorded in the window, followed to their rows as the snapshot found them, with the rows never created counted
as missing. Completed per second is another population: the instances completed during the window.

- **Recorded to segment transaction start** is `settled_at - recorded_at` of start and signal commands that completed at the
  first attempt, with no retry, deferral or parking. A command with a `SIGNAL_PARKED` history entry is excluded, because
  parking refunds the attempt counter. It is an upper bound on recorded-to-claimed.
- **Timer transaction-start lateness** is `settled_at - available_at` of timer commands; retried and deferred timers are
  reported apart.
- **Start to outbox claim transaction start** is a correlated proxy, not the moment the relay picked the row up. An outbox
  row that a relay reclaims after a stale lease keeps its attempt count and has its claim time replaced, so only a claim within
  the relay's lease of the start is shown to be a first claim; the others are counted as unresolved.
- **Dispatch** is the raw `published_at - claimed_at`, handler delay included; less the delay it is a residual proxy. The peak
  number of dispatches one relay held at once is reported beside the relay's batch size.
- **Completed** counts instances at their `completed_at`, the start of the final segment's transaction.
- The latency series are given by the second a wait **ended** as well as by the second the command was **recorded**, because
  under a backlog the wait happens in between; a stall's evidence covers the whole waiting interval.

A latency bound that passes supports the gate. A bound above the limit leaves the gate inconclusive, because it cannot show
that the claim itself was late. A gate is also inconclusive whenever its evidence is missing: no finite throughput to divide
by, too few depth readings, a lock watch that took too few good samples.

The driver's account is reconciled with the snapshot: every start key is placed in one class, so an acknowledged start that is
not durable (a lost write) is told from a start whose answer never arrived and that is durable (a lost response).

### Cold start

A run's database is created empty, so the table statistics start empty and the first minutes of a run are a cold start. The
policy `DROP_CREATE` with default autovacuum and no analyze is a **cold-start experiment**, not a model of a database that has
run for a while. The collector records `pg_stat_user_tables` every tick; each result says when the first automatic analyze of
each hot table came and how often it ran, so a result is read together with the state of the statistics it ran on.

### Gates and aggregation

Each gate is `pass`, `fail` or `inconclusive`; sets marked diagnostic carry no verdict. A point (one arm, node count and load)
needs at least two replicates with a valid run before a gate is judged. A point passes when all its counted runs pass, fails
when all fail, and is inconclusive otherwise. Figures of a point combine by median. The scaling gate compares node counts at one
offered rate taken from the matrix and holds at that rate only; it is not a capacity claim.

### Reading a run after the fact

`scripts/diagnostics/capacity.sh <run-dir> <out-dir>` reads a finished run and the database it left, with `SELECT` and plain
`EXPLAIN (GENERIC_PLAN)` only: per-second settlements and recordings by kind against the due depth, transactions and
container CPU; table statistics; and, for a run that recorded statements, the calls, mean and total time of each statement.
`scripts/diagnostics/capacity-300.json` is the specification of the diagnostic run it was written for.

### Limits of what it can see

- It sees the database from outside. Pooled business work of a node is attributed by the node's `application_name`; the pool,
  the workflow listener and the relay listener share it, so listeners are told apart by what they ran.
- The lock watch samples every few tens of milliseconds; a shorter wait can be missed, so "no waiter seen" counts samples.
- The host sampler uses macOS tools (`top`, `ps`, `sysctl`) and sees the busiest processes only; CPU that no listed process
  accounts for is recorded as unattributed. The VM sampler runs a small loop in the database container.
- `-XX:ActiveProcessorCount` sizes the JVM's own pools; it grants no CPU. Nodes, driver, collector and the database VM share the
  host's cores, and a run at many nodes can be limited by that.
- The overload gate can only judge a run whose load pushes the due depth to the high mark; the harness does not size the
  over-capacity phase for that. At a low capacity and the default marks, reaching 10,000 due commands takes a long phase, and
  a run that does not reach it is reported `inconclusive`.
- Parking is told from the history entries `SIGNAL_PARKED` and `COMMAND_DEAD_LETTERED`; a command parked without such an
  entry cannot be told from a first attempt. A relay reclaim is bounded by the relay's lease, not observed.
- Statement time from `pg_stat_statements` is time inside the server; it does not include planning, the wait for a
  connection or the network, and it explains only part of the database's CPU.
- Observers cost load; a baseline run binds none, and `GET /effective` reports how many are bound.
- The numbers belong to one setup on one machine. They compare configurations against each other and are not a benchmark.

## Tests

- `*Test` classes need no database: configuration strictness, arms, the refusal split, the handler's timer, transaction,
  commit and dedup behaviour, the ingress answers, startup validation, and the shipped configuration against the typed product
  classes.
- `NodeProcessesIT` starts two nodes as separate JVM processes through the launcher against a database that a separate admin
  process migrated. It checks that instances complete with commands claimed by both nodes, that each instance records three
  commands and one dispatch, that the connections carry the node names, and that the history tables are separate.
- `IntakeRefusalIT` forces the intake limit to refuse and checks that the callback is still recorded, that no delivery attempt is
  spent, and that a redelivery records no second signal.
- `ArmTriggersIT` runs the admin entry point against a database: migrations, each arm, and the guard.
- The harness has unit tests for the specification, the arrival account, the validity rule, the gates, the stall and
  staircase logic, the evidence around a stall, the listener record and the report, and integration tests that run the SQL
  metrics on the baseline schema (`SqlMetricsIT`), the lock watch with sessions of two nodes (`LockWatchIT`), the listener
  probe with a killed backend (`ListenerProbeIT`) and the driver as its own process against stub ingresses
  (`DriverProcessIT`: nodes that stall, a paused driver, a refusal and its replay).
- `scripts/smoke.sh` runs real nodes through the runner: a healthy run, an injected database stall, a paused driver, a paused
  node, refusals with their replay, killed listener backends and a rejected specification. It takes about ten minutes and
  needs Docker, so it is a script and not a test.

```bash
./mvnw -ntp -pl examples/vertique-example-workflow-load -am verify
```
