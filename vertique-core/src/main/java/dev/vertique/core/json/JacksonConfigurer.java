// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.extension.OrderedExtension;
import io.vertx.core.json.jackson.DatabindCodec;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies all {@link ObjectMapperCustomizer} instances to the Vert.x
 * {@link DatabindCodec#mapper() ObjectMapper} at application startup.
 *
 * <p>Applications built on the framework lifecycle runner never call {@link #configure()}
 * directly: {@link dev.vertique.core.lifecycle.JacksonConfigureStep} contributes it as a
 * {@code CONFIGURE}-phase startup step, so it always runs before any verticle deploys. Call
 * {@link #configure()} directly only from a host that does not drive
 * {@code VertiqueApplicationBootstrap.start(runtime, factory)}.
 *
 * <p>The configurer is idempotent — subsequent calls log a warning and return immediately.
 */
@Singleton
public class JacksonConfigurer {

    private static final Logger log = LoggerFactory.getLogger(JacksonConfigurer.class);

    private final Set<ObjectMapperCustomizer> customizers;
    private final AtomicBoolean configured = new AtomicBoolean(false);

    /** Constructs a configurer with the given set of customizers. */
    @Inject
    JacksonConfigurer(Set<ObjectMapperCustomizer> customizers) {
        this.customizers = customizers;
    }

    /**
     * Applies all registered customizers to the Vert.x {@link DatabindCodec#mapper()}.
     * Safe to call multiple times — only the first invocation takes effect.
     */
    public void configure() {
        configure(DatabindCodec.mapper());
    }

    /**
     * Applies all registered customizers to the given mapper. Package-private for testing.
     *
     * @param mapper the ObjectMapper to configure
     */
    void configure(ObjectMapper mapper) {
        if (!configured.compareAndSet(false, true)) {
            log.warn("JacksonConfigurer.configure() called more than once; ignoring");
            return;
        }
        customizers.stream().sorted(OrderedExtension.comparator()).forEach(c -> c.customize(mapper));
    }
}
