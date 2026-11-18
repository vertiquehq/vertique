<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# HTTP Client Core

> **Status:** Stable
> **Package:** `dev.vertique.http.client`
> **Artifact:** `vertique-http-client-core`
> **Depends on:** `vertique-core`, `vertique-resilience`, Vert.x Web Client

`vertique-http-client-core` is a governed Vert.x `WebClient`. Applications build and send requests
with the Vert.x `WebClient`, `HttpRequest` and `HttpResponse` API; the module adds a builder, a base
URL, request header validation, and a call method whose attempts run on a `WebClient` that ends with
the attempt.

It does not define its own request or response types, and it does not replace the REST client's
declarative proxies.

## When To Use It

Use this artifact when application code sends outbound HTTP requests and wants one pooled client with
a base URL, validated request headers, and requests that cannot outlive the attempt that started
them. Declarative JAX-RS client proxies are provided by `vertique-rest-client`, which is a separate
artifact.

## Core Concepts

**One client, one agent.** `VertiqueHttpClientBuilder.build()` creates a `VertiqueHttpClient` that
owns one Vert.x `HttpClientAgent`. Every `WebClient` the client hands out is a lightweight wrapper
over that agent, so they all share its connection pool.

**Handed-out `WebClient` instances are delegates.** The `WebClient` from `webClient()` and the one
passed to an attempt behave like a normal Vert.x `WebClient` with four differences:

- `close()` does nothing and leaves the client usable; `VertiqueHttpClient.close()` closes the agent.
- `addInterceptor(...)` throws `UnsupportedOperationException`, so no second, ungoverned hook can be
  added. Wrappers built on a handed-out `WebClient`, such as `OAuth2WebClient.create(web, ...)`, are
  not supported.
- `updateSSLOptions(...)` throws `UnsupportedOperationException`: the client's TLS settings are shared by
  every call, so they are set once on the builder through `webClientOptions(...)`.
- Relative requests are resolved against the base URL.

**Base URL.** `baseUrl(...)` takes an `http` or `https` URL. Its scheme, host and port become the
defaults of the client and its path prefixes the URI of relative requests: with base URL
`https://api.example.com/v1`, `web.get("/items")` requests `/v1/items`. Absolute requests
(`getAbs`, `requestAbs`, ...) and requests that name their own host are sent unchanged. A client
without a base URL fails a relative request through the returned future without connecting.

**Header validation.** Vert.x rejects a header name or value with CR, LF or NUL when it is set. A
header that reaches the client without that check, and any header whose name starts with `vertique-`
(case-insensitive, a framework-reserved prefix), fails the request's future with
`IllegalArgumentException` and a constant message that does not repeat the header. Names such as
`X-Vertique` are not reserved. Values given to `defaultHeader(...)` follow the same rules and are
checked when the client is built.

**Body bounds.** With `maxRequestBytes` above zero, Buffer, JSON and form bodies are measured as they
will be encoded and refused with `HttpBodyLimitException` (`Direction.REQUEST`) before anything is
sent; stream and multipart bodies cannot be measured and are refused too. A body exactly at the bound
passes. `maxResponseBytes` applies whatever body codec the request uses: a declared `Content-Length`
over the bound fails the request before the body is read, and a counting wrapper around the codec's
stream stops passing bytes on at the bound, fails with `HttpBodyLimitException`
(`Direction.RESPONSE`) and resets the exchange. Nothing is buffered to enforce either bound.

**Egress.** `egress(EgressPolicy)` decides which targets the client may contact, before any
connection and again on every redirect hop. The default, `EgressPolicy.allowAll()`, admits every target
with a host and without user information; a target with user information (in a redirect `Location` or a
`UriTemplate` request) is refused with `HttpEgressDeniedException` (`USER_INFO`). Every policy refuses a
domain-socket server address (`CONNECT_ADDRESS`). `EgressPolicy.allowHosts(hosts, allowPlainHttp)` admits
only listed hosts, and then also refuses a server address, a virtual host (`virtualHost(...)` or a
caller-set `Host` header) and a request-level proxy unless its host is listed. A refusal fails the
request once with a constant message and dials nothing.

**Egress matching.** Hosts compare in lower case with one trailing dot removed, and names are never
resolved. `*.suffix` matches `a.suffix` and `b.a.suffix` but not `suffix` or `xsuffix`, and `*` matches any
host name. A wildcard never matches an IP literal or `localhost`: list those exactly. A host whose last
label is all decimal digits or a `0x` number counts as an IP literal. `https` always passes the scheme
rule; `http` passes only to `localhost`, a loopback IP literal, or when `allowPlainHttp` is true.
`EgressPolicy.check(URI)` runs the same rules without connecting.

