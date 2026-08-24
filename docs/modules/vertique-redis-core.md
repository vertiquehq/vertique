# Developing Vertique Redis Core

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-redis-core/src/main/resources/META-INF/vertique/module.md`

This module owns shared Redis connection-profile and client-lifecycle infrastructure. Feature modules consume it; it must remain independent of cache-specific behavior. The application-scoped registry owns the shared Redis clients, while the application host owns `Vertx`.

## Source Map

- `dev.vertique.redis` — shared Redis infrastructure package root.
- `RedisConnectionModule` — parses the named profile section, binds the registry, and contributes its shutdown step.
- `RedisClientRegistry` — snapshots startup-resolved profiles, lazily creates one client per profile, and owns ordered/idempotent close.
- `RedisClientShutdownStep` — contributes Redis cleanup in lifecycle phase `INFRA` after same-phase consumers.

## Runtime or Build Flow

The reactor builds this core module before `vertique-cache-redis`, allowing multiple Redis-backed features to share one managed infrastructure artifact. `RedisConnectionModule` reads `redis.connections`, and the typed `RedisConnectionConfig` record validates profile identity, credential-free Redis endpoint syntax, TLS mode, connect timeout, pool size, and waiting limits. `RedisConnectionsConfig` preserves the validated profile list and rejects duplicate names before the application-scoped `RedisClientRegistry` consumes it. The registry retains an immutable startup snapshot, so credential changes are restart-only, and it creates a client only when a consumer requests a profile.

Vert.x Redis Client 5.1.6 single-command sends return `Future<Response>`. `Future.timeout(long, TimeUnit)` fences the returned future to the timeout boundary; no per-request cancellation is claimed. `RedisDeadline` provides non-blocking event-loop settlement, and late backend results are fenced and ignored after the returned future settles.

## Load-Bearing Invariants

- Redis client types remain behind this shared infrastructure boundary and are not introduced into provider-neutral cache contracts.
- Profile parsing and lifecycle ownership stay here; feature modules do not duplicate connection configuration or shutdown wiring.
- Profile validation failures use stable, secret-free diagnostics; endpoint query, fragment, and user-info credentials are rejected at the configuration boundary.
- `RedisClientRegistry` maps `endpoints`, TLS, connect timeout, pool size, pool waiting, username, and password to the corresponding Vert.x Redis Client 5.1.6 options; it does not perform synchronous readiness checks.
- Credentials are captured at startup. `RedisConnectionConfig.toString()` redacts `passwordSecret`, and later configuration mutation cannot rotate a live client's credentials.
- `RedisClientShutdownStep` is contributed as an `ApplicationShutdownStep` in `LifecyclePhase.INFRA` with the lowest same-phase priority, so reverse-order teardown closes shared clients after their consumers.
- Registry close follows validated profile order, returns one shared close future, and never closes the caller-owned `Vertx` instance.
- T006 owns profile records and parser validation. T007 owns startup credential capture, lazy client lifecycle, option mapping, and ordered shutdown; T009 owns Redis cache commands.

## Testing

Profile and lifecycle behavior are owned by T006/T007. The T007 lifecycle proof covers lazy creation, same-profile identity reuse, ordered/idempotent close, host-owned `Vertx` preservation, and secret redaction. T001 proof selects this module directly.

```text
./mvnw -ntp -pl vertique-redis/vertique-redis-core -am test
```

## Related ADRs

- D009: Shared Redis connection profiles — named profiles are the reuse boundary for Redis-backed features.
- D022: Shared Redis client Dagger ownership — shared infrastructure owns client bindings and lifecycle.
