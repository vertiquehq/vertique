// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import dev.vertique.config.source.ConfigPropertySource;
import dev.vertique.config.source.ConfigPropertySourceException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Map-backed {@link ConfigPropertySource} test double used in {@link PlaceholderResolverTest}.
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Records a per-key lookup invocation count so tests can assert memoization.</li>
 *   <li>Can be configured to throw {@link ConfigPropertySourceException} for a specific set of
 *       keys, simulating an unrecoverable source error.</li>
 *   <li>Name is configurable for diagnostic clarity.</li>
 * </ul>
 *
 * <h2>NFR-CONF-002</h2>
 * <p>The {@link ConfigPropertySourceException} messages thrown by this stub contain only the
 * source name and the key — never a resolved value.
 */
class StubPropertySource implements ConfigPropertySource {

    private final String name;
    private final Map<String, String> values;
    private final Set<String> throwKeys;
    private final Map<String, Integer> lookupCounts = new ConcurrentHashMap<>();

    /**
     * Constructs a stub source with the given name, value map, and set of keys that trigger an
     * error.
     *
     * @param name      the source name returned by {@link #name()}
     * @param values    the key→value map served by {@link #lookup(String)}
     * @param throwKeys keys for which {@link #lookup(String)} throws
     *                  {@link ConfigPropertySourceException} instead of returning a value
     */
    StubPropertySource(String name, Map<String, String> values, Set<String> throwKeys) {
        this.name = name;
        this.values = new HashMap<>(values);
        this.throwKeys = Set.copyOf(throwKeys);
    }

    /**
     * Constructs a stub source that never throws — all misses return {@link Optional#empty()}.
     *
     * @param name   the source name
     * @param values the key→value map
     */
    StubPropertySource(String name, Map<String, String> values) {
        this(name, values, Set.of());
    }

    /**
     * Constructs a stub source with a single key that always throws.
     *
     * @param name    the source name
     * @param values  the key→value map
     * @param throwKey the single key that triggers a throw
     */
    StubPropertySource(String name, Map<String, String> values, String throwKey) {
        this(name, values, Set.of(throwKey));
    }

    /**
     * {@inheritDoc}
     *
     * @return the configured source name
     */
    @Override
    public String name() {
        return name;
    }

    /**
     * Looks up the key in the configured value map.
     *
     * <p>Increments the lookup count for {@code key} on every invocation regardless of outcome.
     * If {@code key} is in the throw-keys set, throws {@link ConfigPropertySourceException}.
     * Otherwise returns the mapped value wrapped in {@link Optional}, or {@link Optional#empty()}
     * if not present.
     *
     * @param key the placeholder key to look up
     * @return the mapped value, or {@link Optional#empty()} if absent
     * @throws ConfigPropertySourceException if the key is in the configured throw-keys set
     */
    @Override
    public Optional<String> lookup(String key) {
        lookupCounts.merge(key, 1, Integer::sum);
        if (throwKeys.contains(key)) {
            throw new ConfigPropertySourceException(name, key, "simulated error in test");
        }
        return Optional.ofNullable(values.get(key));
    }

    /**
     * Returns the number of times {@link #lookup(String)} was invoked for the given key.
     *
     * @param key the key to query
     * @return invocation count; 0 if the key was never looked up
     */
    int lookupCount(String key) {
        return lookupCounts.getOrDefault(key, 0);
    }
}
