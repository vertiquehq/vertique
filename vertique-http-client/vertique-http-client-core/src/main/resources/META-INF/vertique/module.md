<!--
SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
SPDX-License-Identifier: EUPL-1.2
-->

# HTTP Client Core

> **Status:** Stable
> **Package:** `dev.vertique.http.client`
> **Artifact:** `vertique-http-client-core`
> **Depends on:** `vertique-core`, `vertique-resilience`, Vert.x Web Client, Dagger

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
(`Direction.RESPONSE`) and resets the exchange. Nothing is buffered to enforce either bound. The body of a
`stream(...)` call and its `Content-Length` are exempt from `maxResponseBytes`: a stream is the path for
bodies of unbounded length.

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

**Credentials.** `credentials(CredentialSource)` supplies headers such as `Authorization` per exchange:
`resolve(URI)` runs once for every hop to the original origin, after the request is prepared, and each
returned header replaces the request's header of that name. The original origin is the base URL's origin
(without a base URL, the exchange's first hop), widened by `credentialOrigins(List)` entries of the form
`scheme://host[:port]`. The source is never called for a hop to another origin or after the exchange left
the original origin, and a request with a server address, a virtual host (including a caller-set `Host`
header) or a request-level proxy gets no credentials; on a hop to another origin only the redirect
safelist headers travel. With a source, plain HTTP to a non-loopback host fails with
`HttpEgressDeniedException` (`PLAIN_HTTP_CREDENTIALS`) before connecting, unless
`allowCredentialsOverPlainHttp(true)`. A failed, throwing or null source, a header name that is not a
token or is `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade` or `TE`, and a value
with a control character or leading or trailing whitespace fail the exchange with `HttpCredentialException`
and send nothing. Values never appear in a message, log or `toString`; `causeType()` names only the class
of a source failure.

**Interceptors.** `register(HttpClientInterceptor)` adds an interceptor to the clients a builder builds; they run in
`OrderedExtension` order whatever the registration order. `beforeRequest` runs once per exchange, before
credentials, as a fold: each interceptor receives the context the previous one returned, and a failed
future, a `null` future, a `null` result or a throw fails the exchange without sending. The client copies the final
headers and URI into the request, checks the headers again for CR, LF and NUL, and judges a changed URI by the egress
policy. `clientName`, `callId` and `method` of a returned context are ignored. `onAttemptCompleted(request,
response, completion)` runs exactly once per exchange on every terminal path once the exchange reached the client:
a completed response, a refused status, a bound exceeded, a timeout, a reset, a connection failure, a refused
redirect hop, a cancellation, and the refusals before sending (egress denial, credential failure, invalid header,
refused request body, failed `beforeRequest`). `request` is an immutable snapshot of the method, URI and headers
taken after `beforeRequest` and before credentials, so no credential is ever visible to an interceptor;
`response` is a framework-built head without `Set-Cookie`, or `null` when none arrived; `completion` carries the
status, the failure as the caller sees it, the call id, the attempt ordinal, the duration and the final target
(scheme, host and port only). A throw of any interceptor, including `LinkageError`, `AssertionError` and
`StackOverflowError`, is logged and swallowed and never changes the outcome or skips the next interceptor; a
`LinkageError` is logged at error level at most once every five minutes per interceptor class. Callbacks may run on
any thread and concurrently for different exchanges.

Stated limits of `onAttemptCompleted`: a call refused by an open circuit or a full bulkhead, a call on a closed
client and a call cancelled before its exchange began have no exchange and no notification; an exchange whose
`beforeRequest` ran is always completed, even when cancelled before a request exists. For a refusal before
`beforeRequest` (an invalid caller header, a refused request body) the snapshot is the request as prepared before
`beforeRequest`, and the header re-check after it follows the client's header validation, off only for framework
clients built through `FrameworkClientOptions`. A header
Vert.x itself rejects when it is set (`putHeader` with CR or LF in a value) throws inside the attempt before any
request exists, so it is not notified either. For a call run through `execute`, a response that completed is
reported when its attempt ends, so that a status expectation the attempt applied is known (`error` is then the
`HttpStatusRejectedException`); a request sent straight through `webClient()` reports its response when it completes
and cannot see an expectation the caller applies afterwards. Every attempt of a retried call reports its own exchanges
when that attempt ends, with the attempt's ordinal (1, 2, ...) and the call's id, so a retried 503 reports its
`HttpStatusRejectedException` before the next attempt starts. An attempt whose future never completes leaves its
completed exchanges unreported until its pipeline starts the next attempt or the call ends.

**Warning: `allowAll()` with redirects.** With the default policy and redirects followed, a server you
call can redirect the client to any host, including internal addresses, such as a cloud metadata
endpoint or a service on a private network. Give a client that follows redirects, or that calls servers
you do not control, an `allowHosts(...)` policy.

**Attempt scope.** `execute` calls the attempt function once per attempt (once without a resilience
pipeline, once per retry with one) with a fresh `WebClient` scoped to that attempt. When the future the
function returns settles, the attempt's timer fires, the call is cancelled or closed, or the pipeline calls the
function again because a prebuilt pipeline's own timer gave up on the attempt, every exchange the attempt
started that is still open is reset, and the scoped `WebClient` fails every later request with
`IllegalStateException` without contacting the server. Do everything the attempt needs, including reading and decoding the
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
| `resiliencePolicy(String)` | Name of the resilience policy every call runs in unless its options say otherwise; an undefined name is rejected by `build()`; default none, one attempt per call |
| `streamIdleTimeout(long, TimeUnit)` | Longest time a stream may go without a received body byte, not negative; default 60 seconds, `0` disables |
| `maxRequestBytes(long)` | Most request body bytes sent, not negative; default `0` for no bound |
| `maxResponseBytes(long)` | Most response body bytes read, not negative; default 10485760, `0` for no bound |
| `maxEventBytes(int)` | Most raw bytes of one server-sent event, above 0; default 1048576 |
| `egress(EgressPolicy)` | Targets the client may contact, checked before connecting and on every redirect hop; default `EgressPolicy.allowAll()` |
| `credentials(CredentialSource)` | Per-exchange credential headers, resolved for hops to the original origin only; see Credentials |
| `credentialOrigins(List<String>)` | Extra `scheme://host[:port]` origins credentials may go to, besides the base URL's origin |
| `register(HttpClientInterceptor)` | Adds an interceptor that prepares requests before credentials and is told how each exchange ended; see Interceptors |
| `allowCredentialsOverPlainHttp(boolean)` | Lets a credential source be used over plain HTTP to a non-loopback host; default `false` |
| `config(HttpClientConfig)` | External `httpClient.{name}` configuration: every key it sets wins over the matching setter above, applied at `build()`; `null` removes it |
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
runs an attempt function (with `CallOptions.defaults()` or the given options), `stream` opens a response
whose body is read incrementally (see Streams), and `close()` fails the calls
still running and the open streams with `HttpClientClosedException`, closes the policy pipelines and the agent, and resets requests still open. Closing again returns the same future.
A call started after `close()` fails with `HttpClientClosedException` without opening a connection and without
notifying interceptors.

### Streams

```java
Future<HttpStream> opened = client.stream(CallOptions.defaults(), web -> web.get("/events"), null);
opened.onSuccess(stream -> {
    stream.body().handler(chunk -> process(chunk));
    stream.completion().onComplete(ended -> log(ended));
});
```

`stream(options, request, body)` sends the request that `request` returns (created by the `WebClient` it is
given, with an optional buffered `body`) and completes its future as soon as a response head the call's
expectation accepts has arrived, before any body byte. The expectation is `CallOptions.expecting(...)`, by default
`HttpExpectations.success()`. A request created by another `WebClient`, or a null argument, fails the future with
`IllegalArgumentException`. A head the expectation refuses fails the attempt with the expectation's failure (an
`HttpStatusRejectedException` for the built-in `HttpExpectations`, carrying at most 2048 bytes of the first body
chunk) and resets the exchange; the expectation sees an immutable copy of the head; a 429 or 503 is retried like any
rejection, honoring `Retry-After` when the policy sets `retryAfterMaxMs`.

