// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.unitb;

import dev.vertique.core.VertxConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit-mode JAX-RS application fixture, registered by {@code unitb}'s
 * {@link GeneratedJaxRsResourcesModule#publicApplicationRegistration}. Its {@code @Inject}
 * constructor takes the live {@code @VertxConfig JsonObject}, so its behavior is entirely
 * configuration-driven: {@link #getClasses()} normally returns the classes named (as FQCNs, in
 * order) by the {@link #CLASSES_CONFIG_KEY} configuration array, but {@link #MODE_CONFIG_KEY} can
 * switch it into one of the failure or laziness modes C-COMPOSE step 4 and step 6 exercise.
 */
@ApplicationPath("/api/public")
public class PublicApplication extends Application {

    /** Configuration array of FQCN strings selecting {@link #getClasses()}'s normal-mode result. */
    public static final String CLASSES_CONFIG_KEY = "test.public.classes";

    /** Configuration key selecting a {@link #getClasses()} failure or laziness mode. */
    public static final String MODE_CONFIG_KEY = "test.public.mode";

    /** {@link #MODE_CONFIG_KEY} value: {@link #getClasses()} throws {@link RuntimeException}. */
    public static final String MODE_THROW_RUNTIME_EXCEPTION = "throwRuntimeException";

    /** {@link #MODE_CONFIG_KEY} value: {@link #getClasses()} throws {@link NoClassDefFoundError}. */
    public static final String MODE_THROW_NO_CLASS_DEF_FOUND_ERROR = "throwNoClassDefFoundError";

    /** {@link #MODE_CONFIG_KEY} value: {@link #getClasses()} returns an empty set. */
    public static final String MODE_EMPTY = "empty";

    /**
     * {@link #MODE_CONFIG_KEY} value: {@link #getClasses()} throws {@link RuntimeException} with
     * {@link #SECRET_MESSAGE} (G-09) — the composer's own wrapping message must name the cause's
     * class, never echo this message.
     */
    public static final String MODE_THROW_SECRET_MESSAGE = "throwSecretMessage";

    /** The configuration-value-shaped message {@link #MODE_THROW_SECRET_MESSAGE} throws (G-09). */
    public static final String SECRET_MESSAGE = "secret-config-value-42";

    /** Number of {@link #getClasses()} calls; reset before every test via {@link #reset()}. */
    public static final AtomicInteger GET_CLASSES_CALLS = new AtomicInteger();

    private final JsonObject config;

    /**
     * Constructs the application, retaining the live configuration for {@link #getClasses()}.
     *
     * @param config the application configuration
     */
    @Inject
    public PublicApplication(@VertxConfig JsonObject config) {
        this.config = config;
    }

    /**
     * Returns the classes named by {@link #CLASSES_CONFIG_KEY}, in configuration order, unless
     * {@link #MODE_CONFIG_KEY} selects a failure or laziness mode.
     *
     * @return the configured classes, in a {@link LinkedHashSet} preserving configuration order
     * @throws RuntimeException     when {@link #MODE_CONFIG_KEY} is {@link #MODE_THROW_RUNTIME_EXCEPTION}
     * @throws NoClassDefFoundError when {@link #MODE_CONFIG_KEY} is
     *                              {@link #MODE_THROW_NO_CLASS_DEF_FOUND_ERROR}
     */
    @Override
    public Set<Class<?>> getClasses() {
        GET_CLASSES_CALLS.incrementAndGet();
        String mode = resolve(MODE_CONFIG_KEY) instanceof String value ? value : null;
        if (MODE_THROW_RUNTIME_EXCEPTION.equals(mode)) {
            throw new RuntimeException("PublicApplication.getClasses() configured to throw RuntimeException");
        }
        if (MODE_THROW_NO_CLASS_DEF_FOUND_ERROR.equals(mode)) {
            throw new NoClassDefFoundError("PublicApplication.getClasses() configured to throw NoClassDefFoundError");
        }
        if (MODE_THROW_SECRET_MESSAGE.equals(mode)) {
            throw new RuntimeException(SECRET_MESSAGE);
        }
        if (MODE_EMPTY.equals(mode)) {
            return Set.of();
        }
        Set<Class<?>> classes = new LinkedHashSet<>();
        JsonArray configured = resolve(CLASSES_CONFIG_KEY) instanceof JsonArray array ? array : new JsonArray();
        for (Object fqcn : configured) {
            try {
                classes.add(Class.forName((String) fqcn));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("unknown fixture class '" + fqcn + "'", e);
            }
        }
        return classes;
    }

    /**
     * Resolves a dotted key against the nested configuration tree, the way the framework's property
     * conditions read it ({@code test.public.mode} is {@code {"test":{"public":{"mode":…}}}}).
     *
     * @param dottedKey the dotted configuration key
     * @return the value at that path, or {@code null} when any segment is absent
     */
    private Object resolve(String dottedKey) {
        Object current = config;
        for (String segment : dottedKey.split("\\.")) {
            if (!(current instanceof JsonObject object)) {
                return null;
            }
            current = object.getValue(segment);
        }
        return current;
    }

    /** Resets {@link #GET_CLASSES_CALLS} to {@code 0}. */
    public static void reset() {
        GET_CLASSES_CALLS.set(0);
    }
}
