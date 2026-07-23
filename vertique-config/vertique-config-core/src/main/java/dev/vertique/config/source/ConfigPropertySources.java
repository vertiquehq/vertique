// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods for managing collections of {@link ConfigPropertySource} instances.
 *
 * <p>This class is not instantiable. All methods are static.
 *
 * @see ConfigPropertySource
 */
public final class ConfigPropertySources {

    private static final Logger log = LoggerFactory.getLogger(ConfigPropertySources.class);

    /** Private constructor — utility class; no instances. */
    private ConfigPropertySources() {}

    // --- Close utilities ---

    /**
     * Closes all sources in reverse declaration order, catching and logging any {@link Throwable}
     * thrown by each source's {@link ConfigPropertySource#close()} without rethrowing.
     *
     * <p>Reverse order matches the contract that sources instantiated later may depend on resources
     * held by sources instantiated earlier, so later-created sources must be torn down first.
     *
     * <p>If a source's {@code close()} throws, the error is logged at {@code ERROR} level with the
     * source name and the exception's simple class name only — no message text, no stack trace.
     * Third-party {@code close()} implementations own their own internal diagnostics: they may log
     * with their own redaction discipline before throwing. Echoing their exception message at ERROR
     * level here would allow arbitrary provider text (which may contain credentials, paths, or
     * other sensitive material) to reach the framework's log stream. Iteration continues to the
     * next source regardless. No exception — including {@link Error} — is ever rethrown; a close
     * failure must never mask a load failure or prevent other sources from being released.
     *
     * @param sources the ordered list of sources to close; must not be {@code null}; the list
     *                may be empty, in which case this method is a no-op
     */
    public static void closeAllReverse(List<ConfigPropertySource> sources) {
        for (int i = sources.size() - 1; i >= 0; i--) {
            ConfigPropertySource source = sources.get(i);
            try {
                source.close();
            } catch (Throwable t) {
                // ERROR: source name + exception class simple name only — no message text, no stack
                // trace. Third-party close() message text is untrusted and potentially log-unsafe;
                // the provider owns its own diagnostics and may log before throwing.
                log.error(
                        "Failed to close property source '{}': {}",
                        source.name(),
                        t.getClass().getSimpleName());
            }
        }
    }
}
