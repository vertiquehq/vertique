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

```bash
./mvnw -ntp -pl examples/vertique-example-workflow-load -am verify
```
