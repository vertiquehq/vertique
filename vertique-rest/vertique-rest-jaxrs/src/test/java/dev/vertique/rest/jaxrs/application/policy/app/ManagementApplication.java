// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.application.policy.app;

import dev.vertique.core.VertxConfig;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * T005 TP-002/TP-004 application-mount fixture, registered by this package's
 * {@link GeneratedJaxRsResourcesModule#managementApplicationRegistration}. Mirrors
 * {@code application.unitb.PublicApplication}'s configuration-driven {@link #getClasses()}
 * shape (T002), narrowed to what T005 needs: no failure/laziness modes, just the FQCNs named by
 * {@link #CLASSES_CONFIG_KEY}, in configuration order.
 */
@ApplicationPath("/api/mgmt")
public class ManagementApplication extends Application {

    /** Configuration array of FQCN strings selecting {@link #getClasses()}'s result. */
    public static final String CLASSES_CONFIG_KEY = "policy.app.classes";

    private final JsonObject config;

    /**
     * Constructs the application, retaining the live configuration for {@link #getClasses()}.
     *
     * @param config the application configuration
     */
    @Inject
    public ManagementApplication(@VertxConfig JsonObject config) {
        this.config = config;
    }

    /**
     * Returns the classes named by {@link #CLASSES_CONFIG_KEY}, in configuration order.
     *
     * @return the configured classes, in a {@link LinkedHashSet} preserving configuration order
     */
    @Override
    public Set<Class<?>> getClasses() {
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
     * conditions read it ({@code policy.app.classes} is {@code {"policy":{"app":{"classes":…}}}}).
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
}