**Redirects.** The default client does not follow redirects: a 3xx is the response. Following is turned
on through `webClientOptions(...)` (`followRedirects`, `maxRedirects`), and Vert.x's method handling and
limit stay: a 303 becomes a `GET` without a body, and 301, 302, 307 and 308 are followed only for `GET`,
`HEAD` and `QUERY`. On top of that, a hop from `https` to `http` fails with `HttpEgressDeniedException`
(`SCHEME`), a `Location` with user information fails with `USER_INFO`, a 307 or 308 that would resend a
request body to another origin is returned as the response, and a hop to another origin forwards only
`Accept`, `Accept-Language`, `Accept-Encoding`, `User-Agent`, `Cache-Control`, `Pragma`, `If-None-Match`
and `If-Modified-Since`. Every hop passes the egress policy.

**Warning: `allowAll()` with redirects.** With the default policy and redirects followed, a server you
call can redirect the client to any host, including internal addresses, such as a cloud metadata
endpoint or a service on a private network. Give a client that follows redirects, or that calls servers
you do not control, an `allowHosts(...)` policy.

**Attempt scope.** `execute` calls the attempt function once with a fresh `WebClient` scoped to that
call. When the future the function returns settles, every exchange the attempt started that is still
open is reset, and the scoped `WebClient` fails every later request with `IllegalStateException`
without contacting the server. Do everything the attempt needs, including reading and decoding the
response, before the function's future completes, and do not keep the `WebClient` after the attempt.

**Contexts.** Futures returned by the client complete on the caller's Vert.x context when it is
called on one. A `null` argument fails the returned future with `IllegalArgumentException`.

---

## Key Classes

### VertiqueHttpClientBuilder

Creates clients: `VertiqueHttpClientBuilder.create(vertx)` or `create(vertx, resilience)` with a
runtime the caller owns.

| Method | Effect |
|---|---|
| `name(String)` | Client name, `[A-Za-z0-9._~-]{1,128}`; default `default` |
| `baseUrl(String)` | Default origin and path prefix for relative requests |
| `readTimeout(long, TimeUnit)` | Read timeout setting of the client, not negative; default 30 seconds, `0` for none |
| `maxRequestBytes(long)` | Most request body bytes sent, not negative; default `0` for no bound |
| `maxResponseBytes(long)` | Most response body bytes read, not negative; default 10485760, `0` for no bound |
| `egress(EgressPolicy)` | Targets the client may contact, checked before connecting and on every redirect hop; default `EgressPolicy.allowAll()` |
| `webClientOptions(WebClientOptions)` | Copied; replaces the base options, which do not follow redirects |
| `poolOptions(PoolOptions)` | Copied; sizes the shared connection pool |
| `defaultHeader(String, String)` | Header added to every request that does not set it |
| `build()` | Builds a client with its own agent |
| `close()` | Closes the clients this builder built; the returned future is the same on every call |

Setters take effect at `build()`: a client keeps the values the builder held when it was built. An
invalid setting makes `build()` throw `HttpClientConfigurationException` with a message that does
not contain the rejected value; the builder stays usable once the setting is corrected. A runtime
passed to `create(vertx, resilience)` is not closed by the builder.

### VertiqueHttpClient

```java
VertiqueHttpClient client = VertiqueHttpClientBuilder.create(vertx)
        .baseUrl("https://api.example.com/v1")
        .build();

// direct use: each request is one governed attempt
Future<String> direct = client.webClient().get("/items").send().map(HttpResponse::bodyAsString);

// scoped use: the attempt owns everything it starts
Future<String> scoped = client.execute(web ->
        web.get("/items").send().map(HttpResponse::bodyAsString));
```

`name()` returns the client name, `webClient()` the governed `WebClient` for direct use, `execute`
runs an attempt function (with `CallOptions.defaults()` or the given options), and `close()` closes
the agent and resets requests still open. Closing again returns the same future.

### CallOptions

Immutable options for a single `execute` call. `CallOptions.defaults()` selects the client's own
settings.

| Method | Effect |
|---|---|
| `readTimeout(long, TimeUnit)` | Attempt timeout of this call, above zero; replaces the client's `readTimeout` |
| `noReadTimeout()` | No attempt timer for this call; of the two timeout methods the last call wins |
| `cancellation(CancellationSignal)` | Binds the call to a signal a caller can cancel from any thread |

The attempt timeout covers the whole attempt including the response body. When it fires, every
exchange the attempt has open is reset and the call fails with a `java.util.concurrent.TimeoutException`
with a constant message. A request sent straight through `webClient()` is one attempt bounded by the client's `readTimeout` in the same way, resetting just that exchange. A request still waiting for a pooled connection when the attempt ends sends
nothing once the connection frees.