`HttpStream` carries the immutable `head()` (status, message, version, headers), the `body()` `ReadStream`, the
server-sent `events()` and `lastEventId()` (see below), the terminal `completion()` and `cancel()`. The body stays paused until a handler is set; pausing it stops reading
from the connection once a small queue is full, so back-pressure reaches the sender. `completion()` succeeds
when the consumer has received the whole body and otherwise fails with the native throwable of the failed
exchange, a `java.util.concurrent.TimeoutException` after `streamIdleTimeout` without a body byte (also while
the consumer has paused; the exchange is reset unless the body has already ended), `HttpExchangeCancelledException` after `cancel()` and
`HttpClientClosedException` when the client closes. `cancel()` is idempotent and callable from any thread.
Every ending reports the exchange to the client's interceptors exactly once, with the head as the response.
Once the head is accepted the call's `CancellationSignal` no longer covers the stream: use `HttpStream.cancel()`.
Only the request the function returns is the stream; any other request sent from the same `WebClient` is an
ordinary exchange with the normal response bounds.

#### Server-sent events

`stream.events()` parses the body as `text/event-stream` into Vert.x `SseEvent` values; read the body through
`events()` or `body()`, not both. Calling `events()` again returns the same stream.

| Member | Behavior |
|---|---|
| `events()` | A `ReadStream<SseEvent>` paused until a handler is set. The parser follows the WHATWG grammar incrementally in any chunking: LF, CRLF and CR line endings, one leading byte order mark, the fields `data`, `event`, `id` and `retry`, other fields ignored, multi-line `data` joined with `\n`, dispatch on a blank line, an `id` containing NUL ignored, a non-numeric or `int`-overflowing `retry` ignored (`retry()` is 0 when absent), no event without `data`, event name `message` by default. Each `SseEvent.id()` is the last event ID at its dispatch and persists to later events until an `id` field changes it |
| `lastEventId()` | The last event ID as it stood at the last event delivered to the consumer; an id of an event still waiting or never dispatched does not show, and it is empty before any delivered event carries an id. Pass it as `Last-Event-ID` when resuming |
| Back-pressure | Pausing `events()` pauses the response: at most the events of one received chunk are held, then the stream's small queue fills and the connection stops being read |
| Comments | Consumed silently, never surfaced; like any body byte they restart the stream idle timer |
| Not an event stream | `events()` on a head whose media type (without parameters, compared case-insensitively) is not `text/event-stream` fails `completion()` with `SseProtocolException`, whose `contentType()` is the media type only, and resets the exchange. `stream(...)` adds no `Accept` header: set `Accept: text/event-stream` on the request to ask for one |
| `maxEventBytes(int)` | Default 1048576, above 0 (checked at `build()`). Counts every raw byte since the last dispatched event, comments, unknown fields, blocks that dispatch nothing and the current partial line included; exactly the bound is allowed. Above it `completion()` fails with `HttpBodyLimitException` (direction `EVENT`, `limitBytes()` the bound) and the exchange is reset. The bound is per event, not per stream |
| End | `completion()` succeeds after every event has been delivered to the handler |

