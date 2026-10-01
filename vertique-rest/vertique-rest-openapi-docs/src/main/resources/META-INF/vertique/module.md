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

A document describes the application's inputs and responses: `openapi` (`3.1.1`), the `info` object
from configuration or from `@OpenAPIDefinition(info)`, the JSON Schema dialect, one server, every
visible operation of the mount with its parameters, request body, and responses, the component
schemas those inputs and responses reference, the tags its operations declare, and the pattern
dialect (see [Document Content](#document-content)). Responses are inferred from each resource
method's return type or taken from its `@ApiResponse` declarations (see
[Operation Responses](#operation-responses)). Operations, parameters, request bodies, and responses
carry the documentation of their Swagger annotations (see
[Swagger annotations](#swagger-annotations)). It lists no security schemes.

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
that call, and assembles the document on a worker thread. The composition that assembles a
document is the only one that generates its response schemas (see
[Output profile and generation](#output-profile-and-generation)). The document is stored as
immutable JSON and YAML bytes built from one tree, together with a strong entity tag for each form.
Nothing is assembled per request, no schema source or schema generator is called per request, and no
event loop waits for another.

- **JSON:** compact UTF-8 with the root members in a fixed order (see
  [Document Content](#document-content)); `info` holds the configured `title`, `description`, and
  `version`, or the members of the annotated `info` (see [`info`](#info)).
- **YAML:** written from the same tree with Jackson's default YAML settings, so the parsed YAML tree
  equals the JSON tree.
- **Identical inputs produce identical bytes** and identical entity tags.

For the example above, with no `serverUrl` configured, no request-validation gate installed, and a
`CatalogResource` at `@Path("/items")` declaring `String listItems(@QueryParam("limit") Integer
limit)` on `GET` and `String getItem(@PathParam("id") String id)` on `GET /{id}`, both with
`@Produces(TEXT_PLAIN)`, and `void createItem(@QueryParam("dryRun") boolean dryRun,
CreateItemRequest request)` on `POST` with `@Consumes(APPLICATION_JSON)`, where `CreateItemRequest`
is `record CreateItemRequest(String name, int quantity)`, the JSON document is exactly (wrapped here
for reading):

```json
{"openapi":"3.1.1","info":{"title":"Catalog","version":"1.0"},
"jsonSchemaDialect":"https://json-schema.org/draft/2020-12/schema",
"servers":[{"url":"/api/public"}],
"paths":{
"/items":{
"get":{"operationId":"listItems",
"parameters":[{"name":"limit","in":"query","schema":{"type":"integer"}}],
"responses":{"200":{"description":"OK","content":{"text/plain":{}}}}},
"post":{"operationId":"createItem",
"parameters":[{"name":"dryRun","in":"query","schema":{"type":"boolean"}}],
"requestBody":{"content":{"application/json":
{"schema":{"$ref":"#/components/schemas/createItem.request"}}}},
"responses":{"204":{"description":"No Content"}}}},
"/items/{id}":{
"get":{"operationId":"getItem",
"parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
"responses":{"200":{"description":"OK","content":{"text/plain":{}}}}}}},
"components":{"schemas":{
"createItem.request":{"$schema":"https://json-schema.org/draft/2020-12/schema",
"properties":{"name":{"type":"string"},"quantity":{"type":"integer"}},
"type":"object"}}},
"x-vertique-validation":{"patternDialect":"java.util.regex"}}
```

The served bytes have no line breaks. `limit` and `dryRun` carry no `required` member because
neither is certainly required, and the request body carries none because no validation gate is
installed. The two `String` operations publish raw text without a schema, and the `void` operation
publishes `204` (see [Operation Responses](#operation-responses)).

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
that binds none starts and routes unchanged, and no response is inferred, generated, or checked, so
a renamed or hidden output member or an output type the generator rejects does not affect startup.
The same holds for an application whose own document is disabled while another one is enabled.
Only two configuration checks still run when `apidocs.enabled` is not `false`: the checks of every `apidocs.documents` entry and the `@ApiDocs`
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

A document is built from the operations its application's JAX-RS mount publishes, from the
schemas the mount captured for their inputs, and from the output schemas the module generates for
their responses (see [Operation Responses](#operation-responses)). Nothing is read from a request.

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
7. `tags`: one Tag Object per tag a published operation declares with `@Tag`, sorted by name; left
   out when there is none (see [Tags](#tags)).
8. `x-vertique-validation`: `{"patternDialect": "java.util.regex"}` in a public document (see
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
- **Operation Object.** `tags`, `summary`, `description`, and `externalDocs`, each only when set
  (see [Swagger annotations](#swagger-annotations)); then `operationId` (the runtime operation id),
  `parameters` (left out when empty), `requestBody` (left out when the operation has none),
  `responses` (see [Operation Responses](#operation-responses)), and `deprecated` (only `true`).
- **Hidden operations.** A hidden operation is not listed (see
  [Hidden operations](#hidden-operations)).

### Parameters

Every visible input that is neither the request body nor a form input becomes a Parameter Object.
Method parameters come first, then the fields of composite beans, each group in binding order. A
hidden input becomes nothing (see [Hidden inputs](#hidden-inputs)).

The members are written in this order: `name`, `in`, `description`, `required`, `deprecated`,
`schema`, `example`, `examples`, each optional member only when set.

- **`name` and `in`.** The bound name and the lowercase location (`path`, `query`, `header`,
  `cookie`).
- **`description`.** The `description` of the input's first `@Parameter` annotation; when that is
  blank or absent, the `description` of that annotation's own `schema`. Left out when neither is set.
- **`required`.** Written as `true` only when the input is certainly required, such as a path
  parameter. It is never written as `false`: an input whose requiredness is not certain, such as a
  primitive query parameter without `@DefaultValue`, carries no `required` member.
  `@Parameter(required = true)` never adds it (see
  [Agreement with the runtime](#agreement-with-the-runtime)).
- **`deprecated`, `example`, `examples`.** From the input's first `@Parameter` (see
  [Swagger annotations](#swagger-annotations)).
- **`schema`.** The schema captured for the input, published unchanged. It is published inline,
  unless it holds a `$ref` or `$defs` at a schema position; then it becomes a component and the
  parameter references it (see [Components](#components)). A captured parameter schema may not hold
  the `propertyNames` keyword (see [Refused constructs](#refused-constructs)).

### Unenforced inputs

An input with no captured schema, and every composite-bean field, is unenforced. Its schema is
`{"default": "<raw @DefaultValue text>"}` when it declares a `@DefaultValue`, and the empty schema
`{}` otherwise. Nothing is derived from its Java type or its constraint annotations.

### Hidden operations

An operation is hidden when either holds:

- `@Hidden` (`io.swagger.v3.oas.annotations.Hidden`) is present on the resource method, on the same
  method of a superclass or of an interface the resource implements (a JAX-RS interface included),
  on the resource class, on a superclass, or on an implemented interface; or
- an `@Operation` with `hidden = true` is present on the resource method or on the same method of a
  superclass or implemented interface. Any of those `@Operation`s counts, not only the first one,
  which supplies the operation's other members.

A composed annotation also hides, one meta level deep:

- an annotation on the resource method whose own type carries `@Hidden` or `@Operation(hidden = true)`
  hides the operation; and
- an annotation on the resource class whose own type carries `@Hidden` hides the operation.

Nesting deeper than one level does not count: an annotation whose type is itself only annotated with
a further annotation carrying `@Hidden` does not hide an operation.

Hiding applies to the document this module serves only. A build-time specification generator may
still list an operation hidden through a composed `@Hidden` (swagger-core's reader, for example,
does not resolve it).

A hidden operation is removed from the document before its paths are rendered and before any of its
content is checked:

- **Nothing of it is published.** Its Operation Object, its path item when no visible operation is
  left there, every component keyed by its operation id, and every root tag that only it declared
  are absent.
- **Nothing of it is checked as content.** Its paths, inputs, schemas, redaction manifest, hidden
  members, annotations, tags, examples, return type, and declared responses are neither verified
  nor checked, no output schema is generated for it, and it causes no warning.
- **It changes no disclosure.** The `reservedNamesRefused` and `hiddenInputs` members of
  [Validation disclosure](#validation-disclosure) are computed over the visible operations only.
- **Its route still answers.** Hiding changes the document only: the route, its validation, and its
  security are unchanged.
- **Route-level checks still see it.** A hidden operation still fails startup when it uses a
  [reserved operation id](#startup-checks) or its route can answer a document URL, and it is part of
  the comparison between [server instances](#several-server-instances).

### Hidden inputs

An input is hidden when the operation inventory of `dev.vertique:vertique-rest-jaxrs` flags it
hidden. The document reads only that flag and never re-derives hiding from annotations. The
inventory flags an input hidden when:

- the input itself carries `@Parameter(hidden = true)` or `@Schema(hidden = true)`, or, for a
  composite-bean field or component, `@Hidden`;
- a hidden method-level entry names it: a `@Parameter(hidden = true)` on the method, an entry of
  `@Parameters`, or an entry of `@Operation(parameters = ...)`, matched by name (exactly, except
  that a header name matches ignoring ASCII letter case) and by location (an entry without a
  location matches every location);
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
  redacted, or checked, never collides with a visible one of the same name and location, and its
  Swagger annotations are neither checked nor warned about.
- **Binding is unchanged.** The request still binds and validates every hidden input exactly as
  before; hiding changes the document only.
- **Path parameters cannot be hidden.** A document must describe every variable of a path template,
  so an operation that hides a path parameter fails startup:
  `<subject>: operation '<id>' hides its path parameter '<name>'; a document must describe every
  path variable, so a path parameter cannot be hidden`, where `<subject>` is
  `Application '<name>' (declared by <binary name>) at mount '<mount path>'`.

### Request bodies

The Request Body Object holds `description` (when set), `content`, and `required` (when written), in
that order, and nothing else: no `example`, `examples`, or `deprecated`. Each Media Type Object holds
`schema`, then `examples` or `example` when the request body's `@RequestBody` supplies one (see
[Swagger annotations](#swagger-annotations)).

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
  parameter) or the unenforced schema above. A form request body never carries `required`, takes
  only its `description` from `@RequestBody`, and carries no examples.
- **Body and form inputs together.** When an operation binds a body, its form inputs are not
  published.

### Components

Component keys are built from the runtime operation id:

| Input or output | Component key |
|---|---|
| Request body | `<operationId>.request` |
| Parameter | `<operationId>.<location>.<name>`, location in lowercase |
| Form field | `<operationId>.form.<name>` |
| Inferred response body | `<operationId>.response` |
| Declared response content | `<operationId>.response.<status>`, or `<operationId>.response.<status>.<n>` (see [Response components](#response-components)) |
| Declared response header | `<operationId>.response.<status>.header.<name>` |
| Relocated definition | `<component>.<def>` |

Every character outside `[A-Za-z0-9._-]` in a key is replaced by `_`. Two components with the same
key fail startup. Response components are published after the operation's input components.

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

A captured input schema or a generated output schema that the document cannot publish fails
startup. It is refused when, at a schema position, it holds:

- `$id` anywhere, the root included;
- `$anchor`, `$dynamicAnchor`, or `$dynamicRef`;
- `$defs` below the root;
- a `$ref` that is not fragment-only (does not start with `#`);
- a fragment-only `$ref` that does not resolve to a schema position inside the captured schema,
  such as an anchor-name fragment, a pointer to a missing member, or a pointer to the `$defs` object
  itself.

The failure names the application, its declaring interface, the mount, the operation, the input
(for a response, `the output schema of status <status>`, or `the output schema of header '<name>'
of status <status>`), and the JSON Pointer of the offending keyword. It never echoes a value, a
reference, or schema text, and names a `patternProperties` member by its ordinal (`[key-N]`) rather than its pattern.
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

**Order.** The whole document is checked before anything is published. Hidden operations are
removed first; then the paths of the visible operations are checked, then each operation in the
order the document lists it. Within an operation, a hidden path parameter is checked first; then
hidden inputs are left out, and the visible inputs are checked: duplicate inputs; the request body
(its manifest, its refused constructs, its [hidden members](#hidden-members-of-a-request-body), and
its redaction); the parameter schemas (`propertyNames`, then refused constructs); the form-field
schemas, likewise; then its Swagger annotations (see
[Agreement with the runtime](#agreement-with-the-runtime)); and then its responses (see
[Order of the response checks](#order-of-the-response-checks)). Once every operation is checked, the
`@Tag` declarations of all operations are merged in the same order (see [Tags](#tags)). The first
violation fails startup. Component key collisions are found as components are published.

### Hidden members of a request body

A hidden *input* is left out (see [Hidden inputs](#hidden-inputs)). A hidden *member* inside the
type of a published request body is different: the input generator of the operation's JSON mapper
profile describes the body type, and when that description still holds a member or type carrying
`@Hidden` or `@Schema(hidden = true)` that the generator did not leave out, startup fails. The
check runs for every published request body, whether or not a body schema was captured. It inspects
the bound Java type of the request body. A custom or decorating schema source must return the
generated description of the bound type unchanged: `@Hidden` members of a different type it
describes are not refused (such a document reports `inputSchemaSource: custom` when protected). A
refusal reads:

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

### Swagger annotations

The document reads the Swagger annotations (`io.swagger.v3.oas.annotations`) of each visible
operation and of its visible inputs. Method annotations are resolved from the resource method, then
the same method of each superclass, then of each implemented interface; class annotations from the
resource class, then each superclass, then each implemented interface. Where a member is read from
"the first" annotation of a type, it is the first in that order. A blank string member counts as
unset.

Documentation never changes a published input name, location, schema, requiredness, or media type,
and never edits a captured schema. An annotation that contradicts how the runtime binds an
operation fails startup (see [Agreement with the runtime](#agreement-with-the-runtime)).

| Annotation member | Published as | Precedence and rules |
|---|---|---|
| `@Operation.summary`, `@Operation.description` | The Operation Object's `summary`, `description` | From the first `@Operation` |
| `@Operation.deprecated` | `deprecated: true` | From the first `@Operation`; only `true` is written |
| `@Operation.externalDocs` | `externalDocs` with `description` (when set) and `url` | From the first `@Operation`; written only when `url` is set |
| `@Operation.tags` | The operation's `tags` | Listed first, in declared order; see [Tags](#tags) |
| `@Tag` or `@Tags` on the method or the class | The operation's `tags` and the root `tags` | See [Tags](#tags) |
| `@Operation.hidden`, `@Hidden` | Nothing: the operation is removed | See [Hidden operations](#hidden-operations) |
| `@Parameter.description` | The Parameter Object's `description` | From the input's first `@Parameter`; when blank, filled from that annotation's `schema.description` |
| `@Parameter.deprecated` | `deprecated: true` | `true` when `@Parameter.deprecated` or its `schema.deprecated` is `true` |
| `@Parameter.example` | `example` | Not written when `examples` is; when unset, filled from `schema.example`; see [Examples](#examples) |
| `@Parameter.examples` | `examples` | Wins over `example`; see [Examples](#examples) |
| `@RequestBody.description` | The request body's `description` | From the selected `@RequestBody` (below); when blank, the `description` of the first `content` entry's schema that sets one |
| `@RequestBody.content` examples | The Media Type Object's `examples` or `example` | See [Examples](#examples); never on the Request Body Object, never on a form body |
| `@ApiResponse` and `@ApiResponses` on the method or the class | The operation's `responses` | See [Declared responses](#declared-responses); their `content.schema.implementation` is published, unlike a request body's |
| `@Operation.operationId`, `@Parameter.name`, `in`, `required`, `content`, `schema.implementation`, `array.schema.implementation`, `@RequestBody.content.mediaType`, `content.schema.implementation`, `required` | Nothing | Checked against the runtime; see [Agreement with the runtime](#agreement-with-the-runtime) |
| `ref` of `@Parameter`, `@RequestBody`, or `@ExampleObject` | Nothing | Fails startup: the document declares no reusable parameters, request bodies, or examples to reference |
| Other members of `@Schema` and `@ArraySchema` on an input | Nothing | Named in a warning; see [Ignored schema members](#ignored-schema-members) |

- **Which `@RequestBody`.** A request body is documented by exactly one `@RequestBody`: the first
  that sets any member among the one on the body parameter, the first on the method, and the
  `requestBody` of the first `@Operation`. Their members are never merged. A form request body has
  no body parameter, so only the last two are read, and only its `description` is published.
- **Which `@Parameter`.** Each Parameter Object reads the first `@Parameter` of its input. A
  `@Parameter` on the body parameter is neither read nor checked. A form field's `@Parameter` is
  checked but publishes nothing.
- **Schema documentation members.** The `description`, `example`, and `deprecated` of a
  parameter's own `@Parameter(schema = @Schema(...))` fill the Parameter Object as the table says;
  its `title` and `externalDocs` are not published. A request body's own content schema fills its
  `description` and its Media Type Objects' `example`; its `deprecated`, `title`, and
  `externalDocs` are not published anywhere. The documentation members of an `@ArraySchema`'s
  element schema are not published. None of these logs a warning.
- **DTO members.** A body type's member `title`, `description`, and `example` reach the document only
  as the framework's schema generator wrote them into the captured schema (under the member's JSON
  name). The document adds, renames, and removes nothing there.
- **Not applied and not checked.** Every member the table does not list, among them `@Parameter`
  `style`, `explode`, `allowReserved`, `allowEmptyValue`, and `extensions`; `@Operation`
  `parameters`, `responses` (declare responses with `@ApiResponse` on the method or the class
  instead), `security`, `servers`, and `extensions`; `@RequestBody.extensions`;
  the `@Content` members other than `mediaType`, `examples`, `schema`, and `array`; and the
  extensions of `@Tag`, `@ExternalDocumentation`, and `@ExampleObject`. A method-level `@Parameter`
  or `@Parameters` entry is read only to hide an input (see [Hidden inputs](#hidden-inputs)).

### Tags

- **Operation `tags`.** The distinct names of the first `@Operation`'s `tags`, in declared order,
  then of the method's `@Tag`s, then of the class's `@Tag`s, each in resolved order; the first
  occurrence of a name is kept and blank names are ignored. Repeated `@Tag`s arrive as a `@Tags`
  container, which is unwrapped in place.
- **Root `tags`.** Every `@Tag` on a published operation's method or class contributes a Tag Object
  with `name`, `description`, and `externalDocs` (written only with a set `url`), in that order. The
  root `tags` is sorted by name (case-sensitive) and left out when empty. A name that appears only in
  `@Operation.tags` adds no root tag.
- **Conflicts.** The `description` and the `externalDocs` of one tag name are merged separately
  across every declaration, in document order. A declaration that leaves a member unset never
  conflicts, and the set value is published. Two declarations that set a member to different values
  fail startup, naming the tag, the operation that first set it, and the first later operation whose
  value differs, never either value. `description` is compared first; `externalDocs` is compared as
  the published object. An operation whose method and class `@Tag`s differ names itself twice.

### Examples

- **Values.** An example value (`@Parameter.example`, a schema's `example`, or
  `@ExampleObject.value`) is published as JSON when its trimmed text parses as exactly one JSON value,
  and otherwise as the string it is, untrimmed. `"42"` becomes the number `42`, `"null"` the JSON
  `null`, `"{\"a\": 1}"` an object, and `"42 items"` stays a string.
- **Named examples.** `@ExampleObject`s become an `examples` map keyed by `name`, in declaration
  order. Each Example Object holds `summary`, `description`, and `value` or `externalValue`, in that
  order, each when set.
- **`examples` wins.** When an input declares both named examples and an example value, only
  `examples` is published.
- **Request bodies.** Each `content` entry of the selected `@RequestBody` applies to its
  `mediaType`, or to every media type of the body when `mediaType` is empty. For each media type of
  the body, the first applying entry, in declaration order, that declares named examples or whose
  schema sets `example` supplies that Media Type Object's `examples`, or else its `example`. An entry
  never adds a media type. A form request body publishes no examples.
- **Malformed examples fail startup.** An `@ExampleObject` with a blank `name`, a `name` an earlier
  entry of the same array already uses, both `value` and `externalValue`, or a `ref` fails startup
  naming the operation, the input, and the attribute.

### Agreement with the runtime

Annotations document what the runtime does; they cannot change it. Each of the following fails
startup, after the operation's schema checks and before its documentation is read, in this order:
the operation id, the request body, the parameters in published order, then the form fields. The
first failure wins.

| Attribute | Fails when |
|---|---|
| `@Operation.operationId` | It is set on the first `@Operation` and differs from the runtime operation id |
| `@RequestBody.ref`, `@Parameter.ref` | It is set (see [Swagger annotations](#swagger-annotations)) |
| `@RequestBody.content.mediaType` | It is set and is not among the media types the request body publishes |
| `@RequestBody.content.schema.implementation` | It is set and differs from the erasure of the bound body type; on a form request body, whenever it is set |
| `@RequestBody.required` | It is `true` and the document does not mark the request body `required` (see [Request bodies](#request-bodies)); a form request body never is |
| `@Parameter.content` | It has any entry: the runtime never binds a parameter's content |
| `@Parameter.name` | It is set and differs from the bound name; a header name that differs only in ASCII letter case agrees |
| `@Parameter.in` | It is not `DEFAULT` and differs from the bound location; on a form field, whenever it is not `DEFAULT` |
| `@Parameter.required` | It is `true` and the runtime certainly accepts a missing value (below) |
| `@Parameter.schema.implementation` | It is set and differs from the erasure of the bound type |
| `@Parameter.array.schema.implementation` | It is set and differs from the erasure of the element type of a collection or array input, or the input is neither a collection nor an array. An input whose element type is not known, such as a wildcard collection or a collection field of a composite bean, is not checked |

- **Name comparison.** Media types and query, path, cookie, and form-field names are compared
  exactly, case included. Header names are compared ignoring ASCII letter case, as HTTP does: a
  `@Parameter(name = "x-trace")` on a header bound as `X-Trace` agrees, and the document publishes
  `X-Trace`. Only ASCII letters fold, so a non-ASCII look-alike such as U+212A KELVIN SIGN never
  matches `k`. A hidden method-level entry names a header the same way, ignoring ASCII letter case
  (see [Hidden inputs](#hidden-inputs)).
- **Types.** A primitive type and its wrapper are the same type.
- **Message.** `<subject>: operation '<id>' declares <attribute> on <input>, which contradicts how the
  runtime binds it (<fact>); documentation metadata cannot change it, so remove the attribute or make
  it agree`, one line, where `<input>` is `query parameter q`, `header parameter X-Trace`, `form field
  f`, `request body`, and so on, and `<subject>` is as in [Hidden inputs](#hidden-inputs). No
  annotation value is echoed.

**Requiredness.** For each parameter and form field the runtime reports whether a missing value is
rejected, with one of three answers. `@Parameter(required = true)` is checked against it:

| The runtime says | For example | `@Parameter(required = true)` |
|---|---|---|
| Required: a missing value is certainly rejected | A path parameter; an input with a null-rejecting constraint such as `@NotNull`, with a bean validator bound and no group sequence involved | Agrees; `required: true` is written, as without the annotation |
| Not required: a missing value certainly binds | An input with `@DefaultValue`, without a constraint annotation, or with no bean validator bound; a non-array collection with only `@NotNull` | Fails startup |
| Cannot be determined | A primitive method parameter without `@DefaultValue`; any other case | Not published: the Parameter Object carries no `required`, and one warning names the input (see [Warnings](#warnings)) |

`@Parameter(required = false)` is never checked.

### Ignored schema members

The captured schema of an input stays authoritative. Every non-default member of the `@Schema` in
`@Parameter.schema`, `@Parameter.array.schema`, `@RequestBody.content.schema`, or
`@RequestBody.content.array.schema`, and every non-default member of an `@ArraySchema` other than
`schema`, is not published, and the operation's ignored-members warning names it (see
[Warnings](#warnings)). Exempt from the warning are the documentation members `description`,
`title`, `example`, `deprecated`, and `externalDocs`, and an `implementation` that is compared (see
[Agreement with the runtime](#agreement-with-the-runtime)).
`@RequestBody.content.array.schema.implementation` is not compared and is warned.

`@Parameter(schema = @Schema(hidden = true))` does not hide an input: `hidden` there is an ignored
member and is warned. Hide an input with `@Parameter(hidden = true)` or a `@Schema(hidden = true)` on
the input itself (see [Hidden inputs](#hidden-inputs)).

---

## Operation Responses

Every visible operation publishes a `responses` object. Without `@ApiResponse` it is inferred from
the resource method's return type; with one, the declared responses replace the inferred one. The
document describes responses only: the runtime never checks a returned value against it, and no
error-body schema is ever inferred.

### Inferred responses

An operation that declares no `@ApiResponse`, on its method or its class, publishes exactly one
response:

| Return type of the resource method | Published `responses` |
|---|---|
| `void`, `Void`, or `Future<Void>` | `{"204": {"description": "No Content"}}` |
| `Future<T>` or a plain type `T`, with a JSON-compatible produces type | `"200"` with description `OK` and, for each JSON-compatible produces type in declared order (`application/json` when the method declares none), a Media Type Object whose `schema` is a reference to the component `<operationId>.response`: the output schema of `T` under the operation's output profile |
| `String` or `Future<String>` | `"200"` with description `OK` and one media type per declared produces type, in declared order (`application/json` when none), each `{}`: the body is raw text, not a JSON string, so no schema is published |
| Everything decided at runtime (below) | `{"default": {"description": "Response determined at runtime"}}`, with no content |

The rules apply in this order, the first match winning:

1. **No content.** `void`, `Void`, and `Future<Void>` publish `204`.
2. **Type variables.** Every type variable of the return type is resolved (see
   [Type variables](#type-variables)); then exactly one level of `io.vertx.core.Future` is
   unwrapped, so `Future<Response>` classifies as `Response` and `Future<String>` as `String`. A raw
   `Future` without a type argument is decided at runtime, and so is a subtype of `Future` (for
   example `interface MyFuture<T> extends Future<T>`): only `io.vertx.core.Future` itself is
   unwrapped, so a subtype publishes the runtime-determined `default` response with no content. A
   `Future` or a subtype inside the unwrapped `Future` (`Future<Future<T>>`, `Future<MyFuture<T>>`)
   publishes that `default` response too.
3. **Still open.** A type that still holds a type variable or a wildcard is decided at runtime and
   is never handed to the generator.
4. **Handled by the runtime.** `Response`, `CompletionStage`, `ReadStream`, `Buffer`, and their
   subtypes, `Optional`, and `byte[]` are decided at runtime. So is a type bound to a
   `ResponseProducerBinding`: the type itself, a superclass, or an interface of it equals a bound
   `type()`, walked as the runtime looks a producer up. A producer binding wins before the `String`
   and JSON rows, because the runtime consults producers before any body encoder.
5. **`String`.** A `String` is raw text under every produces type, `application/json` and
   `text/event-stream` included: the runtime writes a `String` entity as it is.
6. **Event streams.** Any other type whose produces list holds a type starting with
   `text/event-stream` is decided at runtime.
7. **JSON entities.** Any other type publishes `200` under its JSON-compatible produces types. A
   media type is JSON-compatible exactly when it contains `json`, compared case-sensitively as the
   runtime's JSON encoder decides: `application/json` and `application/vnd.acme+json` are,
   `application/JSON` is not. Other produces types are left out of the content. A type with no
   JSON-compatible produces type, such as one producing only `text/plain`, is decided at runtime.

The whole resolved type is generated: `Future<List<Item>>` publishes the output schema of
`List<Item>`, an array.

No `4XX`, `5XX`, or `default` response is ever added to an inferred response, and no error body is
described. Declare them with `@ApiResponse` (see [Declared responses](#declared-responses)).

### Type variables

Before classification, every type variable of the return type is resolved against the generic
superclasses and interfaces of the resource class the operation is registered on, the nearest
binding winning. For `class ItemResource extends CrudResource<Item>`, an inherited `T find()`
classifies as `Item` and an inherited `Future<List<T>> list()` as `Future<List<Item>>`.

These stay open and publish `default`:

- a method-level type variable, as in `<T> T any()`;
- a type variable of a generic resource class registered itself, as `T get()` on
  `GenericResource<T>`;
- a wildcard, as in `List<?>`.

### Declared responses

`@ApiResponse` (or its container `@ApiResponses`; the two forms are equivalent) is read at two
levels:

- **Method level.** The resource method, the methods it overrides, and the interface methods it
  implements, all together as one level.
- **Class level.** The resource class, its superclasses, and its implemented interfaces.

The declared set is the class level's responses plus the method level's, the method's declaration
winning for a status both declare (as swagger-core merges them). A non-empty declared set replaces
the inferred response entirely, also for an operation that declares nothing itself when its class
does. `@Operation.responses` is not read.

- **Valid statuses.** A status is `default`, a three-digit code from `100` to `599`, or an uppercase
  range key from `1XX` to `5XX`. Anything else, such as `2xx`, `600`, or `20`, fails startup.
- **One declaration per status and level.** A level that declares one status twice fails startup,
  naming the level. Because the method level includes the implementation and its interface methods,
  an implementation method and its interface method declaring one status differently fail too;
  identical declarations count once.
- **Order.** Response keys are published numeric codes ascending, then range keys ascending, then
  `default`, whatever the declaration order or level.
- **Descriptions.** A declared `description` is published as written. A blank one publishes the
  status's reason phrase (`Accepted` for `202`); a code without a registered phrase publishes the
  phrase of its class (`Success` for `299`); a range key publishes `Informational`, `Success`,
  `Redirection`, `Client error`, or `Server error`; and `default` publishes
  `Response determined at runtime`.
- **Response Object.** `description`, `headers` (when any), `content` (when published), then its
  `x-` extensions, in that order.

### A declared success status without content

A declared success status, a code from `200` to `299` other than `204` and `205` or the range key
`2XX`, whose `@ApiResponse` declares no `content` receives the inferred content when the return type
is inferable (the `200` rows of [Inferred responses](#inferred-responses)): the inferred media types
and, for a JSON entity, a reference to the inferred component `<operationId>.response`. Its declared
description and headers are kept:

| Declared status | Return type | Published content |
|---|---|---|
| Success status without `content` | A JSON entity or a `String` | The inferred content |
| Success status without `content` | Decided at runtime, or no content | None; startup does not fail |
| Any status with `content` | Any | The declared content only, even on an inferable return |
| Any other status without `content`: `204`, `205`, `3XX`, `404`, `default`, and so on | Any | None |

So `@ApiResponse(responseCode = "200", description = "The catalog item")` on a method returning a
plain `CatalogItem` publishes its description together with the `CatalogItem` schema.

This is a deliberate difference from swagger-core, whose `useReturnTypeSchema` defaults to `false`,
so swagger-core publishes such a status without content. A document generated at build time by a
swagger-core based tool may therefore differ from the served document at such a status.

**`useReturnTypeSchema = true`.** A status that sets it publishes the inferred content under any
status when it declares no `content`; with `content` declared on an inferable return, the declared
content wins. On a return type that is not inferable it fails startup, whether or not `content` is
declared.

### Response content and headers

- **Media types.** A `@Content` with a `mediaType` applies to that media type. One with a blank
  `mediaType` applies to each of the operation's produces types in declared order
  (`application/json` when it declares none), JSON-compatible or not. A media type declared twice
  in one status, also through a blank `mediaType`, fails startup naming `@Content.mediaType`.
- **Schema.** `schema = @Schema(implementation = X)` publishes `{"$ref":
  "#/components/schemas/<component>"}` (see [Response components](#response-components)).
- **Arrays.** `array = @ArraySchema(schema = @Schema(implementation = X))` publishes
  `{"items": {"$ref": "#/components/schemas/<component>"}, "type": "array"}`, members in that order,
  the component describing `X`. When a `@Content` sets both, the `schema` is published and the
  array's set members are warned as omitted.
- **Neither.** A `@Content` with neither implementation publishes its media type as `{}`.
- **Media Type Object.** `schema` (when published), then `examples` (when declared).
- **Headers.** A Header Object holds `description` (when set), `required` and `deprecated` (only
  `true`), then `schema`: a reference to the component `<operationId>.response.<status>.header.<name>`
  when `schema.implementation` is set, and the empty schema `{}` otherwise, since OpenAPI 3.1
  requires a header schema. A `@Header` with a blank name is left out and warned as `@Header.name`.
  One header name declared twice in one status fails startup naming `@Header.name`; names are
  compared ignoring ASCII letter case, so `X-Rate` and `x-rate` are the same header (non-ASCII
  letters never fold).
- **`@Header(hidden = true)` is not honored.** The header is published, and `@Header.hidden` is
  named in the [omitted-attribute warning](#omitted-response-attributes).
- **`hidden = true` on a response's `@Schema` is not honored either.** On a content schema, an
  `@ArraySchema`'s `schema` or `arraySchema`, or a header schema, the implementation is still
  generated and published in full, and `hidden` is merely warned (as `@Schema.hidden`, or
  `@ArraySchema.arraySchema`), not refused. To keep a type out of a document, remove the declaration or hide the operation.

**Documentation members.** The `description`, `title`, `example`, `deprecated` (only `true`), and
`externalDocs` (only with a `url`) of a `@Schema` that sets `implementation` are published beside the
reference they document, never in the component: beside the content or header schema's `$ref`, or
beside the `items` reference of an array. Their keys are in natural order, for example
`{"$ref": "#/components/schemas/getThing.response.200", "description": "The thing body", "title":
"Thing"}`. An `example` is published as JSON when its trimmed text parses as exactly one JSON value,
and as a string otherwise. Unlike a request body's, a response schema's `example` stays in the
Schema Object and never becomes the Media Type Object's `example`. A member that is set to
whitespace only counts as unset and is warned. A `@Schema` without `implementation` publishes
nothing, and every member it sets is warned.

**Examples.** `@Content.examples` becomes the Media Type Object's `examples` under the rules of
[Examples](#examples): keyed by `name`, in declaration order, each with `summary`, `description`, and
`value` or `externalValue`, a `value` that parses as JSON published as JSON. A blank or repeated
name, both `value` and `externalValue`, or a non-blank `ref` fails startup naming the operation, the
`@ExampleObject` attribute, and `@Content.examples on status <status>`. The extensions of an
`@ExampleObject` are not published and are warned.

**Extensions.** An `@ApiResponse.extensions` entry whose name starts with `x-` (case-sensitive)
becomes a member of the Response Object, after `content`, rendered as an `info` extension is (see
[`info`](#info)): `@Extension(name = "x-rate-limited", properties = @ExtensionProperty(name =
"limit", value = "10"))` becomes `"x-rate-limited": {"limit": "10"}`. Any other extension is not
published and is warned.

### Response components

| Schema | Component key |
|---|---|
| The inferred output type | `<operationId>.response`, one per operation, referenced from every media type and status that publishes the inferred content |
| A declared `schema.implementation` or `array` element implementation | `<operationId>.response.<status>` |
| Several different implementations in one status | `<operationId>.response.<status>.1`, `.2`, and so on, numbered from 1 in declaration order |
| A header's `schema.implementation` | `<operationId>.response.<status>.header.<name>` |

- **One implementation, one component.** One implementation used by several media types of a status,
  or as both a `schema` and an `array` element, is one component and gets no number. The same type
  under two statuses is two components.
- **Sanitizing and collisions.** Every key is built as in [Components](#components): each character
  outside `[A-Za-z0-9._-]` is replaced by `_`, and two components with the same key fail startup, as
  do the components of operations `get:report` and `get_report`. The failure names the colliding
  component as `the output schema of status <status> of operation '<id>'`, or
  `the output schema of header '<name>' of status <status> of operation '<id>'`.
- **Relocation and refused constructs.** Each component is the generated output schema, relocated
  as in [Relocation of local definitions](#relocation-of-local-definitions) (each root `$defs` entry
  becomes `<component>.<def>`) and checked as in [Refused constructs](#refused-constructs).
- **Only what is referenced.** The inferred type is generated, checked, and published only when
  some status publishes the inferred content, so an operation whose declared responses supply all
  their content never generates its return type.

### Omitted response attributes

Every attribute of a declared response that is set to something other than its default and that the
sections above do not publish is left out and does not fail startup. Among them:

- `@ApiResponse.ref` and `@ApiResponse.links`;
- `@Content.encoding` and every other `@Content` member except `mediaType`, `schema`, `array`, and
  `examples`;
- every `@Schema` member other than `implementation` and the documentation members, such as `type`,
  `format`, or `maxProperties`, and every member of a `@Schema` without `implementation`;
- every `@ArraySchema` member other than `schema`, such as `minItems` or `arraySchema`;
- `@Header.ref`, `@Header.hidden`, and every other `@Header` member except `name`, `description`,
  `required`, `deprecated`, and `schema`;
- an extension whose name does not start with `x-`, named `@ApiResponse.extensions` without its name,
  and `@ExampleObject.extensions`.

One warning per operation names all of them, each as `<attribute> on status <status>`, in published
status order; within a status the response's own members come first, then each header's, then
each content's, members in name order. It is logged at WARN on the `DocumentWarnings` logger once
the document is written (see [Metadata warnings](#metadata-warnings)), and quotes no attribute value:

```text
apidocs.documents.<name>: operation '<id>' at mount '<mount path>' declares response attributes the
document does not publish: @ApiResponse.links on status 200, @Header.hidden on status 404
```

### Output profile and generation

- **Output profile.** The operation's output profile is the JSON mapper profile the runtime
  serializes its responses with, such as one selected with `@JsonProfile`, looked up in the
  `JsonMapperProfileRegistry`. Each output type is described by the framework's output generator for
  that profile (`AnnotationJsonSchemaGenerator.forOutputProfile`), so the component holds the names
  and members a response carries: the profile's naming strategy applies, a read-only member appears,
  and a write-only member does not.
- **Only the framework's output generator.** Output schemas come from that generator alone. No
  `OperationSchemaSource` is consulted for a response, and no schema is built from a Java type by
  any other means.
- **Once per document.** Output schemas are generated while a document is assembled, once per
  document and component, by the composition that assembles it, with one generator per profile.
  Another server instance that finds the document stored only compares its publication (see
  [Several server instances](#several-server-instances)). Nothing is generated per request.

### Refusals of output types

Every published output type is checked: the inferred type (when some status publishes it), each
declared content implementation, an `array` element implementation included, and each header
implementation. Each is generated, then inspected for renamed members, then for hidden members,
then checked for refused constructs; the first failure stops startup.

These messages start with the document's configuration path, followed by the usual subject; below,
`<prefix>` stands for
`apidocs.documents.<name>: Application '<name>' (declared by <binary name>) at mount '<mount path>'`.
Refused constructs and component collisions of an output schema start with the subject alone, as
for inputs. No message carries schema text, an example, or a payload. Each message is one line; it is
wrapped here for reading.

**Generator failure.** An exception while resolving the profile, generating the schema, or
inspecting it, for example a `JsonSchemaGenerationException` for a member whose
`@Schema(implementation = ...)` redirects a type the profile overrides, fails startup:

```text
<prefix>: operation '<id>' cannot publish the output content schema of status <status>:
generating it in output direction failed
```

A header type is named `header '<name>' schema` instead of `content schema`, and the reason is
`inspecting it for renamed members failed` or `inspecting it for hidden members failed` when that
step throws. The status is the declared status of the content or header; the inferred type is always
named as status `200`, also when it is published under `201`, `2XX`, or a `useReturnTypeSchema`
status. The generator's exception is kept as the cause.

**Renamed output property.** A member that `@Schema(name = ...)` describes under a name other than
the one the profile's mapper serializes fails startup, because the document would describe a
property the response never carries:

```text
<prefix>: operation '<id>' publishes <declaring type> as an output type of status <status>; its member
'<member>' is serialized as '<serialized name>' but described as '<schema name>', so the document
would describe a property the response never carries; make @Schema(name) agree with the serialized
name or remove it
```

The first renamed member reachable from the type is named. `@Schema(name)` loses to a non-empty
`@JsonProperty` that names something other than the member itself, and the check reflects that:
`@JsonProperty("wire") @Schema(name = "label") String code` publishes `wire` and is not refused, while
a bare `@JsonProperty` or `@JsonProperty("code")` beside `@Schema(name = "label")` is.

**Hidden output members.** The output generator ignores `@Hidden`, and honors
`@Schema(hidden = true)` only on the property's own field or getter. A member or type that the
output schema still describes while it carries either marker fails startup, because the response
would publish what the marker is meant to hide:

```text
<prefix>: operation '<id>' publishes an output type of status <status> that describes <what>, which
carries <marker>; <fix>
```

`<what>` and `<marker>` are as for a request body (see
[Hidden members of a request body](#hidden-members-of-a-request-body)). The first entry, by
declaring type and then member with a type before its members, is named, with the one fix its
position implies:

| Position of the marker | Fix |
|---|---|
| `@Hidden` on a property's field, getter, or setter, without an honored `@Schema(hidden = true)` | `the output generator ignores @Hidden; declare @Schema(hidden = true) on the property's own field or getter` |
| `@Schema(hidden = true)` on a setter, on a creator parameter, through an annotation bundle or a mix-in, or at another position the output generator ignores | `the output generator ignores @Schema(hidden = true) where it is declared; declare it directly on the property's own field or getter, not through a bundle or mix-in` |
| A type carrying either marker, a class-level `@Schema(hidden = true)` included | `the output generator does not hide a type; declare @Schema(hidden = true) on the field or getter of each member that references it, or hide the operation` |
| An enum constant, or `@JsonUnwrapped` content | `the output generator cannot leave this member out where the document describes it (an enum constant or @JsonUnwrapped content); remove it from the published type, or hide the operation` |

- **The fix that works.** A member carrying `@Schema(hidden = true)` on its own field or getter,
  with or without `@Hidden`, is absent from the published schema, and startup succeeds.
- **Input-only positions do not apply.** The input generator also ignores a marker on the field or
  getter of a property bound through a setter, a builder, or a static factory's creator parameter,
  of a case-insensitively bound nested bean described inline, of a converter-bound property, and of
  a map member rendered through its value constraints (see
  [Hidden members of a request body](#hidden-members-of-a-request-body)). These are ways of binding
  a request; a response is serialized, and only the positions in the table are refused for it.
- **Nothing is dropped.** The refusal never changes the generator's output or the response: the
  member is still serialized at runtime. Hide the operation, or fix the marker.
- **Hidden operations are never checked**, and with no enabled document nothing is checked (see
  [Nothing is built without an enabled document](#nothing-is-built-without-an-enabled-document)).

### Order of the response checks

An operation's responses are checked after its inputs and their annotations, in this order:

1. The declared statuses: each one's validity, then repeated statuses, at the method level and
   then at the class level.
2. Per declared status in published order: `useReturnTypeSchema` on a return type that is not
   inferable, then each `@Content`'s examples and media types in declaration order, then the header
   names.
3. The output types: the inferred type first, when some status publishes it, then per status in
   published order each content implementation in declaration order, then each header
   implementation, each through the steps of [Refusals of output types](#refusals-of-output-types).

Component collisions are found as the document is written, after every operation is checked.

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
  the annotation's `info`: it publishes `title`, `description` (when present), and `version`, and no
  member of the annotation appears beside them.
- **From `@OpenAPIDefinition`.** Otherwise the `@OpenAPIDefinition(info = ...)` on the declaring
  type itself. The annotation is never inherited: one on a superclass or a superinterface is never
  read, even where `@OpenAPIDefinition` is `@Inherited`.
- **Neither.** Startup fails naming `apidocs.documents.<name>.info`.

No default `info` is invented. Other members of `@OpenAPIDefinition` are not published in this release.

An annotated `info` publishes every member of `@Info`, `@Contact`, and `@License` that is set, in the
field order of the OpenAPI 3.1.1 objects. A blank string is unset, and an unset member is never
written:

- **Info:** `title`, `summary`, `description`, `termsOfService`, `contact`, `license`, `version`,
  then its extensions.
- **Contact:** `name`, `url`, `email`, then its extensions; written only when one of them is set.
- **License:** `name`, `identifier`, `url`, then its extensions; written only when one of them is
  set.

An `@Info` that sets only `title`, `version`, and `description` therefore publishes exactly those
three. Member values are published as declared and never echoed in a message.

URLs (`termsOfService`, `contact.url`, `license.url`, every `externalDocs.url`, and
`@ExampleObject.externalValue`) are published exactly as declared and are not checked; never put
credentials, internal hosts, or non-`http(s)` schemes in them.

- **Extensions.** An `@Extension` of the `@Info`, its `@Contact`, or its `@License` whose name
  starts with `x-` (case-sensitive) becomes a member of that name, written after the object's other
  members. Its value is an object of its `@ExtensionProperty` names and values; a property with a
  blank name is skipped, so an extension whose properties are all skipped publishes `{}`. A property
  with `parseValue = true` holds its value parsed as JSON when the text is exactly one JSON value,
  and the text as a string otherwise. Extensions of one name merge in declaration order: the first
  keeps its position and a repeated property takes the later value.
- **Other extension names.** An extension whose name does not start with `x-`, a blank name
  included, is not published, and one warning per document names it (see [Warnings](#warnings)).
- **License `identifier` and `url`.** OpenAPI 3.1 makes them mutually exclusive, so an `@License`
  that sets both fails startup naming the application, its declaring interface, and
  `@OpenAPIDefinition.info.license`, and echoing neither value.
- **Documented limit: a license without `name`.** OpenAPI 3.1 requires `name` in a License Object,
  but an `@License` that sets `identifier` or `url` without `name` is published as declared, without
  `name`, and is not refused. Set `name` whenever the license sets anything.

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

Every warning of the module is logged at WARN on logger
`dev.vertique.rest.openapi.docs.DocumentWarnings` and starts with `apidocs.documents.<name>`. No
warning carries schema text, an annotation value other than the extension names it lists, or a
configuration value other than the document name and a mount path. Each is logged at most once per component, so a second composition
or server instance of the same component does not repeat it.

### Controls that skip the document routes

The module logs one such warning per enabled document. It is logged when the documentation
module's composition checks pass, before any router is created, so it can appear even when another
startup check (including another module's composition validator in the same pass, or a later check)
fails the deployment.

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

### Metadata warnings

Four warnings report Swagger annotation content the document does not publish. Unlike the warning
above, they are held back while the document is assembled and logged only once it is built
successfully, so a document that fails startup logs none of them. They are logged in document order:
the `info` warning first, then each operation's warnings in the order the document lists the
operations, its input warnings before its response warning. Hidden operations and hidden inputs
cause none.

- **Unpublished `info` extensions.** One per document, when the annotated `info` declares an
  extension whose name does not start with `x-` (see [`info`](#info)). It names the application and
  its declaring interface and lists every such name, sorted, a blank name as `<unnamed>`; it quotes
  no property value:
  `apidocs.documents.<name>: application '<name>' (declared by <binary name>) has
  @OpenAPIDefinition.info extensions whose names do not start with 'x-', which are not published:
  <names>`.
- **Ignored schema members.** One per operation that declares any (see
  [Ignored schema members](#ignored-schema-members)), naming every such member as
  `<annotation path> on <input>`, for example `@Parameter.schema.maxLength on query parameter q`:
  `apidocs.documents.<name>: operation '<id>' at mount '<mount path>' declares schema members the
  document does not publish, because each input's canonical schema stays authoritative: <members>`.
- **Requiredness that cannot be determined.** One per parameter or form field that declares
  `@Parameter(required = true)` while the runtime cannot tell whether it rejects a missing value
  (see [Agreement with the runtime](#agreement-with-the-runtime)):
  `apidocs.documents.<name>: operation '<id>' at mount '<mount path>' declares @Parameter.required on
  <input>, but whether the runtime rejects a missing value cannot be determined, so the document does
  not mark it required`.
- **Omitted response attributes.** One per operation whose declared responses set attributes the
  document does not publish, naming each as `<attribute> on status <status>` (see
  [Omitted response attributes](#omitted-response-attributes)):
  `apidocs.documents.<name>: operation '<id>' at mount '<mount path>' declares response attributes
  the document does not publish: <attributes>`.

Each message is one line; it is wrapped here for reading.

---

## Failures, Constraints, and Common Mistakes

### Startup failures

Every failure below stops startup. Each message names what is wrong and never echoes configuration
values, schema text, references, pattern text, redaction locations, reserved names, or annotation
values. Messages about the document's content name operation ids, rendered paths, input names,
component keys, tag names, annotation attributes, the class of the bound schema source, the
declaring type and member of a hidden member, response statuses and header names, and the declaring
type, member, serialized name, and schema name of a renamed output member. Two quote what the
developer declared: an invalid response status is quoted as declared, and a renamed output member's
names are quoted.

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
| An enabled document takes its `info` from `@OpenAPIDefinition` and its `@License` sets both `identifier` and `url` | `ConfigurationException` naming the application, its declaring interface's binary name, `@OpenAPIDefinition.info.license`, and `apidocs.documents.<name>.info`; neither value is echoed |
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
| A Swagger annotation of a visible operation of a documented application contradicts how the runtime binds it (see [Agreement with the runtime](#agreement-with-the-runtime)) | `RestConfigurationException` with the same start, naming the operation and the attribute, and for an input attribute the input and the runtime fact it contradicts; no annotation value |
| A `@Parameter`, `@RequestBody`, or `@ExampleObject` of a visible operation sets `ref` | `RestConfigurationException` with the same start, naming the operation and the attribute with its input, and stating that the document declares no reusable parameters, request bodies, or examples |
| An `@ExampleObject` of a visible input has a blank name, a name used twice in one array, or both `value` and `externalValue` (see [Examples](#examples)) | `RestConfigurationException` with the same start, naming the operation, the attribute, and where it is declared |
| Two `@Tag` declarations of one name set a different `description` or `externalDocs` (see [Tags](#tags)) | `RestConfigurationException` with the same start, naming the tag, the member, and both operations, never either value |
| A declared `@ApiResponse` status is not `default`, a code from `100` to `599`, or an uppercase range key from `1XX` to `5XX` (see [Declared responses](#declared-responses)) | `RestConfigurationException` starting `apidocs.documents.<name>: ` and then the same start, naming the operation and quoting the declared status |
| The method level or the class level declares one response status twice | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the status, and the level |
| `@ApiResponse.useReturnTypeSchema` is `true` on a return type that is not inferable (see [A declared success status without content](#a-declared-success-status-without-content)) | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the status, and the attribute |
| One status declares a media type twice, or one header name twice, ignoring ASCII letter case (see [Response content and headers](#response-content-and-headers)) | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the status, and `@Content.mediaType` or `@Header.name`; neither value is echoed |
| An `@ExampleObject` in `@Content.examples` has a blank or repeated name, both `value` and `externalValue`, or a `ref` | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the attribute, and the status |
| A published output type cannot be generated or inspected under the operation's output profile (see [Refusals of output types](#refusals-of-output-types)) | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the content or header schema, and the status (`200` for the inferred type); the generator's exception is the cause; no schema text |
| A published output type describes a member under a `@Schema(name)` that differs from its serialized name | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the status, the declaring type, the member, the serialized name, and the schema name |
| A published output type describes a member or type carrying `@Hidden` or a `@Schema(hidden = true)` the output generator ignores | `RestConfigurationException` with the `apidocs.documents.<name>: ` start, naming the operation, the status, the member or type, its marker, and the fix |
| A generated output schema holds a refused construct | `RestConfigurationException` with the same start, naming the operation, the output schema and its status, the construct, and the JSON Pointer |
| Two components of a document would have the same key | `RestConfigurationException` with the same start, naming the input or output of both components and the key; when either is a relocated definition, it says `one component` instead of the key, and states that component keys replace every character outside `[A-Za-z0-9._-]` with `_` |
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
- **Expecting security schemes in the document.** A document describes operations, their inputs,
  and their responses only.
- **Expecting a response for `Response`, `CompletionStage`, or a producer-bound type.** Their content
  is decided at runtime, so they publish `default` only; declare the responses with `@ApiResponse`.
  Return `Future<T>` instead of `CompletionStage<T>` to have `T` inferred.
- **Expecting a schema for a `String` return.** A `String` is raw text under every media type,
  `application/json` included, and publishes no schema.
- **Expecting error responses to be inferred.** No `4XX`, `5XX`, or error body is ever inferred;
  declare them with `@ApiResponse`.
- **Declaring a `404` and losing the inferred `200`.** A declared set replaces the inferred response
  entirely; also declare the success status. A success status declared without `content` keeps the
  inferred content.
- **Comparing with a build-time generated document.** A success status declared without `content`
  keeps the inferred schema here, while swagger-core publishes it without content unless
  `useReturnTypeSchema` is `true`.
- **Declaring responses in `@Operation(responses = ...)`.** They are not read; use `@ApiResponse` on
  the method or the class.
- **Writing a range key in lowercase.** `2xx` fails startup; write `2XX`.
- **Declaring one status differently on an implementation and its interface method.** Both are the
  method level, so startup fails; declare it once.
- **Hiding an output property with `@Hidden`.** The output generator ignores `@Hidden`, so startup
  fails; declare `@Schema(hidden = true)` on the property's own field or getter.
- **Renaming an output property with `@Schema(name = ...)`.** The response still carries the
  serialized name, so startup fails; rename it on the wire with `@JsonProperty`, or remove the
  `@Schema(name)`.
- **Hiding a response header with `@Header(hidden = true)`.** It is not honored: the header is
  published and the attribute is warned. Remove the `@Header` instead.
- **Hiding a response body with `@Schema(hidden = true)` on its `@Content`.** It is not honored:
  the implementation is published in full and the attribute is warned. Remove the content
  declaration or hide the operation.
- **Expecting a response schema's `example` on the Media Type Object.** It stays beside the `$ref`
  in the Schema Object; use `@Content.examples` for media-type examples.
- **Expecting a server URL from the request.** `servers[0].url` is the configured `serverUrl` or the
  mount path; behind a proxy that changes the path, configure `serverUrl`.
- **Putting credentials in `serverUrl`.** It is published exactly as configured, user information
  included; keep credentials and other secrets out of it.
- **Putting credentials, internal hosts, or other schemes in a documentation URL.** `termsOfService`,
  `contact.url`, `license.url`, every `externalDocs.url`, and `@ExampleObject.externalValue` are
  published exactly as declared and are not checked; use public `http(s)` URLs only.
- **Hiding a path parameter.** It fails startup; a document describes every path variable.
- **Expecting a hidden input to stop binding.** Hiding changes the document only; the request still
  binds and validates the input.
- **A schema source that replaces or edits the generated body schema.** With an enabled document it
  fails startup; return the generated body schema and its redaction manifest unchanged.
- **A schema source that describes a different type than the bound body type.** The hidden-member
  check inspects the bound type only, so `@Hidden` members of the other type are not refused and
  publish; return the generated description of the bound type unchanged.
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
- **Marking a parameter `@Parameter(required = true)` without enforcing it.** On an input with
  `@DefaultValue`, without a constraint annotation, or with no bean validator bound, the runtime
  accepts a missing value, so startup fails. Add a null-rejecting constraint such as `@NotNull`, or
  remove the attribute.
- **Expecting `@Parameter(required = true)` to add `required`.** The document writes `required`
  from what the runtime enforces; when that cannot be determined, the attribute is not published
  and a warning names the input.
- **Narrowing a schema with `@Parameter(schema = @Schema(...))`.** Members such as `maxLength`,
  `format`, or `pattern` are not published; the captured schema stays as the runtime validates it,
  and a warning names them. Put the constraint on the input itself.
- **Hiding an input with `@Parameter(schema = @Schema(hidden = true))`.** It does not hide the input;
  it is warned as an ignored member. Use `@Parameter(hidden = true)`.
- **Spelling a query, path, cookie, or form-field name in another case.** Only header names ignore
  ASCII letter case; `@Parameter(name = "ID")` on a query parameter bound as `id` fails startup.
- **Expecting a hidden operation to stop routing.** `@Hidden` and `@Operation(hidden = true)` change
  the document only; the route still answers, and its id and route are still checked for reserved
  ids and document-URL collisions.
- **Expecting `@Operation.tags` alone to add a root tag.** Only `@Tag` on the method or class adds a
  root tag with its description; an `@Operation.tags` name appears on the operation only.
- **Declaring one tag with different descriptions.** Every `@Tag` of one name that sets a
  `description` or `externalDocs` must set the same value, or startup fails; set it once.
- **A license with both `identifier` and `url`, or without `name`.** Both together fail startup.
  A license without `name` is published as declared, although OpenAPI 3.1 requires `name`; set it.
- **Expecting annotated `info` members beside a configured `info`.** A configured `info` replaces
  the annotation as a whole.

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
`AuthEnforcementCapability`, the optional `OperationSchemaSource` that `RestModule` declares, the
multibound `Set<ResponseProducerBinding<?>>` (from `dev.vertique:vertique-rest-core`), which
decides which return types publish `default`, and the `JsonMapperProfileRegistry` from
`JsonRuntimeModule` (in `dev.vertique:vertique-json`), which `RestModule` includes.

---

## Dependencies

| Module | Why |
|---|---|
| `dev.vertique:vertique-rest-jaxrs` | The declared-application view, the operation publication seam the module consumes, and the `ApiDocsInstalled` marker |
| `dev.vertique:vertique-rest-core` | `RouterMount`, `MountMeta`, the extension phases, `JaxRsConfig` default headers, `ResponseProducerBinding`, and `RestConfigurationException` |
| `dev.vertique:vertique-core` | `ConfigParser`, configuration path navigation, `ConfigurationException`, `@KeyedBy`, and `JsonMapperProfileRegistry` |
| `dev.vertique:vertique-json-schema` | The redaction manifest each published request body is verified against and redacted by, and whose digest is part of the comparison between instances; the input generator that reports hidden members of a request body; the output generator that describes response types and reports their renamed and hidden members |
| `io.vertx:vertx-json-schema` | Compile dependency; evaluates whether a captured request-body schema accepts an absent body, which decides the request body's `required` |
| `io.swagger.core.v3:swagger-annotations-jakarta` | `@OpenAPIDefinition`, read for `info` on the declaring interface, and the operation, parameter, request-body, response, tag, example, and hiding annotations described in [Swagger annotations](#swagger-annotations) and [Operation Responses](#operation-responses) |
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
- Annotate an operation with `@Operation(summary = ...)` and its class with `@Tag(name = ...)`:
  expect the summary and the tag on the operation, and the tag in the root `tags`.
- Mark an operation `@Hidden`: expect it, its components, and the tags only it declared in neither
  form of the document, while a request to its route is still answered.
- Put `@Parameter(required = true)` on a query parameter with `@DefaultValue`: expect startup to fail
  naming the operation and `@Parameter.required` on that parameter.
- Put `@Parameter(schema = @Schema(maxLength = 5))` on a query parameter: expect its captured schema
  published unchanged and one `DocumentWarnings` warning naming `@Parameter.schema.maxLength`.
- Return `Future<Item>` from a `@Produces(APPLICATION_JSON)` method without `@ApiResponse`: expect
  exactly one response, `200` with description `OK`, whose `application/json` schema references
  `<operationId>.response`, a component holding `Item`'s serialized property names; return `void`
  and expect `204`; return `Response` and expect only `default`.
- Add `@ApiResponse(responseCode = "200", description = "The item")` and
  `@ApiResponse(responseCode = "404", description = "Missing")`: expect `200` with that description
  and the inferred content, and `404` without content.
- Put `@Hidden` alone on a field of the returned type: expect startup to fail naming the operation,
  the member, `@Hidden`, and the fix; move to `@Schema(hidden = true)` on that field and expect the
  property absent from the component and startup to succeed.
- Put `@Schema(name = "remark")` on a returned field serialized as `note`: expect startup to fail
  naming the member, `note`, and `remark`; set `apidocs.documents.<name>.enabled: false` and expect
  startup to succeed.
- Add `links` to a declared `@ApiResponse`: expect it in neither form of the document and one
  `DocumentWarnings` warning naming `@ApiResponse.links on status <status>`.