`CancellationSignal.create()` makes a signal; `cancel()` works from any thread and repeating it does
nothing. Cancelling resets the open exchanges of every call bound to the signal that has not settled
and fails each with `HttpExchangeCancelledException`. A call started with a signal that is already
cancelled fails without sending. One signal may be shared by many calls; a settled call leaves it.

### EgressPolicy

Immutable and shareable between clients. `allowAll()` admits any target with a host and no user
information. `allowHosts(List<String> hosts, boolean allowPlainHttp)` admits the listed exact hosts,
IP literals, `*.suffix` wildcards and `*`; a null, blank or malformed entry throws
`IllegalArgumentException`. `check(URI)` throws `HttpEgressDeniedException` with reason `NO_HOST`,
`USER_INFO`, `HOST` or `SCHEME`, never connects and never resolves a name.

```java
VertiqueHttpClient client = VertiqueHttpClientBuilder.create(vertx)
        .baseUrl("https://api.example.com")
        .webClientOptions(new WebClientOptions().setFollowRedirects(true))
        .egress(EgressPolicy.allowHosts(List.of("api.example.com", "*.cdn.example.com"), false))
        .build();
```

### HttpClientConfigurationException

Extends `ConfigurationException`. Thrown by `build()` for an invalid name, base URL, read timeout,
negative body bound or default header.

### HttpExpectations

`HttpExpectations.success()` accepts 2xx responses and `HttpExpectations.status(IntPredicate)` accepts
the status codes the predicate accepts. Both are Vert.x `Expectation<HttpResponseHead>` values for
use inside an attempt:

```java
client.execute(web -> web.post("/items").sendBuffer(body).expecting(HttpExpectations.success()));
```

A refused response fails with `HttpStatusRejectedException`.

### Failures

Transport failures (connection refused, DNS, TLS handshake, reset, an empty or truncated response, a
pool-wait connect timeout) reach the caller unchanged as the Vert.x or JDK throwable; resilience
failures pass through unchanged. Only failures the client itself originates are typed, in
`dev.vertique.http.client.exception`. Every message is constant and carries no URI path, query,
header value, body text or cause message.

| Type | Extends | Thrown when | Extra accessors |
| --- | --- | --- | --- |
| `HttpClientTechnicalException` (abstract) | `TechnicalException` | Root of the technical failures | none |
| `HttpClientUnavailableException` (abstract) | `UnavailableException` | Root of the failures that mean the client cannot serve the call | none |
| `HttpClientValidationException` (abstract) | `ValidationException` | Root of the requests the client's policy refuses | none |
| `HttpClientConfigurationException` | `ConfigurationException` | A builder or configuration setting is invalid | none |
| `HttpEgressDeniedException` | `HttpClientValidationException` | The egress rules refused a target | `reason()`: `Reason` (`NO_HOST`, `USER_INFO`, `SCHEME`, `HOST`, `CONNECT_ADDRESS`, `VIRTUAL_HOST`, `PROXY`, `PLAIN_HTTP_CREDENTIALS`) |
| `HttpCredentialException` | `HttpClientTechnicalException` | The credential source failed or returned an invalid value | `causeType()`; `getCause()` is always null |
| `HttpBodyLimitException` | `HttpClientTechnicalException` | A byte bound was exceeded | `direction()`: `Direction` (`REQUEST`, `RESPONSE`, `EVENT`); `limitBytes()` |
| `HttpStatusRejectedException` | `HttpClientTechnicalException` | A status check refused the response | `statusCode()`, `headers()` (read-only, no `Set-Cookie`), `retryAfter()`, `bodyExcerpt()` (at most 2048 bytes) |
| `SseProtocolException` | `HttpClientTechnicalException` | Events were requested from a head that is not `text/event-stream` | `contentType()` (media type only) |
| `HttpExchangeCancelledException` | `HttpClientTechnicalException` | The call was cancelled | none |
| `HttpClientClosedException` | `HttpClientUnavailableException` | The client was closed before or during the call | none |

`HttpStatusRejectedException` implements `RetryDelayHint`: `retryAfter()` is the `Retry-After` header
as delta-seconds or an HTTP-date, zero for a past date, empty when absent or unparseable, and capped
at 24 hours. `bodyExcerpt()` is filled only when the checked response has a buffered `Buffer` body.

---

## Extension Points

Every public type in `dev.vertique.http.client.internal` (`FrameworkClientOptions`,
`BoundedBodyCodec`, `RetryAfter`, `EgressInterceptor`, `RedirectRules`) is INTERNAL: it exists for this module and other framework modules,
is outside the compatibility promise of this artifact, and application code must not use it.

---

## Dependencies

- `vertique-core` supplies the exception roots.
- `vertique-resilience` supplies the `Resilience` runtime accepted by the builder.
- Vert.x Web Client supplies `WebClient`, `HttpRequest`, `HttpResponse` and the HTTP agent. The
  module adds no other third-party artifact.