Vert.x's `BodyCodec.sseStream` is unbounded and mis-parses some input; use `events()` instead.

A call leaves the resilience pipeline at the accepted head: it is never retried afterwards, the circuit breaker
has recorded the head's outcome (a failure after the head does not count), the attempt timeout stops and only
`streamIdleTimeout` governs the body, so a stream may outlive the attempt timeout. A head that arrives after
the call was cancelled, timed out or closed is reset and never delivered.

**A bulkhead bounds how many heads are being obtained, not how many streams are open**: its permit is released
at the head. **A stream that is not consumed holds its connection** until it ends, fails, is cancelled or the
client closes, so cancel every `HttpStream` that is dropped. Open streams count against the connection pool.

### CallOptions

Immutable options for a single `execute` or `stream` call. `CallOptions.defaults()` selects the client's own
settings.

| Method | Effect |
|---|---|
| `readTimeout(long, TimeUnit)` | Attempt timeout of this call, above zero; replaces the client's `readTimeout` |
| `noReadTimeout()` | No attempt timer for this call; of the two timeout methods the last call wins |
| `expecting(Expectation<HttpResponseHead>)` | Decides whether a `stream` head is accepted; default `HttpExpectations.success()`; other calls ignore it |
| `cancellation(CancellationSignal)` | Binds the call to a signal a caller can cancel from any thread |
| `resilience(ResiliencePipeline)` | Runs the call in a prebuilt pipeline, used as given |
| `resiliencePolicy(String)` | Runs the call in a named resilience policy; the client builds its pipeline once |
| `noResilience()` | Runs the call as one attempt without a pipeline; of the three resilience methods the last call wins |

