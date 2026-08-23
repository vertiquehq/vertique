# Developing Vertique Cache Redis

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-redis/src/main/resources/META-INF/vertique/module.md`

This module owns cache-specific Redis provider behavior while sharing connection infrastructure from `vertique-redis-core`.

## Source Map

- `dev.vertique.cache.redis` — Redis provider package root.

## Runtime or Build Flow

The provider consumes neutral cache contracts and the shared Redis infrastructure boundary. Redis commands, serialization, and cache lifecycle behavior are owned by later tasks.

## Load-Bearing Invariants

- Redis connection profile parsing and client lifecycle remain in `vertique-redis-core`.
- The provider must not make `vertique-cache-core` depend on Redis.

## Testing

Provider behavior is outside T001. The foundation proof selects this module through the cache family aggregator.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-redis -am test
```
