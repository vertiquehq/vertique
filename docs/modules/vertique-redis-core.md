# Developing Vertique Redis Core

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-redis-core/src/main/resources/META-INF/vertique/module.md`

This module owns shared Redis connection-profile and client-lifecycle infrastructure. Feature modules consume it; it must remain independent of cache-specific behavior.

## Source Map

- `dev.vertique.redis` — shared Redis infrastructure package root.

## Runtime or Build Flow

The reactor builds this core module before `vertique-cache-redis`, allowing multiple Redis-backed features to share one managed infrastructure artifact. The typed `RedisConnectionConfig` record validates profile identity, credential-free Redis endpoint syntax, TLS mode, connect timeout, pool size, and waiting limits. `RedisConnectionsConfig` preserves the validated profile list and rejects duplicate names before the client registry consumes it.

## Load-Bearing Invariants

- Redis client types remain behind this shared infrastructure boundary and are not introduced into provider-neutral cache contracts.
- Profile parsing and lifecycle ownership stay here; feature modules do not duplicate connection configuration or shutdown wiring.
- Profile validation failures use stable, secret-free diagnostics; endpoint query, fragment, and user-info credentials are rejected at the configuration boundary.
- T006 owns profile records and parser validation. T007 owns credential resolution, lazy client lifecycle, and ordered shutdown; T009 owns Redis cache commands.

## Testing

Profile and lifecycle behavior are owned by T006/T007. T001 proof selects this module directly.

```text
./mvnw -ntp -pl vertique-redis/vertique-redis-core -am test
```

## Related ADRs

- D009: Shared Redis connection profiles — named profiles are the reuse boundary for Redis-backed features.
- D022: Shared Redis client Dagger ownership — shared infrastructure owns client bindings and lifecycle.