The attempt timeout is, in this order, the call's `readTimeout` or `noReadTimeout()`, the timeout of the
call's resilience policy, then the client's `readTimeout`. It covers the whole attempt including the response
body. When it fires, every exchange the attempt has open is reset. In a pipeline the client built from a policy the
attempt fails with a `ResilienceTimeoutException` that carries the pipeline's operation key and the effective
timeout, so the runtime reports `TimeoutTriggered` and a `retryOn` or `abortOn` entry naming that type matches;
otherwise (no resilience, a prebuilt pipeline) it fails with a `java.util.concurrent.TimeoutException` with a
constant message, and a prebuilt pipeline's own timeout concern, when it has one, turns that into its own
`ResilienceTimeoutException`. A request sent straight through `webClient()` is one attempt bounded by the client's `readTimeout` in the same way, resetting just that exchange. A request still waiting for a pooled connection when the attempt ends sends
nothing once the connection frees.

### Resilience

A call runs in one pipeline execution: the options' prebuilt pipeline, else the options' or the client's named
policy (`resilience.policies.<name>`), else a single attempt. The client builds a policy's pipeline once per policy,
without the policy's timeout concern, because its own attempt timer is the only timer. Each attempt calls the
function again with a new scoped `WebClient`, so requests, bodies and decoding are rebuilt every time.

The default retry classification of a policy's pipeline applies when the policy names no `retryOn`: every method
retries a connection failure before anything was written (`ConnectException`, `UnknownHostException`,
`NoRouteToHostException`, `SSLHandshakeException`) and a `HttpStatusRejectedException` with status 429 or 503; only an
attempt whose every exchange used GET, HEAD, OPTIONS, TRACE, PUT or DELETE retries a 502, a 504, the attempt timeout, an
`HttpClosedException` and a stream reset. A policy's `retryOn` and `abortOn` replace this, except that a cancelled
call, a closed client, a refusal before the first request was written (a failed credential source, an egress denial
on the first hop, an invalid request header, a refused request body) are never retried; the default classification
also declines a refusal on a redirect hop. `Retry-After` is honored only when the policy sets `retry.retryAfterMaxMs` above 0.

Callers always see the original failure. A refusal before the first request was written ends the execution without
retry and the breaker treats it like a cancelled one: it re-opens a half-open breaker and, like any success, resets the
failure count of a closed one. The circuit breaker counts every failure that reaches it except a 4xx
`HttpStatusRejectedException` other than 429, `HttpBodyLimitException` and a refusal on a redirect hop, which are
recorded as successes. Cancelling a call or closing the client ends the execution at once with
`HttpExchangeCancelledException` or `HttpClientClosedException`, whatever `retryOn` says, and starts no further
attempt. The policy's pipelines are owned by the client and closed with it.

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
negative body bound, default header or credential origin, when a configured client names a resilience policy that is
not defined, and when a policy is named but the client has no resilience runtime.

