// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.kafka.avro;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.specific.SpecificData;
import org.apache.avro.util.ClassSecurityValidator;
import org.apache.avro.util.ClassSecurityValidator.ClassSecurityPredicate;

/**
 * Extends Avro's JVM-wide class allow-list ({@link ClassSecurityValidator}, Avro 1.12.2+) with the
 * generated classes this module's serdes legitimately resolve.
 *
 * <p>Avro validates every class it resolves from a schema name (record, enum, fixed) against a single
 * static predicate, and generated {@code SpecificRecord} classes are not trusted by default. This
 * helper keeps one module-owned predicate and composes it <em>additively</em> onto whatever global
 * predicate is installed — it never replaces the global, so system-property trust
 * ({@code org.apache.avro.SERIALIZABLE_CLASSES}/{@code SERIALIZABLE_PACKAGES}) and any trust the
 * application installed itself keep working.
 *
 * <p>Trust is granted at two precisions, both explicit:
 * <ul>
 *   <li><strong>Exact classes</strong> derived from the payload types the application declares: the
 *       type itself plus every named schema (nested record, enum, fixed) reachable from its schema.</li>
 *   <li><strong>Package prefixes</strong> the application lists in
 *       {@value #TRUSTED_PACKAGES_KEY}, for the type-agnostic router path whose concrete types are not
 *       known when the serde is built. A prefix matches its subpackages; wildcards are rejected.</li>
 * </ul>
 *
 * <p>The effect is JVM-wide and additive: trust registered for one endpoint is visible to every Avro
 * resolution in the process. Registration happens when serdes are built (startup), never per record.
 */
final class AvroClassTrust {

    /** {@code serdeProperties} key listing extra trusted package prefixes (array or comma-separated string). */
    static final String TRUSTED_PACKAGES_KEY = "vertique.avro.trusted-packages";

    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+");

    private static final Set<String> TRUSTED_CLASSES = ConcurrentHashMap.newKeySet();
    private static final Set<String> TRUSTED_PACKAGE_PREFIXES = ConcurrentHashMap.newKeySet();
    private static final ClassSecurityPredicate PREDICATE = type -> isTrusted(type.getName());

    /** The composite last installed as Avro's global predicate; guarded by the class lock. */
    private static ClassSecurityPredicate installedGlobal;

    private AvroClassTrust() {}

    /**
     * Trusts {@code type} and the named schemas reachable from it, plus the configured package
     * prefixes, then makes sure the module predicate is part of Avro's global predicate.
     *
     * @param type the declared {@code SpecificRecord} payload type
     * @param endpointConfig the merged endpoint serde config (its {@code serdeProperties} may carry
     *     {@value #TRUSTED_PACKAGES_KEY})
     * @throws IllegalArgumentException if the configured package list is malformed or contains a wildcard
     */
    static void register(Class<?> type, JsonObject endpointConfig) {
        List<String> packages = configuredPackages(endpointConfig);
        Set<String> classes = new HashSet<>();
        classes.add(type.getName());
        Schema schema = schemaOf(type);
        if (schema != null) {
            collectNamedClasses(schema, classes, new HashSet<>());
        }
        install(classes, packages);
    }

    /**
     * Trusts only the configured package prefixes; used by the type-agnostic router deserializer.
     *
     * @param endpointConfig the merged endpoint serde config
     * @throws IllegalArgumentException if the configured package list is malformed or contains a wildcard
     */
    static void register(JsonObject endpointConfig) {
        install(Set.of(), configuredPackages(endpointConfig));
    }

    /**
     * Whether {@code className} is trusted by this module's predicate.
     *
     * @param className the binary class name, as returned by {@link Class#getName()}
     * @return {@code true} for a registered exact class or a class under a registered package prefix
     */
    static boolean isTrusted(String className) {
        if (TRUSTED_CLASSES.contains(className)) {
            return true;
        }
        for (String prefix : TRUSTED_PACKAGE_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void install(Collection<String> classes, Collection<String> packages) {
        TRUSTED_CLASSES.addAll(classes);
        for (String pkg : packages) {
            TRUSTED_PACKAGE_PREFIXES.add(pkg + ".");
        }
        ClassSecurityPredicate current = ClassSecurityValidator.getGlobal();
        // Re-compose if another component replaced the global since the last install.
        if (current != installedGlobal) {
            installedGlobal = ClassSecurityValidator.composite(current, PREDICATE);
            ClassSecurityValidator.setGlobal(installedGlobal);
        }
    }

    /** The class's own schema, or {@code null} for a hand-written record that exposes none (only the type is trusted). */
    private static Schema schemaOf(Class<?> type) {
        try {
            return SpecificData.get().getSchema(type);
        } catch (AvroRuntimeException e) {
            return null;
        }
    }

    private static void collectNamedClasses(Schema schema, Set<String> classes, Set<String> visited) {
        switch (schema.getType()) {
            case RECORD -> {
                if (visited.add(schema.getFullName())) {
                    classes.add(schema.getFullName());
                    schema.getFields().forEach(field -> collectNamedClasses(field.schema(), classes, visited));
                }
            }
            case ENUM, FIXED -> classes.add(schema.getFullName());
            case ARRAY -> collectNamedClasses(schema.getElementType(), classes, visited);
            case MAP -> collectNamedClasses(schema.getValueType(), classes, visited);
            case UNION -> schema.getTypes().forEach(branch -> collectNamedClasses(branch, classes, visited));
            default -> {
                // primitives carry no class to trust
            }
        }
    }

    private static List<String> configuredPackages(JsonObject endpointConfig) {
        JsonObject serdeProperties = endpointConfig.getJsonObject("serdeProperties");
        Object raw = serdeProperties == null ? null : serdeProperties.getValue(TRUSTED_PACKAGES_KEY);
        if (raw == null) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        if (raw instanceof String csv) {
            for (String entry : csv.split(",")) {
                entries.add(entry);
            }
        } else if (raw instanceof JsonArray array) {
            array.forEach(entry -> entries.add(String.valueOf(entry)));
        } else if (raw instanceof Iterable<?> iterable) {
            iterable.forEach(entry -> entries.add(String.valueOf(entry)));
        } else {
            throw new IllegalArgumentException(
                    TRUSTED_PACKAGES_KEY + " must be a string or an array of package names, but got "
                            + raw.getClass().getName());
        }
        List<String> packages = new ArrayList<>();
        for (String entry : entries) {
            String pkg = entry.trim();
            if (pkg.isEmpty()) {
                continue;
            }
            if (!PACKAGE_NAME.matcher(pkg).matches()) {
                throw new IllegalArgumentException(
                        TRUSTED_PACKAGES_KEY + " entry '" + pkg
                                + "' is not a package name of at least two segments; wildcards and single-segment roots are not accepted");
            }
            packages.add(pkg);
        }
        return packages;
    }
}
