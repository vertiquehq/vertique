// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

/**
 * A single path entry from the {@code paths} array in a Vault source configuration.
 *
 * <p>Each entry declares one Vault path to read and an optional key prefix to apply to all keys
 * read from that path. This is a package-private transfer object parsed by
 * {@link VaultPropertySourceFactory} from the source configuration JSON.
 *
 * @param path   the logical Vault path to read (required, non-blank); users supply the path
 *               WITHOUT the {@code /data/} infix — the driver handles the KV v2 envelope
 *               transparently when {@code engineVersion(2)} is set on the
 *               {@link io.github.jopenlibs.vault.VaultConfig}
 * @param prefix the prefix prepended to all keys read from this path; may be empty string (no
 *               prefix) but never {@code null}
 */
record VaultPathEntry(String path, String prefix) {}