### HttpExpectations

`HttpExpectations.success()` accepts 2xx responses and `HttpExpectations.status(IntPredicate)` accepts
the status codes the predicate accepts. Both are Vert.x `Expectation<HttpResponseHead>` values for
use inside an attempt:

```java
client.execute(web -> web.post("/items").sendBuffer(body).expecting(HttpExpectations.success()));
```

A refused response fails with `HttpStatusRejectedException`. The same checks serve `CallOptions.expecting(...)`
for the head of a stream.

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

**`HttpClientInterceptor`** (package `dev.vertique.http.client.interceptor`) is the one extension point of the
client, ordered by `OrderedExtension` (`priority()` keeps its default). It is registered per builder with
`register(...)`, or for every client of an application by contributing it to the `Set<HttpClientInterceptor>`
multibinding (see Wiring); the REST client does not use it.

| Callback | When | Contract |
|---|---|---|
| `beforeRequest(HttpClientRequestContext)` | Once per exchange, before credentials, in order | Returns the context to send as a future; a failure fails the exchange without sending |
| `onAttemptCompleted(request, response, completion)` | Exactly once per exchange on every terminal path that reached the client | Never changes the outcome; exceptions, `LinkageError`, `AssertionError` and `StackOverflowError` are logged and swallowed |

The records are immutable framework facts: `HttpClientRequestContext` (method, URI, headers, client name, call id,
attributes; `with...` copies), `HttpClientResponseHead` (status, message, headers without `Set-Cookie`),
`HttpClientAttemptCompletion` (status, error, call id, attempt ordinal, duration, completion time, target) and
`HttpClientAttemptTarget` (scheme, host, port). Their `toString` prints no header value, path, query or body.

Every public type in `dev.vertique.http.client.internal` (`CredentialInterceptor`, `WebClientOptionsHardening`, `WebClientOptionsNormalizer`, `BoundedBodyCodec`,
`RetryAfter`, `EgressInterceptor`, `RedirectRules`, `FrameworkClientOptions`) is INTERNAL: it exists for this module and other framework modules,
is outside the compatibility promise of this artifact, and application code must not use it.

---

## Configuration

Named clients are configured under `httpClient.{name}.*`; `{name}` matches `[A-Za-z0-9._~-]{1,128}`. The
section is read at startup through the framework configuration parser. A key that is not set keeps the builder's
value, and below it the default: external configuration wins over builder setters over defaults. An unknown key,
a value of the wrong type or outside its bounds, a raw Vert.x duration name and a rejected default header fail
startup with an `HttpClientConfigurationException` whose constant message names the key path
`httpClient.<name>.<key>` and never shows the value.

| Key | Type | Default | Rule |
|---|---|---|---|
| `baseUrl` | string | none | As `baseUrl(...)`; checked when the client is built |
| `readTimeoutMs` | long | 30000 | Attempt timeout in milliseconds, above 0 |
| `pool.http1MaxSize`, `pool.http2MaxSize`, `pool.maxWaitQueueSize`, `pool.eventLoopSize`, `pool.cleanerPeriodMs`, `pool.maxLifetimeSeconds` | int | Vert.x defaults | The first two at least 1, the last two not negative |
| `webClient` | object | `{}` | Vert.x `WebClientOptions` pass-through, see below |
| `resiliencePolicy` | string | none | A `resilience.policies.<tier>` name; an undefined tier fails startup |
| `defaultHeaders` | object of string | `{}` | Rejects CR, LF, NUL, a `vertique-` prefix (case-insensitive) and the names `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Upgrade` and `TE`; rendered as `***` in logs |
| `egress.allowHosts` | string list | absent: every host | Exact host, `*.suffix` or `*`; an empty list is rejected |
| `egress.allowPlainHttp` | boolean | `false` | Read only together with `egress.allowHosts` |
| `allowCredentialsOverPlainHttp` | boolean | `false` | As `allowCredentialsOverPlainHttp(...)` |
| `credentialOrigins` | string list | the base URL's origin | `scheme://host[:port]` entries; an empty list is rejected |
| `maxRequestBytes` | long | 0 (no bound) | At least 0 |
| `maxResponseBytes` | long | 10485760 | At least 0, 0 for no bound |
| `maxEventBytes` | int | 1048576 | Above 0; the most raw bytes of one server-sent event read through `events()`; wins over `maxEventBytes(...)` |
| `streamIdleTimeoutMs` | long | 60000 | At least 0, 0 disables; the longest a `stream(...)` body may go without a received byte; wins over `streamIdleTimeout(...)` |

