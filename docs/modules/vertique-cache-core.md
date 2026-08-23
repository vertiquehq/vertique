# Developing Vertique Cache Core

> **Audience:** Vertique framework contributors and source agents
> **Public contract:** `vertique-cache-core/src/main/resources/META-INF/vertique/module.md`

This module owns the provider-neutral cache package boundary. Provider implementations must depend inward on this module and must not introduce provider types into its public contracts.

## Source Map

- `dev.vertique.cache` — cache core package root.

## Runtime or Build Flow

The module is selected before provider modules in the reactor and supplies the neutral dependency target for code generation and storage providers.

## Load-Bearing Invariants

- The core module must not depend on Caffeine, Redis, or provider serialization libraries.
- Aggregator POMs remain non-consumable and are not added to the BOM.

## Testing

T001 proof is the focused reactor-selection command. Public contract tests belong to T002 and runtime/provider tests belong to later tasks.

```text
./mvnw -ntp -pl vertique-cache/vertique-cache-core -am test
```

## Related ADRs

- D018: Public cache store SPI boundary — provider-neutral cache contracts remain separate from storage implementations.
