// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.vault;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import dev.vertique.config.source.SecretDataFlattener;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Eagerly-loaded Vault KV v2 property source.
 *
 * <p>All configured paths are read from Vault at construction time and loaded into an
 * immutable in-memory map. {@link #lookup(String)} is a simple map get with no further I/O.
 *
 * <h2>Key Flattening</h2>
 * <p>The {@link VaultGateway} returns a flat {@code Map<String,String>} from the jopenlibs
 * driver's {@code getData()} call. KV v2 data keys are flat strings; a JSON-object string stored
 * as a value is preserved literally and is never re-interpreted. {@link SecretDataFlattener}
 * descends into nested {@code Map} structures when a gateway returns real nested maps (e.g. in
 * test stubs), but the production jopenlibs gateway always returns flat maps.
 *
 * <p>The optional per-path {@link VaultPathEntry#prefix()} is prepended to every key produced
 * by that path.
 *
 * <h2>Collision Resolution</h2>
 * <p>When the same flattened (prefixed) key is produced by more than one path, the <em>later</em>
 * path in the declaration order wins. This allows a more-specific path to override a
 * more-general one.
 *
 * <h2>Redaction</h2>
 * <p>This class never logs, prints, or includes resolved values in exception messages. Only
 * source names, path names, and key names appear in diagnostic output.
 *
 * @see VaultPropertySourceFactory
 */
class VaultPropertySource implements ConfigPropertySource {

    private static final Logger log = LoggerFactory.getLogger(VaultPropertySource.class);

    private final String name;
    private final Map<String, String> properties;

    // --- Construction ---

    /**
     * Constructs and eagerly loads the property source.
     *
     * <p>Called by {@link VaultPropertySourceFactory#create(String, io.vertx.core.json.JsonObject)}
     * after successful schema validation. The gateway is called once per declared path; results
     * are flattened, prefixed, and merged (later path wins on collision) into an immutable map.
     *
     * @param name    the source instance name (from the configuration entry)
     * @param gateway the Vault gateway to use for reading secrets
     * @param paths   the ordered list of paths to read
     * @throws ConfigPropertySourceException if any gateway call fails during eager load
     */
    VaultPropertySource(String name, VaultGateway gateway, List<VaultPathEntry> paths) {
        this.name = name;
        this.properties = Collections.unmodifiableMap(loadAllPaths(name, gateway, paths));
        log.info("Vault source '{}': {} keys loaded from {} path(s)", name, this.properties.size(), paths.size());
    }

    // --- ConfigPropertySource ---

    /** {@inheritDoc} */
    @Override
    public String name() {
        return name;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation returns from the in-memory map loaded at construction time. No
     * further I/O is performed.
     */
    @Override
    public Optional<String> lookup(String key) {
        return Optional.ofNullable(properties.get(key));
    }

    // --- Internal helpers ---

    /**
     * Reads all declared paths via the gateway and merges them into a single mutable map.
     *
     * <p>Later path entries win on key collision. Any gateway exception is wrapped in a
     * {@link ConfigPropertySourceException} that names the source and path but never includes
     * a resolved value.
     *
     * <p>The gateway is fail-closed: a 404 (path not found) or 403 (permission denied) response
     * causes a {@link VaultReadException} to be thrown, which is then wrapped here as a
     * {@link ConfigPropertySourceException}. The gateway exception's message already contains
     * the path exactly once; no additional prefix is added to avoid duplication.
     *
     * <p>Flattening of nested map structures is delegated to {@link SecretDataFlattener#flatten}.
     *
     * @param sourceName the source instance name for error messages
     * @param gateway    the gateway to read from
     * @param paths      ordered list of path entries
     * @return mutable merged map
     * @throws ConfigPropertySourceException if any path read fails
     */
    private static Map<String, String> loadAllPaths(
            String sourceName, VaultGateway gateway, List<VaultPathEntry> paths) {
        Map<String, String> merged = new HashMap<>();
        for (VaultPathEntry entry : paths) {
            try {
                Map<String, Object> raw = gateway.readPath(entry.path());
                merged.putAll(SecretDataFlattener.flatten(raw, entry.prefix()));
            } catch (VaultReadException e) {
                throw new ConfigPropertySourceException(sourceName, e.getMessage(), e);
            }
        }
        return merged;
    }
}
