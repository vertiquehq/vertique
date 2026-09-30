<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# REST OpenAPI Docs Module

> **Status:** Alpha
> **Package:** `dev.vertique.rest.openapi.docs`
> **Artifact:** `vertique-rest-openapi-docs`
> **Depends on:** rest-jaxrs, rest-core, core, json-schema

Opt-in runtime OpenAPI 3.1 documents for named REST applications. An application interface that
carries `@ApiDocs` gets two read-only documents, `openapi.json` and `openapi.yaml`, served under a
server-level prefix (default `/apidocs`) at `<prefix>/<application name>/openapi.json` and
`<prefix>/<application name>/openapi.yaml`. Each document is assembled once per application at
startup, on a worker thread, and served from frozen bytes with strong entity tags, conditional
`304` answers, `HEAD`, and `Cache-Control`.

In this release the document content is the skeleton only: `openapi` (`3.1.1`), the `info` object
from configuration, and an empty `paths` object. The document does not list operations,
parameters, request bodies, responses, security schemes, or servers, although the module receives
all of them at startup and compares them across server instances (see
[Several server instances](#several-server-instances)).

The module serves documents only. It has no UI, no assets, and no other route, and `@ApiDocs` is not
API protection: it does not change who may call any operation of the application.

---

## When To Use It

Add `dev.vertique.rest.openapi.docs.OpenApiDocsModule` to the Dagger `@Component` of an application
that declares one or more `@RestApplication` interfaces (see `dev.vertique:vertique-rest-jaxrs`)
and wants a machine-readable OpenAPI document for some of them.

Three things switch a document on; all three are required:

1. `OpenApiDocsModule` is listed in the component.
2. The application's declaring interface carries `@ApiDocs(access = ApiDocs.Access.PUBLIC)`.
3. `apidocs.documents.<name>.info` carries a non-blank `title` and `version`, where `<name>` is the
   application's `name` (see [Configuration](#configuration)).

```java
@ApiDocs(access = ApiDocs.Access.PUBLIC)
@RestApplication(name = "public", path = "/api/public", resources = {CatalogResource.class})
public interface PublicApi {}
```

```json
{
  "apidocs": {
    "documents": {
      "public": { "info": { "title": "Catalog", "version": "1.0" } }
    }
  }
}
```

The document is then served at `/apidocs/public/openapi.json` and `/apidocs/public/openapi.yaml`.

The component also needs `dev.vertique.config.parser.ConfigParsingModule` from
`dev.vertique:vertique-config-core`, which every Vertique application already lists: the module
parses the `apidocs` section through the canonical `ConfigParser`.

No document exists for:

- the legacy default mount (the one served without any `@RestApplication`);
- a manually built `JaxRsRouterMount`;
- an application whose registration is inactive (for example, switched off by
  configuration);
- an application whose declaring interface has no `@ApiDocs`, even when `apidocs.documents.<name>`
  is configured for it.

---

## Core Concepts

### One document per documented application

A document is named by its application's `name`, not by its mount path. The set of documents is
decided once per component from the declared applications that `vertique-rest-jaxrs` reports as
active. A document is enabled exactly when all of these hold:

- `apidocs.enabled` is not `false`;
- the application is active;
- the application's declaring interface itself carries `@ApiDocs` (a superinterface's `@ApiDocs` is
  not consulted);
- `apidocs.documents.<name>.enabled` is absent, `null`, or `true`.

Nothing else enables a document: not the module's presence, not another annotation, and not a
configuration entry alone.

### Access: `PUBLIC` is served, `PROTECTED` is refused

`@ApiDocs.access` states who may read the document routes.

- `PUBLIC` documents are served to any caller.
- `PROTECTED` documents are not served. A component with an enabled `PROTECTED` document fails at
  startup while the docs mount is built, before any document route is registered and before any
  application mount exists, so a protected document is never exposed without an access check. The
  failure names the application, its declaring interface, and `@ApiDocs.access`. The
  `securityScheme` and `rolesAllowed` attributes of `@ApiDocs` are declared for protected documents
  and have no effect while protected documents are refused.

### Built once, off the event loop, frozen

When an application's mount is built at startup, the module receives the mount's publication (the
operations with their resolved inputs, schemas, and response shape), copies what it needs during
that call, and assembles the document on a worker thread. The document is stored as immutable JSON
and YAML bytes built from one tree, together with a strong entity tag for each form. Nothing is
assembled per request, no schema source is called per request, and no event loop waits for another.

- **JSON:** compact UTF-8 with fields in OpenAPI 3.1.1 specification order: `openapi`, `info`
  (`title`, `description` when configured, `version`), `paths`.
- **YAML:** written from the same tree with Jackson's default YAML settings, so the parsed YAML tree
  equals the JSON tree.
- **Identical inputs produce identical bytes** and identical entity tags.

The skeleton document for the example above is exactly:

```json
{"openapi":"3.1.1","info":{"title":"Catalog","version":"1.0"},"paths":{}}
```

### The docs mount

All documents are served by one router mount at `<apidocs.path>/*`. It runs in the
`SYSTEM_FIRST` phase, before every other mount, and carries the fixed mount metadata
`new MountMeta("apidocs", "<path>/*", null, Set.of())`, so a `MountCustomizer` sees it like any other
mount and applies to it unless its `matches` method filters it out. The module adds no filtering.

### What the mount answers, and what it passes on

The mount answers `GET` and `HEAD` for exactly `<path>/<name>/openapi.json` and
`<path>/<name>/openapi.yaml` of each enabled document. The query string is ignored. For everything
else the mount calls `next()`, and the request continues to the later mounts unchanged:

- another HTTP method on a document URL;
- a trailing-slash variant of a document URL;
- an unknown document name, or the name of an application without an enabled document;
- any other URL under the prefix;
- a document whose bytes are not stored yet.

This mount never answers `404` or `405`. Whatever the later mounts answer, including a catch-all
mount or the framework's default not-found handling, is the response.

### Responses

| Situation | Response |
|---|---|
| `GET` of a stored document | `200`, `Content-Type: application/json` or `application/yaml`, `Content-Length`, `ETag`, `Cache-Control`, and the document bytes |
| `HEAD` of a stored document | `200` with the same headers as `GET` and no body |
| `GET` or `HEAD` with a matching `If-None-Match` | `304` with `ETag` and `Cache-Control` and no body |

- **Entity tag:** strong, a double quote, the lowercase hexadecimal SHA-256 of that form's bytes, and
  a double quote. The JSON and YAML forms have different tags.
- **`If-None-Match`:** each header value is split on commas and each member trimmed. A member matches
  when it is `*` or equals the tag after a leading `W/` is dropped (weak comparison). Any other value
  returns `200`.
- **Fresh buffers:** each response sends the frozen bytes in a new buffer, so no response can alter
  the stored document.

### Caching

The `Cache-Control` of a document never allows shared caching and is never weaker than the configured
default. It is computed once at startup from the effective default, which is the value of the last
`Cache-Control` header, in map order, among the headers that `jaxrs.defaultHeaders` resolves to (the
framework default is `no-store`; see `dev.vertique:vertique-rest-core`):

- `no-store` when that default has a `no-store` directive, otherwise `no-cache`;
- prefixed with `private, ` when that default has a `private` directive;
- so the value is one of `no-store`, `no-cache`, `private, no-store`, or `private, no-cache`; and
- when no default `Cache-Control` exists, `no-cache`.

Directive names are compared case-insensitively. The value is never `public` and carries no
`s-maxage`. The mount sets the header itself, replacing the default-headers middleware's value.

### Several server instances

A component may deploy several `HttpVerticle` instances. They share one document per application:

- The first instance to publish an application's mount assembles and stores the document. Every other
  instance waits without blocking its event loop, then renders a digest of its own publication,
  operation by operation, on a worker thread and compares it with the stored one.
- When the publications differ, the later instance's startup fails naming the application, its
  declaring interface, its mount path, and the first differing operation id in operation-id order
  (only the application, interface, and mount when just the mount-level facts differ). The message
  contains no digest, schema, or value.
- When assembly fails, every instance waiting on it fails with the same failure, the failed flight is
  discarded, and a later deployment in the same component assembles again.
- Each instance completes its own publication step on its own event loop.

All instances of one component must therefore publish the same operations and schemas for a
documented application. An operation source whose answers change between instances, such as a
stateful schema source, fails the later instance at startup.

### Nothing is built without an enabled document

With no enabled document, because `apidocs.enabled` is `false`, no application carries `@ApiDocs`,
every annotated application is switched off, or no application is registered, the module contributes
no publication sink and no docs mount. No publication is built, `apidocs` validation does not run,
and routes, validation, and schema-source calls are exactly as without the module.

### Startup log lines

- One INFO line per stored document, naming the application, its mount, and the document's source,
  which is `generated` for every document in this release: `The document of application '<name>'
  at mount '<mount path>' is stored (source: generated)`.
- DEBUG lines from `dev.vertique.rest.openapi.docs` record each assembly (application, mount, elapsed
  milliseconds; no content) and each comparison between instances.

---

## Key Classes

### ApiDocs

```java
@Retention(RUNTIME) @Target(TYPE) @Documented
public @interface ApiDocs {
    Access access();                    // required
    String securityScheme() default ""; // PROTECTED only
    String[] rolesAllowed() default {}; // PROTECTED only
    enum Access { PUBLIC, PROTECTED }
}
```

Place it on the `@RestApplication` declaring interface. It enables the document named by the
application and states who may read the document routes; it guards those routes only and is not API
protection. No configuration changes the annotation's `access`. It is honored only on the declaring
interface itself. `vertique-rest-jaxrs` and `vertique-codegen-jaxrs` recognize it by its fully
qualified name, `dev.vertique.rest.openapi.docs.ApiDocs` (also the constant `ApiDocs.ANNOTATION_NAME`),
and never depend on this module.

Only `PUBLIC` is served; see [Access](#access-public-is-served-protected-is-refused).

### OpenApiDocsModule

The Dagger module an application lists. It provides the parsed `apidocs` configuration, selects the
enabled documents, and contributes the publication sink and the docs mount when at least one document
is enabled (see [Module Dagger Bindings](#module-dagger-bindings)).

### ApidocsConfig, DocumentConfig, InfoConfig

The public records behind the `apidocs` configuration section (see [Configuration](#configuration)).
They are parsed by the canonical `ConfigParser`; applications normally only write the configuration,
not construct these records.

---

## Configuration

| Key | Type | Default | Meaning |
|---|---|---|---|
| `apidocs.enabled` | boolean | `true` | Global switch. `false` disables every document, and the rest of the `apidocs` subtree is then neither parsed nor validated. Must be a JSON boolean |
| `apidocs.path` | string | `/apidocs` | Prefix under which documents are served |
| `apidocs.documents.<name>.enabled` | boolean | absent | `false` disables the document of application `<name>`. Absent or `null` keeps the decision of `@ApiDocs`. `true` does not enable a document by itself |
| `apidocs.documents.<name>.info.title` | string | none | Document title. Required and non-blank for every enabled document |
| `apidocs.documents.<name>.info.version` | string | none | Document version. Required and non-blank for every enabled document |
| `apidocs.documents.<name>.info.description` | string | absent | Optional description, written to `info` when present |
| `apidocs.documents.<name>.serverUrl` | string | absent | Parsed and accepted; it does not change the document and is not checked in this release |

Each key under `apidocs.documents` is an application `name`. The document list is a keyed collection:
the key becomes the entry's `name`.

### `apidocs.path`

The prefix is validated only when at least one document is enabled. It must start with `/`, must not
be `/` alone, must not end with `/`, must contain none of `*`, `:`, `{`, `}`, `?`, `#`, whitespace, or
`//`, and must have no `.` or `..` segment. A violation fails startup with a message that names
`apidocs.path` and does not repeat the value.

### Entries that are ignored

An `apidocs.documents.<name>` entry for an application that has no `@ApiDocs` or names no declared
application does not enable or create anything, and its `info` is not validated.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

Every failure below stops startup. Each message names what is wrong and does not echo configuration
values or document content.

| Condition | Failure |
|---|---|
| `apidocs` is not a JSON object | `ConfigurationException` from the section navigation |
| `apidocs.enabled` is present and not a JSON boolean | `ConfigurationException` naming `apidocs.enabled` |
| A document is enabled and `apidocs.path` breaks a rule above | `ConfigurationException` naming `apidocs.path` |
| An enabled document has no `info`, or a blank `title` or `version` | `ConfigurationException` naming the application, its declaring interface's binary name, and `apidocs.documents.<name>.info`, `.info.title`, or `.info.version` |
| An enabled document has `@ApiDocs.access` `PROTECTED` | `RestConfigurationException` naming the application, its declaring interface, and `@ApiDocs.access`, stating that protected documents are not served yet; raised before any document route is registered |
| A later server instance publishes a different mount or operation than the stored document | `RestConfigurationException` naming the application, its declaring interface, its mount path, and the first differing operation id |
| A mount of a documented application is built outside a Vert.x context | `RestConfigurationException` naming the application |

Applications without `@ApiDocs`, and documents switched off with `enabled: false`, are never
validated.

### Common mistakes

- **Annotating without configuring `info`.** `@ApiDocs` alone does not start: every enabled document
  needs `apidocs.documents.<name>.info.title` and `.version`. Disable a document with
  `apidocs.documents.<name>.enabled: false` instead of removing the annotation when the configuration
  is not ready.
- **Keying a document entry by path instead of name.** Entries are keyed by the `@RestApplication`
  `name`. An entry that matches no documented application is ignored without a message.
- **Expecting `enabled: true` to enable a document.** Only `@ApiDocs` enables; the entry can only
  switch a documented application off.
- **Expecting operations in the document.** `paths` is empty in this release; consumers that read
  operations from the served document find none.
- **Expecting `serverUrl` in the document.** It is parsed and left out.
- **Putting `@ApiDocs` on a superinterface.** Only the `@RestApplication` declaring interface is
  read; the annotation processor rejects it on a superinterface.
- **Expecting `PROTECTED` to work.** It fails startup; use `PUBLIC` only where the document may be
  read by any caller.
- **Expecting a `404` or `405` from the docs mount.** The mount passes every request it does not
  answer to the later mounts; a trailing slash, `POST`, or unknown name is answered by them.
- **Publishing different content from different server instances.** Instances of one component are
  compared, and a difference fails the later instance's startup.
- **Omitting the module and expecting silence.** An application whose interface carries `@ApiDocs`
  but whose component does not list `OpenApiDocsModule` serves no document, and `vertique-rest-jaxrs`
  logs one INFO line per such application saying so.

---

## Module Dagger Bindings

`OpenApiDocsModule` provides:

| Binding | Notes |
|---|---|
| `ApidocsConfig` | The parsed `apidocs` section, through the canonical `ConfigParser`. When `apidocs.enabled` is `false`, the disabled defaults without parsing the rest of the subtree |
| The enabled documents | Package-private, `@Singleton`; decides which applications have a document and runs the validation above |
| `@ElementsIntoSet Set<OperationPublicationSink>` | The publication sink when at least one document is enabled, otherwise an empty set; contributes to the set `vertique-rest-jaxrs` declares |
| `@ElementsIntoSet Set<RouterMount>` | The docs mount when at least one document is enabled, otherwise an empty set; one mount per server instance |
| The document store | `@Singleton`, one per component; holds the documents keyed by application name and is shared by every `HttpVerticle` instance |
| `ApiDocsInstalled` | Bound whenever the module is listed, whatever the configuration, so `vertique-rest-jaxrs` does not log that no documentation route is published for `@ApiDocs` applications, even with `apidocs.enabled` `false` |

The module requires `@VertxConfig JsonObject`, `ConfigParser` (from `ConfigParsingModule`),
`JaxRsConfig` and `RestApplications` (both from `RestModule` in `dev.vertique:vertique-rest-jaxrs`).

---

## Dependencies

| Module | Why |
|---|---|
| `dev.vertique:vertique-rest-jaxrs` | The declared-application view, the operation publication seam the module consumes, and the `ApiDocsInstalled` marker |
| `dev.vertique:vertique-rest-core` | `RouterMount`, `MountMeta`, the extension phases, `JaxRsConfig` default headers, and `RestConfigurationException` |
| `dev.vertique:vertique-core` | `ConfigParser`, configuration path navigation, `ConfigurationException`, and `@KeyedBy` |
| `dev.vertique:vertique-json-schema` | The redaction manifest whose digest is part of the comparison between instances |
| Jackson (`jackson-databind`, `jackson-dataformat-yaml`) | Writes the JSON and YAML forms; YAML brings SnakeYAML |
| Dagger and `jakarta.inject-api` | Module bindings |

The module depends on neither `io.vertx:vertx-openapi` nor `io.vertx:vertx-web-openapi-router` and
adds no route beyond the document URLs. It is not part of any starter.

---

## Verification

- Start a component that lists `OpenApiDocsModule` with a `@ApiDocs(access = PUBLIC)` application and
  the `info` configuration, then `GET <prefix>/<name>/openapi.json` and `.yaml`: expect `200`, the
  exact content type, an `ETag`, and `Cache-Control`.
- Repeat with `If-None-Match` set to the returned `ETag`, to `W/` plus the tag, and to `*`: expect
  `304` with no body.
- `HEAD` the same URLs: expect the `200` headers with no body.
- Request `<prefix>/unknown/openapi.json`, a trailing-slash variant, and a `POST`: expect the
  response of the later mounts, never one from this mount.
- Set `apidocs.documents.<name>.enabled` to `false`: expect no document route and no publication.
