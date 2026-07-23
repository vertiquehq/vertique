// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import java.util.List;

/**
 * Parsed and validated settings for a single Vault property source.
 *
 * <p>Bundles the gateway connection settings with the ordered list of path entries to read.
 * This is the complete picture of one {@code vault} source entry after schema parsing — the
 * factory separates connection concerns (owned by {@link VaultConnectionSettings} and delegated
 * to the gateway) from path concerns (owned here and delegated to {@link VaultPropertySource}).
 *
 * <p>This record is package-private: it is an internal transfer object between
 * {@link VaultPropertySourceFactory} and the construction of the gateway and source.
 *
 * @param connection the gateway connection, authentication, and timeout parameters
 * @param paths      the ordered list of path entries to read (at least one)
 */
record VaultSourceSettings(VaultConnectionSettings connection, List<VaultPathEntry> paths) {}
