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
from configuration or from `@OpenAPIDefinition(info)`, and an empty `paths` object. The document does not list operations,
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
3. The document has an `info` with a non-blank `title` and `version`, from
   `apidocs.documents.<name>.info` or from `@OpenAPIDefinition(info)` on the declaring interface,
   where `<name>` is the application's `name` (see [Configuration](#configuration)).

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
- an application whose declaring interface has no `@ApiDocs`. An `apidocs.documents.<name>` entry for
  it is accepted and publishes nothing, unless it sets `enabled: true`, which fails startup (see
  [Configuration](#configuration)).

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
  failure names the application, its declaring interface, and `@ApiDocs.access`. Before that
  refusal, startup checks the `securityScheme` of each protected document (see
  [Startup Checks](#startup-checks)). `rolesAllowed` has no effect on serving yet, because protected documents are
  refused. The shape check still refuses a blank role and any role on a `PUBLIC` document (see
  [`@ApiDocs` shape](#apidocs-shape)).

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
mount and applies to it unless its `matches` method filters it out. The module adds no filtering (see
[Mount Customizers](#mount-customizers)). A component builds one docs mount per composition, and the
mount refuses to create its router unless the module's composition validator has checked the
composition (see [Startup Checks](#startup-checks)).

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
no publication sink and no docs mount. No publication is built, no composition validator runs, no
startup warning is logged, and routes, validation, and schema-source calls are exactly as without
the module. Only two configuration checks still run when `apidocs.enabled` is not `false`: the checks
of every `apidocs.documents` entry and the `@ApiDocs` shape check of every active application (see
[Configuration](#configuration)). With `apidocs.enabled` `false`, nothing runs.

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
enabled documents, and contributes the publication sink, the composition validator, and the docs
mount when at least one document is enabled (see [Module Dagger Bindings](#module-dagger-bindings)).

### ApidocsConfig, DocumentConfig, InfoConfig

The public records behind the `apidocs` configuration section (see [Configuration](#configuration)).
They are parsed by the canonical `ConfigParser`; applications normally only write the configuration,
not construct these records.

---

## Configuration

| Key | Type | Default | Meaning |
|---|---|---|---|
| `apidocs.enabled` | boolean | `true` | Global switch. `false` disables every document, and the rest of the `apidocs` subtree is then neither parsed nor checked. Must be a JSON boolean |
| `apidocs.path` | string | `/apidocs` | Prefix under which documents are served |
| `apidocs.documents.<name>.enabled` | boolean | absent | `false` disables the document of application `<name>`. Absent or `null` keeps the decision of `@ApiDocs`. `true` is accepted only for an application whose declaring interface carries `@ApiDocs` |
| `apidocs.documents.<name>.info.title` | string | none | Document title. Required non-blank when `info` is configured; otherwise `info` comes from `@OpenAPIDefinition` on the declaring interface |
| `apidocs.documents.<name>.info.version` | string | none | Document version. Required non-blank when `info` is configured; otherwise `info` comes from `@OpenAPIDefinition` on the declaring interface |
| `apidocs.documents.<name>.info.description` | string | absent | Optional description, written to `info` when present |
| `apidocs.documents.<name>.serverUrl` | string | absent | Checked for every enabled document; it does not change the document in this release |

Each key under `apidocs.documents` is an application `name`. The document list is a keyed collection:
the key becomes the entry's `name`.

### `apidocs.enabled`

`apidocs.enabled` is resolved first. When it is `false`, nothing else under `apidocs` is parsed or
checked, and no startup check of this module runs. A value that is not a JSON boolean fails startup
naming `apidocs.enabled`.

### `apidocs.path`

The prefix is checked only when at least one document is enabled. It must start with `/`, must not
be `/` alone, must not end with `/`, must contain none of `*`, `:`, `{`, `}`, `?`, `#`, whitespace, or
`//`, and must have no `.` or `..` segment. A violation fails startup with a message that names
`apidocs.path` and does not repeat the value.

### Document entries

Every key under `apidocs.documents` is checked, whatever the entry's `enabled` value, so an entry that
only disables a document is checked too and a mistyped name never leaves a document published
unnoticed. The checks run in this order, and the first violation fails startup:

1. **Name.** The key is the application's name and must match `[a-z0-9][a-z0-9_-]{0,63}`: lowercase
   letters, digits, `_` and `-`, starting with a letter or digit, at most 64 characters. No
   application can be declared under any other name.
2. **Declared application.** An application of that name must be declared, active or not. An entry
   that names no declared application is refused. An entry for an inactive application is accepted and
   publishes nothing.
3. **Supported keys.** The entry holds only `enabled`, `info`, and `serverUrl` (case-sensitive). Any
   other key fails, `access` and `mount` included: who may read a document is `@ApiDocs`'s, in code,
   and configuration cannot relocate a document. The failure lists each unsupported key's full path,
   sorted.
4. **`enabled: true`.** `true` is accepted only for an application whose declaring interface itself
   carries `@ApiDocs`; otherwise the failure names the application, its declaring interface, and
   `apidocs.documents.<name>.enabled`.

`enabled` is a tri-state. Absent and an explicit JSON `null` keep the decision of `@ApiDocs`, `false`
disables the document, and `true` only confirms a document that `@ApiDocs` already enables. A
configuration entry never enables a document without `@ApiDocs`.

Keys elsewhere under `apidocs` are tolerated.

### `@ApiDocs` shape

For every active application whose declaring interface carries `@ApiDocs`, whether or not
configuration disables its document, startup re-checks that `securityScheme` is set exactly when
`access` is `PROTECTED`, that `rolesAllowed` is empty when `access` is `PUBLIC`, and that no
`rolesAllowed` entry is blank. The annotation processor enforces the same rules at compile time, so
only a registration the processor did not produce can fail here. All violations are reported in one
failure, sorted, each naming the application, its declaring interface, and the attribute. An inactive
application is not checked.

### `info`

Each enabled document needs an `info` with a non-blank `title` and `version`.

- **From configuration.** `apidocs.documents.<name>.info`, when present, is used as a whole and replaces
  the annotation's `info`.
- **From `@OpenAPIDefinition`.** Otherwise the `title`, `version`, and (when non-blank) `description`
  of `@OpenAPIDefinition(info = ...)` on the declaring interface itself. A superinterface's
  `@OpenAPIDefinition` is never read.
- **Neither.** Startup fails naming `apidocs.documents.<name>.info`.

No default `info` is invented. Other members of `@OpenAPIDefinition` are not published in this release.

### `serverUrl`

`apidocs.documents.<name>.serverUrl`, when present on an enabled document, must be one of:

- an absolute `http` or `https` URI with a host (the scheme is compared case-insensitively); or
- an absolute path that starts with a single `/` and has no scheme or authority.

Anything else, a blank value included, fails startup naming `apidocs.documents.<name>.serverUrl`
without repeating the value. A valid `serverUrl` does not change the document in this release.

---

## Startup Checks

When at least one document is enabled, the module checks every composition before any router is
created, and again as each JAX-RS mount publishes. Every failure stops the `HttpVerticle` before it
listens. A failure reported by the composition validator reaches the application as the
`HttpVerticle`'s `IllegalStateException` (`Invalid mount configuration:`) listing every violation,
sorted.

**`@ApiDocs` values.** The docs mount checks each enabled `PROTECTED` document before it registers a
route:

- its `securityScheme` must be the scheme name of a registered `SecuritySchemeHandler`; and
- authentication enforcement must be installed.

Both violations name the application, its declaring interface, and the attribute, and are listed
together. Public documents are not checked. A protected document that passes is still refused, as
[Access](#access-public-is-served-protected-is-refused) describes.

**Application mount.** Each enabled document is matched to the JAX-RS mount whose application name
equals the document's name. The match never uses paths: a manually built mount at the application's
path does not match. A document with no such mount fails.

**Mounts at or under the prefix.** No JAX-RS mount may lie at or under `apidocs.path`. Move the mount
or choose another `apidocs.path`. Mounts that are not JAX-RS mounts, such as a UI mount, may lie under
the prefix. The documentation mount is mounted first and answers the exact document URLs itself, so a
route of a mount that is not a JAX-RS mount, a main-router customizer route, or an API middleware path
at one of those URLs is never reached. Such routes are not checked for collisions; only published
JAX-RS operations are.

**Pattern mount paths.** A JAX-RS mount whose path contains `:`, `{`, or `}` is refused while documents
are enabled when its literal part before the first such character is a prefix of `apidocs.path`, as
with `/:tenant/*` or `/{tenant}/*` under any prefix, or `/api/{v}/*` under `/api/docs`. Collision checks
cover literal mount paths only, so such a mount could reach the document URLs. A pattern mount whose
literal part does not prefix `apidocs.path`, such as `/api/:tenant/*` under `/apidocs`, is not refused.

**Route collisions.** The document URLs are `<apidocs.path>/<name>/openapi.json` and `.yaml`. A
published `GET` or `HEAD` route of a JAX-RS mount that can answer such a URL fails startup naming the
route, the operation, the mount, and the URL. The check matches the route as Vert.x routes it, regex
routes and path parameters included, and other methods never collide. Resolve a collision by choosing
an `apidocs.path` no such route can match, or by narrowing or removing the route.

**Catch-all routes.** A route that matches every path under its mount, such as `GET /{path: .*}` on a
mount at `/`, collides under every prefix. No `apidocs.path` avoids it, so the route must be narrowed or
removed.

**Routes added by hooks and customizers.** Routes that a `RouterLifecycleHook` or a `MountCustomizer`
adds to a JAX-RS router are not published, so they are neither published in a document nor checked for
collisions.

**Reserved operation ids.** For every enabled document, `apidocs:<name>:json` and `apidocs:<name>:yaml`
are reserved. A JAX-RS operation with one of these ids fails startup naming the operation and the
document's application.

**Shared global contract.** An application whose selected request-validation strategy resolves
operations from the mount's contract (`openapi-contract`) while that contract is the shared global
`jaxrs.openapiPath` fails startup if it has an enabled document. The shared contract file already is the
mount's OpenAPI document, so no document is generated for it. Serve that file behind an access check at
least as strict as the most restricted mount it describes. An application whose contract location is
its own, from its annotation or its configuration, is not refused by this check.

**Unvalidated composition.** The docs mount refuses to create its router when the composition validator
has not checked it, which happens when the `HttpVerticle` is built without composition validators, such
as with its public five-argument constructor or by a subclass. Obtain the `HttpVerticle` from Dagger.

The sink checks run for every JAX-RS mount of the composition, documented or not, in the order reserved
ids, route collisions, shared contract. The first check with a violation fails the mount, listing all of
that check's violations.

---

## Mount Customizers

Every `MountCustomizer` whose `matches` accepts the docs mount's fixed metadata is applied to the docs
router, as to any mount. Such a customizer therefore covers the document routes.

A customizer that matches every mount and adds a handler that ends every request, instead of passing it
on, also ends every request the docs mount does not answer. Requests under the prefix then stop falling
through to later mounts. Keep such handlers pass-through, or have `matches` exclude the mount id
`apidocs`.

---

## Warnings

The module logs one WARN on logger `dev.vertique.rest.openapi.docs.DocumentWarnings` per enabled
document, once per component. It is logged when the documentation module's composition checks pass, before any
router is created, so it can appear even when a later startup check fails the deployment. A second
composition or server instance of the same component does not repeat it.

The warning starts with `apidocs.documents.<name>` and the described mount's path, and lists the
mount-scoped controls that apply to the application's mount but not to the document routes, each as
`<kind> <class name>`:

- `MountCustomizer`: matches the application's mount but not the docs mount;
- `API middleware`: a middleware of `API` scope other than the framework's content-type middleware;
- `RouterLifecycleHook`: every hook;
- `RequestInterceptor`: every interceptor that overrides `beforeRequest`.

The last three apply only to a mount that has resources. Document requests bypass these controls,
while main-router middleware still applies. For a public document the warning also states that no
`OperationHandlerContributor` runs for the document routes. A document with no such control logs
nothing.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

Every failure below stops startup. Each message names what is wrong and does not echo configuration
values or document content.

| Condition | Failure |
|---|---|
| `apidocs` is not a JSON object | `ConfigurationException` from the section navigation |
| `apidocs.enabled` is present and not a JSON boolean | `ConfigurationException` naming `apidocs.enabled` |
| An `apidocs.documents` key breaks the name grammar, or names no declared application | `ConfigurationException` naming `apidocs.documents.<name>` and the rule |
| An entry holds a key other than `enabled`, `info`, or `serverUrl` | `ConfigurationException` naming the application, its declaring interface, and each unsupported key's path |
| An entry sets `enabled: true` for an application without `@ApiDocs` | `ConfigurationException` naming the application, its declaring interface, and `apidocs.documents.<name>.enabled` |
| A blank key, or an entry that is not a JSON object | The keyed-collection parser's own `ConfigurationException`, raised before any check above; it says the entry has a blank key, or that the entry `'<key>'` must be a nested JSON object, and names the key |
| The `@ApiDocs` of an active application breaks its shape rules | `RestConfigurationException` listing every violation, sorted, each naming the application, its declaring interface, and `@ApiDocs.securityScheme` or `@ApiDocs.rolesAllowed` |
| A document is enabled and `apidocs.path` breaks a rule above | `ConfigurationException` naming `apidocs.path` |
| An enabled document has no `info`, or a blank `title` or `version` | `ConfigurationException` naming the application, its declaring interface's binary name, and `apidocs.documents.<name>.info`, `.info.title`, or `.info.version`; a blank `title` or `version` in `@OpenAPIDefinition(info)` names `@OpenAPIDefinition.info` |
| An enabled document has an invalid `serverUrl` | `ConfigurationException` naming the application, its declaring interface, and `apidocs.documents.<name>.serverUrl` |
| An enabled `PROTECTED` document names a `securityScheme` no registered handler has, or authentication enforcement is not installed | `RestConfigurationException` naming the application, its declaring interface, and `@ApiDocs.securityScheme` or `@ApiDocs.access`; raised before any document route is registered |
| An enabled document has `@ApiDocs.access` `PROTECTED` and passes the check above | `RestConfigurationException` naming the application, its declaring interface, and `@ApiDocs.access`, stating that protected documents are not served yet; raised before any document route is registered |
| An enabled document has no JAX-RS mount of its application name, a JAX-RS mount lies at or under `apidocs.path`, or a JAX-RS pattern mount path can reach the prefix | `IllegalStateException` from `HttpVerticle` (`Invalid mount configuration:`) listing every violation, raised before any router is created |
| The docs mount is hosted by an `HttpVerticle` built without composition validators | `RestConfigurationException` naming the docs mount, the cause, and the remedy (obtain `HttpVerticle` from Dagger) |
| A JAX-RS operation uses a reserved id `apidocs:<name>:json` or `apidocs:<name>:yaml` | `RestConfigurationException` naming the operation id, its method and template, the mount, and the application |
| A `GET` or `HEAD` route of a JAX-RS mount can answer a document URL | `RestConfigurationException` with one line per route and URL, naming both |
| A documented application resolves operations from the shared global contract | `RestConfigurationException` naming the application, the mount, and the strategy |
| A later server instance publishes a different mount or operation than the stored document | `RestConfigurationException` naming the application, its declaring interface, its mount path, and the first differing operation id |
| A mount of a documented application is built outside a Vert.x context | `RestConfigurationException` naming the application |

Entries of applications without `@ApiDocs`, and entries that switch a document off, are still checked
for their name, their application, and their keys. The `info` and `serverUrl` of a document are checked
only when it is enabled. A value of the wrong type inside an entry is rejected by the canonical parser,
and its message can quote that value.

### Common mistakes

- **Annotating without configuring `info`.** `@ApiDocs` alone does not start: every enabled document
  needs `apidocs.documents.<name>.info.title` and `.version`. Disable a document with
  `apidocs.documents.<name>.enabled: false` instead of removing the annotation when the configuration
  is not ready.
- **Keying a document entry by path instead of name.** Entries are keyed by the `@RestApplication`
  `name`. An entry that names no declared application fails startup, even when it only sets
  `enabled: false`.
- **Expecting `enabled: true` to enable a document.** Only `@ApiDocs` enables. `enabled: true` for an
  application without `@ApiDocs` fails startup; the entry can only switch a documented application off.
- **Putting `access` or `mount` in an entry.** Entries accept only `enabled`, `info`, and `serverUrl`;
  access is declared by `@ApiDocs` in code.
- **Mounting a JAX-RS mount or a catch-all route under the prefix.** It fails startup. Choose another
  `apidocs.path`, or move, narrow, or remove the mount or route.
- **Expecting operations in the document.** `paths` is empty in this release; consumers that read
  operations from the served document find none.
- **Expecting `serverUrl` in the document.** It is checked and left out.
- **Expecting routes added by a hook or customizer to be checked.** They are neither published nor
  collision-checked.
- **A customizer that ends every request.** A match-all customizer whose handler does not pass the
  request on stops fall-through under the prefix.
- **Putting `@ApiDocs` on a superinterface.** Only the `@RestApplication` declaring interface is
  read; the annotation processor rejects it on a superinterface.
- **Expecting `PROTECTED` to work.** It fails startup, after checking the scheme and enforcement; use
  `PUBLIC` only where the document may be read by any caller.
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
| The enabled documents | Package-private, `@Singleton`; decides which applications have a document and runs the configuration checks above |
| `@ElementsIntoSet Set<MountCompositionValidator>` | The composition validator when at least one document is enabled, otherwise an empty set; contributes to the set `HttpVerticle` runs before it creates any router |
| `@ElementsIntoSet Set<OperationPublicationSink>` | The publication sink when at least one document is enabled, otherwise an empty set; contributes to the set `vertique-rest-jaxrs` declares |
| `@ElementsIntoSet Set<RouterMount>` | The docs mount when at least one document is enabled, otherwise an empty set; unscoped, so every composition builds its own mount |
| The documentation warnings | Package-private, `@Singleton`; holds the once-per-component guard of the startup warnings |
| The document store | `@Singleton`, one per component; holds the documents keyed by application name and is shared by every `HttpVerticle` instance |
| `ApiDocsInstalled` | Bound whenever the module is listed, whatever the configuration, so `vertique-rest-jaxrs` does not log that no documentation route is published for `@ApiDocs` applications, even with `apidocs.enabled` `false` |

The module requires `@VertxConfig JsonObject`, `ConfigParser` (from `ConfigParsingModule`),
`JaxRsConfig` and `RestApplications` (both from `RestModule` in `dev.vertique:vertique-rest-jaxrs`),
and the component's multibound sets of `RequestValidationStrategy`, `SecuritySchemeHandler`,
`MountCustomizer`, `Middleware`, `RouterLifecycleHook`, and `RequestInterceptor`, plus an optional
`AuthEnforcementCapability`.

---

## Dependencies

| Module | Why |
|---|---|
| `dev.vertique:vertique-rest-jaxrs` | The declared-application view, the operation publication seam the module consumes, and the `ApiDocsInstalled` marker |
| `dev.vertique:vertique-rest-core` | `RouterMount`, `MountMeta`, the extension phases, `JaxRsConfig` default headers, and `RestConfigurationException` |
| `dev.vertique:vertique-core` | `ConfigParser`, configuration path navigation, `ConfigurationException`, and `@KeyedBy` |
| `dev.vertique:vertique-json-schema` | The redaction manifest whose digest is part of the comparison between instances |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@OpenAPIDefinition`, read for `info` on the declaring interface |
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
- Key an entry by an undeclared name, add `access` to an entry, or set `serverUrl` to `ftp://host`:
  expect startup to fail naming the configuration path.
- Declare a `GET /{path: .*}` route on a mount at `/`: expect startup to fail naming the route and
  the document URL.
