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

A document describes the application's inputs: `openapi` (`3.1.1`), the `info` object from
configuration or from `@OpenAPIDefinition(info)`, the JSON Schema dialect, one server, every
operation of the mount with its parameters and request body, the component schemas those inputs
reference, and the pattern dialect (see [Document Content](#document-content)). It lists no
responses, security schemes, summaries, or tags, although the module receives the response shape at
startup and compares it across server instances (see
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

- **JSON:** compact UTF-8 with the root members in a fixed order (see
  [Document Content](#document-content)); `info` holds `title`, `description` when configured, and
  `version`.
- **YAML:** written from the same tree with Jackson's default YAML settings, so the parsed YAML tree
  equals the JSON tree.
- **Identical inputs produce identical bytes** and identical entity tags.

For the example above, with no `serverUrl` configured, no request-validation gate installed, and a
`CatalogResource` at `@Path("/items")` declaring `listItems(@QueryParam("limit") Integer limit)` on
`GET`, `getItem(@PathParam("id") String id)` on `GET /{id}`, and
`createItem(@QueryParam("dryRun") boolean dryRun, CreateItemRequest request)` on `POST` with
`@Consumes(APPLICATION_JSON)`, where `CreateItemRequest` is `record CreateItemRequest(String name,
int quantity)`, the JSON document is exactly (wrapped here for reading):

```json
{"openapi":"3.1.1","info":{"title":"Catalog","version":"1.0"},
"jsonSchemaDialect":"https://json-schema.org/draft/2020-12/schema",
"servers":[{"url":"/api/public"}],
"paths":{
"/items":{
"get":{"operationId":"listItems",
"parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}}]},
"post":{"operationId":"createItem",
"parameters":[{"name":"dryRun","in":"query","schema":{"type":"boolean"}}],
"requestBody":{"content":{"application/json":
{"schema":{"$ref":"#/components/schemas/createItem.request"}}}}}},
"/items/{id}":{
"get":{"operationId":"getItem",
"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}]}}},
"components":{"schemas":{
"createItem.request":{"$schema":"https://json-schema.org/draft/2020-12/schema",
"properties":{"name":{"type":"string"},"quantity":{"type":"integer"}},
"type":"object"}}},
"x-vertique-validation":{"patternDialect":"java.util.regex"}}
```

The served bytes have no line breaks. `limit` and `dryRun` carry no `required` member because
neither is certainly required, and the request body carries none because no validation gate is
installed.

### The docs mount

All documents are served by one router mount at `<apidocs.path>/*`. It runs in the
`SYSTEM_FIRST` phase (ordered among other `SYSTEM_FIRST` mounts by priority, then path), and carries
the fixed mount metadata
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
the module. In particular, no request body is checked for a redaction manifest, so a schema source
that binds none starts and routes unchanged. Only two configuration checks still run when
`apidocs.enabled` is not `false`: the checks of every `apidocs.documents` entry and the `@ApiDocs`
shape check of every active application (see [Configuration](#configuration)). With
`apidocs.enabled` `false`, nothing runs.

### Startup log lines

- One INFO line per stored document, naming the application, its mount, and the document's source,
  which is `generated` for every document in this release: `The document of application '<name>'
  at mount '<mount path>' is stored (source: generated)`.
- DEBUG lines from `dev.vertique.rest.openapi.docs` record each assembly (application, mount, elapsed
  milliseconds; no content) and each comparison between instances.

---

## Document Content

A document is built from the operations its application's JAX-RS mount publishes and from the
schemas the mount captured for their inputs. Nothing is read from a request.

### Root members

The root members are written in this order:

1. `openapi`: `3.1.1`.
2. `info`: see [`info`](#info).
3. `jsonSchemaDialect`: `https://json-schema.org/draft/2020-12/schema`.
4. `servers`: exactly one entry. Its `url` is the configured `apidocs.documents.<name>.serverUrl`
   when present; otherwise the mount path without its trailing `/*`, and `/` for the root mount. It
   is never taken from request headers such as `Host` or `X-Forwarded-*`.
5. `paths`: one entry per rendered path, keys in natural order.
6. `components`: `schemas` only, keys in natural order; left out when the document has no
   component schema.
7. `x-vertique-validation`: `{"patternDialect": "java.util.regex"}` in a public document (see
   [Patterns](#patterns) and [Validation disclosure](#validation-disclosure)).

### Paths and operations

- **Path keys.** Each key is the operation's JAX-RS path template relative to the mount, with every
  variable written as `{name}`: `{id: [0-9]+}` becomes `{id}`. The regular expression of a variable
  never reaches the document.
- **Equivalent paths.** Two templates whose rendered keys have the same literal text with variables
  at the same positions are equivalent. They share one path item only when they use the same
  variable names and differ in method; otherwise startup fails (see
  [Startup failures](#startup-failures)).
- **Methods.** Within a path item each operation is keyed by its lowercase HTTP method, in the order
  `get`, `put`, `post`, `delete`, `options`, `head`, `patch`, `trace`.
- **Operation Object.** `operationId` (the runtime operation id), then `parameters` (left out when
  empty), then `requestBody` (left out when the operation has none).

### Parameters

Every visible input that is neither the request body nor a form input becomes a Parameter Object.
Method parameters come first, then the fields of composite beans, each group in binding order. A
hidden input becomes nothing (see [Hidden inputs](#hidden-inputs)).

- **`name` and `in`.** The bound name and the lowercase location (`path`, `query`, `header`,
  `cookie`).
- **`description`.** The `description` of the input's first `@Parameter` annotation, left out when
  blank or absent.
- **`required`.** Written as `true` only when the input is certainly required, such as a path
  parameter. It is never written as `false`: an input whose requiredness is not certain, such as a
  primitive query parameter without `@DefaultValue`, carries no `required` member.
- **`schema`.** The schema captured for the input, published unchanged. It is published inline,
  unless it holds a `$ref` or `$defs` at a schema position; then it becomes a component and the
  parameter references it (see [Components](#components)). A captured parameter schema may not hold
  the `propertyNames` keyword (see [Refused constructs](#refused-constructs)).

### Unenforced inputs

An input with no captured schema, and every composite-bean field, is unenforced. Its schema is
`{"default": "<raw @DefaultValue text>"}` when it declares a `@DefaultValue`, and the empty schema
`{}` otherwise. Nothing is derived from its Java type or its constraint annotations.

### Hidden inputs

An input is hidden when the operation inventory of `dev.vertique:vertique-rest-jaxrs` flags it
hidden. The document reads only that flag and never re-derives hiding from annotations. The
inventory flags an input hidden when:

- the input itself carries `@Parameter(hidden = true)` or `@Schema(hidden = true)`, or, for a
  composite-bean field or component, `@Hidden`;
- a hidden method-level entry names it: a `@Parameter(hidden = true)` on the method, an entry of
  `@Parameters`, or an entry of `@Operation(parameters = ...)`, matched by exact name and by
  location (an entry without a location matches every location);
- it is a field of a `@BeanParam` or `@RequestParams` parameter that carries
  `@Parameter(hidden = true)` or `@Schema(hidden = true)`, or whose type carries `@Hidden`: hiding
  the composite hides all its fields.

A hidden input appears nowhere in the document: in no parameter list, form body, request body,
component, or example.

- **Hidden body.** The operation publishes no `requestBody`.
- **Hidden form fields.** Hidden form fields, named file parts included, leave the form body and do
  not choose its default media type. A form whose fields are all hidden publishes no request body.
- **Removed before every other check.** Apart from the path-parameter check below, hidden inputs
  are left out before any check of the document runs, so a hidden input is never verified,
  redacted, or checked, and never collides with a visible one of the same name and location.
- **Binding is unchanged.** The request still binds and validates every hidden input exactly as
  before; hiding changes the document only.
- **Path parameters cannot be hidden.** A document must describe every variable of a path template,
  so an operation that hides a path parameter fails startup:
  `<subject>: operation '<id>' hides its path parameter '<name>'; a document must describe every
  path variable, so a path parameter cannot be hidden`, where `<subject>` is
  `Application '<name>' (declared by <binary name>) at mount '<mount path>'`.

### Request bodies

- **Body input.** The operation's visible body input becomes `requestBody`, with one media type per
  type in `@Consumes`, or `application/json` when the operation declares none. Each media type
  references the one component `<operationId>.request`, which holds the captured body schema after
  [reserved-name redaction](#reserved-name-redaction). When no body schema was captured, each media
  type holds the empty schema `{}`.
- **`required`.** The request body carries `required: true` exactly when a request-validation gate is
  installed for the operation and the captured body schema, evaluated as the gate evaluates it,
  rejects an absent body. Otherwise it carries no `required` member.
- **Form inputs.** When the operation has no body input, its visible form inputs and named file
  parts become the request body instead: an object schema with one property per input, in binding
  order. The media types are those in `@Consumes`; when the operation declares none,
  `multipart/form-data` for an operation with a visible named file part and
  `application/x-www-form-urlencoded` otherwise. A named file
  part's property is `{}`; any other property is its captured schema (inline or a component, as for a
  parameter) or the unenforced schema above. A form request body never carries `required`.
- **Body and form inputs together.** When an operation binds a body, its form inputs are not
  published.

### Components

Component keys are built from the runtime operation id:

| Input | Component key |
|---|---|
| Request body | `<operationId>.request` |
| Parameter | `<operationId>.<location>.<name>`, location in lowercase |
| Form field | `<operationId>.form.<name>` |
| Relocated definition | `<component>.<def>` |

Every character outside `[A-Za-z0-9._-]` in a key is replaced by `_`. Two components with the same
key fail startup.

### Reserved-name redaction

The request-body schema the framework's input generator builds, which is the schema the
request-validation gate validates, can refuse names the body type binds but does not publish, such
as a `@JsonIgnore` or `@Schema(hidden = true)` member beside extra keys a `@JsonAnySetter` accepts.
It does so with an assertion under `propertyNames` that spells each name out: an `enum` of the names
for a case-sensitively bound type, or a pattern of their ASCII case folds for a case-insensitively
bound one. The generator records the location of every such assertion, copies included, in a
redaction manifest bound to the schema's content. `dev.vertique:vertique-json-schema` describes when
the generator emits these assertions.

The document publishes the body schema minus exactly those recorded assertions, so the names never
appear in it while the gate still refuses them:

- **Exactly the recorded locations.** Each recorded assertion is removed from the document's own
  copy, before [relocation](#relocation-of-local-definitions). Nothing is matched by shape and
  nothing is simplified: an `allOf` left with one element stays an `allOf`. A body that reserves no
  name is published unchanged.
- **What stays.** The refusal of non-ASCII keys of a case-insensitively bound type, and a
  `propertyNames` that a JSON mapper profile's own schema override declares, are not recorded and
  stay in the document.
- **The gate is unchanged.** The captured schema the gate validates with is never changed, so every
  request is accepted or refused exactly as before.

**Fail-closed.** A captured body schema is published only when it carries the framework's redaction
manifest and its content matches that manifest. Only the framework's input generator binds one, so
a custom `OperationSchemaSource`, or one that decorates the framework's source, fails startup when
it returns a body schema it built, replaced, or edited, or one without the manifest:

```text
<subject>: the request body of operation '<id>' carries no redaction manifest matching its content
(schema source <binary name>); only the framework's schema generator binds one, so the source must
return the generated body schema and its manifest unchanged
```

`<binary name>` is the runtime class of the bound source. A manifest whose recorded locations do not
all resolve in the body schema fails startup too, before anything is removed:

```text
<subject>: the request body of operation '<id>' carries a redaction manifest that does not resolve
in its schema (schema source <binary name>)
```

Each message is one line; it is wrapped here for reading. Neither message echoes a location, a name,
or schema text. A hidden body is never verified. These checks run only for an application with an
enabled document: without one, a source that binds no manifest starts and routes unchanged (see
[Nothing is built without an enabled document](#nothing-is-built-without-an-enabled-document)).

### Relocation of local definitions

A schema published as a component is relocated on a copy the document owns, after
[reserved-name redaction](#reserved-name-redaction) for a request body; the captured schemas the
request-validation gate uses are never changed.

- The root `$defs` is removed, and each entry `<def>` becomes component `<component>.<def>`.
- Every fragment-only `$ref` at a schema position, in the component and in each relocated
  definition, is rewritten:
  - `#` becomes `#/components/schemas/<component>`;
  - `#/$defs/<def>` and `#/$defs/<def>/<rest>` become `#/components/schemas/<component>.<def>`
    and that followed by `/<rest>`;
  - any other `#/<pointer>` becomes `#/components/schemas/<component>/<pointer>`.
- Nothing else changes: no `$id` is added, and a root `$schema`, boolean subschemas, pattern text,
  and member order are kept.

### Refused constructs

A captured schema that the document cannot publish fails startup. It is refused when, at a schema
position, it holds:

- `$id` anywhere, the root included;
- `$anchor`, `$dynamicAnchor`, or `$dynamicRef`;
- `$defs` below the root;
- a `$ref` that is not fragment-only (does not start with `#`);
- a fragment-only `$ref` that does not resolve to a schema position inside the captured schema,
  such as an anchor-name fragment, a pointer to a missing member, or a pointer to the `$defs` object
  itself.

The failure names the application, its declaring interface, the mount, the operation, the input,
and the JSON Pointer of the offending keyword. It never echoes a value, a reference, or schema text,
and names a `patternProperties` member by its ordinal (`[key-N]`) rather than its pattern.
Identifiers, anchors, and nested definitions are reported before references.

**`propertyNames` in a parameter.** Only a request body carries a redaction manifest, so a captured
parameter or form-field schema that holds the `propertyNames` keyword at a schema position fails
startup:

```text
<subject>: the <location> parameter '<name>' of operation '<id>' has a schema holding the keyword
'propertyNames'; parameter schemas carry no redaction manifest, so a published one may not hold it
```

A form field is named `the form field '<name>' of operation '<id>'` instead. The text
`propertyNames` as data (inside `const`, `enum`, `default`, `examples`, or `example`) or as a
property name (a member of `properties`, `patternProperties`, `$defs`, or `dependentSchemas`) is
not the keyword.

**Order.** The whole document is checked before anything is published: paths first, then each
operation in the order the document lists it. Within an operation, a hidden path parameter is
checked first; then hidden inputs are left out, and the visible inputs are checked: duplicate
inputs; the request body (its manifest, its refused constructs, its
[hidden members](#hidden-members-of-a-request-body), and its redaction); the parameter schemas
(`propertyNames`, then refused constructs); and the form-field schemas, likewise. The first
violation fails startup. Component key collisions are found as components are published.

### Hidden members of a request body

A hidden *input* is left out (see [Hidden inputs](#hidden-inputs)). A hidden *member* inside the
type of a published request body is different: the input generator of the operation's JSON mapper
profile describes the body type, and when that description still holds a member or type carrying
`@Hidden` or `@Schema(hidden = true)` that the generator did not leave out, startup fails. The
check runs for every published request body, whatever the schema source and whether or not a body
schema was captured:

```text
<subject>: the request body of operation '<id>' describes <what>, which carries <marker>; <fix>
```

- `<what>` is `member '<member>' of <declaring type>`, or `type <declaring type>` for a type.
- `<marker>` is `@Hidden`, `@Schema(hidden = true)`, or `both @Hidden and @Schema(hidden = true)`.
- `<fix>` depends on where the marker is:

| Position of the marker | Fix |
|---|---|
| `@Hidden` on the property's own field or getter | The input generator ignores `@Hidden`; add `@Schema(hidden = true)` on the property's own field or getter |
| `@Schema(hidden = true)` declared through an annotation bundle, a mix-in, or a creator parameter | Declare it directly on the property's own field or getter |
| A type carrying a marker | The generator does not hide a type; declare `@Schema(hidden = true)` on the field or getter of each member that references it, or hide the operation |
| A member the generator cannot leave out: an enum constant, `@JsonUnwrapped` content, a property bound through a setter, a builder, or a static factory's creator parameter, or its value constraints, and also a case-insensitively bound nested bean described inline or a converter-bound property | Remove it from the published type, or hide the operation |

The messages state these fixes as:

- `the input generator ignores @Hidden; declare @Schema(hidden = true) on the property's own field
  or getter`;
- `the input generator ignores @Schema(hidden = true) where it is declared; declare it directly on
  the property's own field or getter, not through a bundle or mix-in`;
- `the input generator does not hide a type; declare @Schema(hidden = true) on the field or getter
  of each member that references it, or hide the operation`;
- `the input generator cannot leave this member out where the document describes it (an enum
  constant, @JsonUnwrapped content, or a property bound through a setter, a builder, a static
  factory's creator parameter, or its value constraints); remove it from the published type, or hide
  the operation`. This message is used for every position in the table's last row, including the
  two the parenthetical does not list.

The first reported entry fails startup. When the body type cannot be inspected, startup fails with
`<subject>: the request body of operation '<id>' could not be inspected for hidden members`, without
the cause. The generator's output, the request-validation gate, and the binding are unchanged: the
member still binds and keeps its constraints.

### Patterns

Patterns are published verbatim, as written in the captured schema. They are Java regular
expressions, which the root member `x-vertique-validation.patternDialect: "java.util.regex"` records;
a consumer using another regular-expression dialect may read a pattern differently.

### Validation disclosure

The root `x-vertique-validation` depends on the document's access, which comes only from
`@ApiDocs(access)`; no configuration changes it.

- **Public document.** The root member is exactly `{"patternDialect":"java.util.regex"}`, and no
  input carries a marker.
- **Protected document.** After `patternDialect`, the root member adds, in this order:

| Member | Value |
|---|---|
| `strategy` | The id of the mount's request-validation strategy |
| `inputSchemaSource` | `generated` when the bound `OperationSchemaSource` is exactly the framework's source, runtime class `dev.vertique.rest.validation.AnnotationSchemaSource`; `custom` for any other source, a subclass or a wrapper of the framework's source included; `absent` when none is bound |
| `enforcement` | `active` for `web-validation`, `disabled` for `none`, `unknown` for any other strategy |
| `reservedNamesRefused` | `true` when a reserved name was removed from a published request body; otherwise absent |
| `hiddenInputs` | `true` when an input of a published operation was left out as hidden; otherwise absent |

Under `web-validation` only, a protected document also marks each Parameter Object and request
body that the gate does not validate with a schema with `"x-vertique-validation":
{"enforcedSchema": false}` as its last member. A form request body is marked when any of its fields
is not validated with a schema or is a named file part, since a file part publishes no schema.
Under any other strategy no input is marked; the root `enforcement` already qualifies every input.

Protected documents are refused at startup in this release (see
[Access](#access-public-is-served-protected-is-refused)), so no served document carries these
protected-only members yet.

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
| `apidocs.documents.<name>.enabled` | boolean | absent | `false` disables the document of application `<name>`. Absent or `null` keeps the decision of `@ApiDocs`. `true` is accepted only for an application whose declaring interface carries `@ApiDocs`. A string that is blank or made only of control characters fails startup |
| `apidocs.documents.<name>.info.title` | string | none | Document title. Required non-blank when `info` is configured; otherwise `info` comes from `@OpenAPIDefinition` on the declaring interface |
| `apidocs.documents.<name>.info.version` | string | none | Document version. Required non-blank when `info` is configured; otherwise `info` comes from `@OpenAPIDefinition` on the declaring interface |
| `apidocs.documents.<name>.info.description` | string | absent | Optional description, written to `info` when present |
| `apidocs.documents.<name>.serverUrl` | string | absent | The document's `servers[0].url`, published exactly as configured, so it must never hold credentials or other secrets. When absent, the mount path without its trailing `/*` (`/` for the root mount) |

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
4. **Unreadable `enabled`.** A string that is blank or made only of control characters cannot be
   read as a boolean, so it fails instead of keeping the decision of `@ApiDocs`. The failure names the application, its declaring interface, and
   `apidocs.documents.<name>.enabled`.
5. **`enabled: true`.** `true` is accepted only for an application whose declaring interface itself
   carries `@ApiDocs`; otherwise the failure names the application, its declaring interface, and
   `apidocs.documents.<name>.enabled`.

`enabled` is a tri-state. Absent and an explicit JSON `null` keep the decision of `@ApiDocs`, `false`
disables the document, and `true` only confirms a document that `@ApiDocs` already enables. A string
that is blank or made only of control characters is refused. A configuration entry never enables a document without `@ApiDocs`.

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
without repeating the value. A valid `serverUrl` is published unchanged as the document's
`servers[0].url` (see [Root members](#root-members)), to every reader of the document. Never put
credentials, such as the user information of `https://user:secret@host/`, or any other secret in
it: the check does not refuse them, and the document publishes them as configured.

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
the prefix. The documentation mount runs in the `SYSTEM_FIRST` phase, so it answers the exact
document URLs before every JAX-RS mount, every mount that sorts after it, `AFTER_MOUNTS` router
customizers, and API-scoped middleware; their routes are never reached at those URLs. ROOT-scoped
middleware and `BEFORE_MOUNTS` router customizers (the default customizer phase) run on the main router
before any mount, so they see and can answer the document URLs, as can another `SYSTEM_FIRST` mount that
sorts ahead of it (lower priority, or the same priority and a lower-sorting path such as `/*`). None of
these routes is checked for collisions; only published JAX-RS operations are.

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
router is created, so it can appear even when another startup check (including another module's
composition validator in the same pass, or a later check) fails the deployment. A second
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

Every failure below stops startup. Each message names what is wrong and never echoes configuration
values, schema text, references, pattern text, redaction locations, or reserved names. Messages
about the document's content name operation ids, rendered paths, input names, component keys, the
class of the bound schema source, and the declaring type and member of a hidden member.

| Condition | Failure |
|---|---|
| `apidocs` is not a JSON object | `ConfigurationException` from the section navigation |
| `apidocs.enabled` is present and not a JSON boolean | `ConfigurationException` naming `apidocs.enabled` |
| An `apidocs.documents` key breaks the name grammar, or names no declared application | `ConfigurationException` naming `apidocs.documents.<name>` and the rule |
| An entry holds a key other than `enabled`, `info`, or `serverUrl` | `ConfigurationException` naming the application, its declaring interface, and each unsupported key's path |
| An entry sets `enabled` to a string that is blank or made only of control characters | `ConfigurationException` naming the application, its declaring interface, and `apidocs.documents.<name>.enabled`, ending `it must be true or false, or be left out to keep the @ApiDocs decision` |
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
| Two operations of a documented application render to equivalent paths with different variable names, or with the same method | `RestConfigurationException` starting `Application '<name>' (declared by <binary name>) at mount '<mount path>'`, naming both routes by method, operation id, and rendered path, and stating that routes at one path must use the same variable names and differ in method |
| An operation of a documented application hides a path parameter (see [Hidden inputs](#hidden-inputs)) | `RestConfigurationException` with the same start, naming the operation and the parameter, and stating that a path parameter cannot be hidden |
| An operation of a documented application binds two visible inputs with the same name and location | `RestConfigurationException` with the same start, naming the operation, the input name, and the location, and stating that a document describes one parameter per name and location |
| A published request body of a documented application carries no redaction manifest matching its content, such as one a custom or decorating `OperationSchemaSource` built, replaced, or edited (see [Reserved-name redaction](#reserved-name-redaction)) | `RestConfigurationException` with the same start, naming the operation and the class of the bound schema source |
| A published request body carries a redaction manifest that does not resolve in its schema | `RestConfigurationException` with the same start, naming the operation and the class of the bound schema source |
| A captured parameter or form-field schema of a documented application holds the `propertyNames` keyword | `RestConfigurationException` with the same start, naming the operation and the parameter's location and name, or the form field |
| A published request body's type is described with a member or type carrying `@Hidden` or `@Schema(hidden = true)` (see [Hidden members of a request body](#hidden-members-of-a-request-body)) | `RestConfigurationException` with the same start, naming the operation, the member or type, its marker, and the fix |
| A published request body's type cannot be inspected for hidden members | `RestConfigurationException` with the same start, naming the operation; the cause is not echoed |
| A captured schema of a visible input of a documented application holds a refused construct (see [Refused constructs](#refused-constructs)) | `RestConfigurationException` with the same start, naming the operation, the input, the construct, and the JSON Pointer of the offending keyword; no value, reference, or schema text |
| Two components of a document would have the same key | `RestConfigurationException` with the same start, naming the input of both components and the key; when either is a relocated definition, it says `one component` instead of the key, and states that component keys replace every character outside `[A-Za-z0-9._-]` with `_` |
| A later server instance publishes a different mount or operation than the stored document | `RestConfigurationException` naming the application, its declaring interface, its mount path, and the first differing operation id |
| A mount of a documented application is built outside a Vert.x context | `RestConfigurationException` naming the application |

Entries of applications without `@ApiDocs`, and entries that switch a document off, are still checked
for their name, their application, their keys, and an unreadable `enabled`. The `info` and `serverUrl` of a document are checked
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
- **Expecting responses or security schemes in the document.** A document describes operations and
  their inputs only.
- **Expecting a server URL from the request.** `servers[0].url` is the configured `serverUrl` or the
  mount path; behind a proxy that changes the path, configure `serverUrl`.
- **Putting credentials in `serverUrl`.** It is published exactly as configured, user information
  included; keep credentials and other secrets out of it.
- **Hiding a path parameter.** It fails startup; a document describes every path variable.
- **Expecting a hidden input to stop binding.** Hiding changes the document only; the request still
  binds and validates the input.
- **A schema source that replaces or edits the generated body schema.** With an enabled document it
  fails startup; return the generated body schema and its redaction manifest unchanged.
- **Hiding a body property with `@Hidden`.** The input generator ignores `@Hidden`, so startup
  fails; declare `@Schema(hidden = true)` on the property's own field or getter.
- **Expecting `required: false`.** An input that is not certainly required carries no `required`
  member at all.
- **Expecting schemas for composite-bean fields.** They are unenforced and publish `{}` or their
  `@DefaultValue` only.
- **Using `$id`, anchors, or nested `$defs` in an input schema.** A documented application refuses
  them at startup; keep local definitions in the root `$defs` and reference them by fragment.
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
`AuthEnforcementCapability`, the optional `OperationSchemaSource` that `RestModule` declares, and
the `JsonMapperProfileRegistry` from `JsonRuntimeModule` (in `dev.vertique:vertique-json`), which
`RestModule` includes.

---

## Dependencies

| Module | Why |
|---|---|
| `dev.vertique:vertique-rest-jaxrs` | The declared-application view, the operation publication seam the module consumes, and the `ApiDocsInstalled` marker |
| `dev.vertique:vertique-rest-core` | `RouterMount`, `MountMeta`, the extension phases, `JaxRsConfig` default headers, and `RestConfigurationException` |
| `dev.vertique:vertique-core` | `ConfigParser`, configuration path navigation, `ConfigurationException`, `@KeyedBy`, and `JsonMapperProfileRegistry` |
| `dev.vertique:vertique-json-schema` | The redaction manifest each published request body is verified against and redacted by, and whose digest is part of the comparison between instances; the input generator that reports hidden members of a request body |
| `io.vertx:vertx-json-schema` | Compile dependency; evaluates whether a captured request-body schema accepts an absent body, which decides the request body's `required` |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@OpenAPIDefinition`, read for `info` on the declaring interface, and `@Parameter`, read for a parameter's `description` |
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
- Mark a query parameter `@Parameter(hidden = true)`: expect its name in neither form of the
  document, while a request still binds and validates it.
- Mark a path parameter `@Parameter(hidden = true)`: expect startup to fail naming the operation and
  the parameter.
- Give a body type a `@JsonAnySetter` and a `@JsonIgnore` member, with the request-validation gate
  installed: expect the ignored name in neither form of the document, while the gate still refuses a
  body that holds it.
- Bind an `OperationSchemaSource` that edits the generated body schema: expect startup to fail
  naming the operation and the source class.