**The `webClient` bag** is merged over the builder's base options (the options of `webClientOptions(...)`, or the
default options that follow no redirects), so a bag without `followRedirects` keeps the base value. It takes the
`WebClientOptions` keys with seven explicit-unit duration keys: `connectTimeoutMs`, `idleTimeoutSeconds`,
`readIdleTimeoutSeconds`, `writeIdleTimeoutSeconds`, `keepAliveTimeoutSeconds`, `http2KeepAliveTimeoutSeconds` and
`sslHandshakeTimeoutSeconds`; the raw Vert.x names (`connectTimeout`, ...) are rejected. The bag is checked for
weakened TLS under any key or nesting: trusting all certificates, skipping host verification, a blank hostname
verification algorithm and a TLS protocol below 1.2 fail startup naming `httpClient.<name>.webClient`. The bag can
carry key material and proxy credentials, so it renders as `***` in logs.

**A misspelt `webClient` key is ignored.** Vert.x skips keys it does not know, so a misspelt option (for example
`connectTimeoutMz`) does not fail startup and does not take effect. Check spelling against the Vert.x
`WebClientOptions` documentation. Misspelt keys outside the bag fail startup as unknown keys.

A Java builder that is given insecure `WebClientOptions` still builds; it logs one constant `WARN` per `build()`.

## Wiring

Include `HttpClientModule` in the application component, next to the configuration parser module and a module
that provides `Vertx` and the `@VertxConfig JsonObject`. It includes the resilience module and provides:

- `HttpClientFactory`, a singleton: `client(name)` returns one governed client per name, configured from
  `httpClient.{name}` (a name without a section has every default); `builder(name)` returns a builder seeded
  from that section.
- `Set<HttpClientInterceptor>`, empty by default: contribute global interceptors with
  `@Provides @IntoSet HttpClientInterceptor`. They are seeded into every client and builder of the factory
  and run in `OrderedExtension` order with the ones a builder registers.
- the parsed `httpClient` index, and a startup validator that fails startup with
  `httpClient.<name>.resiliencePolicy` when a client names a `resilience.policies` tier that is not defined.
- the shutdown step that closes the factory's clients.

When the graph provides the `resilience.policies` registry (`ResiliencePoliciesModule`), the factory hands it to
every client; without it no tier is defined.

## Lifecycle

`close()` on a client fails running calls and open streams with `HttpClientClosedException`, closes the policy pipelines and the
agent, and returns the same future when repeated. Clients built by the factory, and by builders it hands out,
close when the application stops, in the `INFRA` shutdown phase; calling `client(name)` or `builder(name)`
afterwards fails with `HttpClientClosedException`.

---

## Dependencies

- `vertique-core` supplies the exception roots.
- `vertique-resilience` supplies the `Resilience` runtime accepted by the builder.
- Vert.x Web Client supplies `WebClient`, `HttpRequest`, `HttpResponse` and the HTTP agent.
- Dagger and `jakarta.inject` wire `HttpClientModule`; both already come with `vertique-resilience`. The
  module adds no other third-party artifact.
