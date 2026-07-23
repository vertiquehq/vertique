// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * HashiCorp Vault KV v2 property source for bootstrap placeholder resolution.
 *
 * <h2>Overview</h2>
 * <p>This package provides a {@link dev.vertique.config.source.ConfigPropertySourceFactory}
 * implementation (type {@code "vault"}) that fetches secrets from HashiCorp Vault's KV v2 engine
 * during application bootstrap, before Dagger wiring. It implements the
 * {@link dev.vertique.config.source.ConfigPropertySource} SPI defined in the
 * {@code vertique-config} module.
 *
 * <h2>Eager Fetch Model</h2>
 * <p>All declared paths are read at factory {@code create()} time — before the source is returned
 * to the bootstrap engine. Any authentication or network failure at that point aborts startup
 * immediately with a {@link dev.vertique.config.source.ConfigPropertySourceException}. This
 * ensures missing credentials and unreachable Vault instances are surfaced at boot, not at first
 * placeholder lookup.
 *
 * <h2>Key Flattening</h2>
 * <p>The jopenlibs driver returns KV v2 data as a flat {@code Map<String,String>}: the keys are
 * exactly the keys stored in the secret, and each value is a string. A JSON-object string stored
 * as a secret value is preserved literally — it is never re-interpreted or further decomposed.
 * The dot-flattening logic in {@link dev.vertique.config.vault.VaultPropertySource} applies to
 * nested map structures that a gateway implementation may return (e.g. test stubs), not to
 * string values.
 *
 * <p>The optional {@code prefix} declared on each path entry is prepended to every key read from
 * that path, regardless of whether the key itself contains dots.
 *
 * <p>When the same prefixed key is produced by multiple declared paths, the later path wins.
 * This is documented on {@link dev.vertique.config.vault.VaultPropertySourceFactory}.
 *
 * <h2>Redaction Rule</h2>
 * <p>Resolved secret values MUST NEVER appear in log messages, exception messages, or any other
 * observable output. Only source names, path names, and structural/diagnostic detail strings are
 * safe to include. This module logs one INFO line per source after successful load (key count and
 * path count only — no values).
 *
 * <h2>ServiceLoader Registration</h2>
 * <p>The factory is registered in
 * {@code META-INF/services/dev.vertique.config.source.ConfigPropertySourceFactory} so that the
 * bootstrap engine discovers it automatically via {@link java.util.ServiceLoader}.
 */
package dev.vertique.config.vault;
