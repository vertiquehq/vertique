---
title: Configuration
description: Configure a Vertique application from JSON sources, inject typed configuration at the Dagger boundary, and route secrets through a property source.
---

<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# Configuration

This page explains where a generated Vertique application's configuration comes from, how to
consume it as a typed record inside your own Dagger modules, the order in which configuration
sources combine, and how to route secrets into that tree without writing them into a file.

## Where configuration lives in a generated application

The REST application generated in [Quickstart](quickstart.md) ships one configuration file,
`src/main/resources/config/application.json`:

```json
{
  "http": { "port": 8080 },
  "management": { "port": 9090, "enabled": true }
}
```

Vertique reads every `*.json` and `*.properties` file from each configured directory — `config/`
by default, or the directories named by the `VERTX_CONFIG_LOCATIONS` environment variable (a
comma-separated list; later directories override earlier ones, and within one directory
`*.properties` overrides `*.json`). Nothing beyond this one file is required for the quickstart
application to start.

Each configured directory is resolved against the running process's own current working
directory, not against the compiled classpath: starting the generated application with
`mvn -ntp exec:java` from `rest-app/` (as in [Quickstart](quickstart.md)) looks for a `config/`
directory directly under `rest-app/`, which does not exist there by default — only
`src/main/resources/config/` and the compiled `target/classes/config/` do. Editing
`src/main/resources/config/application.json` therefore has no observable effect on that run, even
after `mvn compile`, until a real `config/` directory exists next to the process's working
directory (or `VERTX_CONFIG_LOCATIONS` names one that does).

## Typed configuration at the Dagger boundary

Your own Dagger modules read configuration by injecting the framework's `ConfigParser` and
parsing a section of the root `@VertxConfig` tree into a typed record — never by calling
`JsonObject.mapTo` or reading a raw `@Named` scalar at the Dagger boundary:

```java
@Provides
HelloConfig helloConfig(@VertxConfig JsonObject config, ConfigParser parser) {
    return parser.parse(JsonConfigPaths.navigateObject(config, "hello"), HelloConfig.class);
}
```

`ConfigParser` is provided by `ConfigParsingModule`, which every starter aggregate already
includes transitively through `CoreApplicationModule` — you never list it again yourself. The
parser uses an isolated, coercion-lenient object mapper independent of the mapper your REST layer
uses for request and response bodies, so a config-parsing decision never leaks into your API's
JSON behavior.

## Configuration source precedence

Vertique merges configuration from several sources into one tree, in this order from lowest to
highest precedence:

1. `*.json` files in each config directory
2. `*.properties` files in each config directory
3. Declared `config.stores` entries
4. Environment variables
5. System properties
6. The `--conf` overlay — highest precedence; always wins

A value declared in a higher-precedence source always overrides the same key supplied by a
lower one.

## Secret providers

Secrets are injected into the config tree through `${key}` placeholder references, resolved
against declared property sources under `config.propertySources`. Vertique publishes four:

| Provider | Artifact | Model |
|---|---|---|
| HashiCorp Vault (KV v2) | `vertique-config-vault` | Eager — every declared path is read once at bootstrap |
| AWS Secrets Manager | `vertique-config-aws-secrets` | Eager — every declared secret is fetched once at bootstrap |
| AWS SSM Parameter Store | `vertique-config-aws-ssm` | A `config.stores` entry, not a property source — merges a whole parameter subtree into the tree |
| Azure Key Vault | `vertique-config-azure-keyvault` | On-demand — only keys actually referenced by a placeholder are fetched |

Each property-source provider supports the same self-reference idiom for pulling a secret into
the tree without writing its value into any file: declare the placeholder under its own key, and
the recursive tree probe falls through to the declared source instead of looping.

```json
{
  "db": {
    "password": "${db.password}"
  },
  "config": {
    "propertySources": [
      {
        "name": "vault-app",
        "type": "vault",
        "address": "http://vault:8200",
        "paths": [{ "path": "secret/app", "prefix": "db." }]
      }
    ]
  }
}
```

## Learn more

- [`vertique-core` module reference](../../vertique-core/src/main/resources/META-INF/vertique/module.md)
  — the `ConfigParser` seam, the `@VertxConfig` qualifier, and `JsonConfigPaths`.
- [`vertique-config-core` module reference](../../vertique-config/vertique-config-core/src/main/resources/META-INF/vertique/module.md)
  — the full precedence chain, `config.stores`, and the placeholder resolution engine.
- [`vertique-config-vault` module reference](../../vertique-config/vertique-config-vault/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-aws-secrets` module reference](../../vertique-config/vertique-config-aws-secrets/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-aws-ssm` module reference](../../vertique-config/vertique-config-aws-ssm/src/main/resources/META-INF/vertique/module.md)
- [`vertique-config-azure-keyvault` module reference](../../vertique-config/vertique-config-azure-keyvault/src/main/resources/META-INF/vertique/module.md)
- [Application model](application-model.md)
- [Documentation overview](index.md)
