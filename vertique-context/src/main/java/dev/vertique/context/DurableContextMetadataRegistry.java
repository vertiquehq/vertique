// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableMetadataHeaderCodec;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Validating registry for durable context metadata encoders and decoders.
 *
 * <p>Boot-time validation in the constructor enforces:
 * <ul>
 *   <li>No duplicate durable encoder types (FR-CTX-113).
 *   <li>No two durable encoders owning the same namespace (FR-CTX-113).
 *   <li>No duplicate durable decoder types (FR-CTX-114).
 *   <li>No two durable decoders owning the same namespace (FR-CTX-114).
 *   <li>Empty encoder and decoder sets are valid (FR-CTX-112).
 * </ul>
 *
 * <p>Encoder and decoder validation are independent: a type may be durable encoder-only
 * (FR-CTX-115).
 *
 * <p>{@link DurableContextPropagator} MUST inject this registry rather than raw multibinding sets
 * so validation happens once at application bootstrap.
 */
@Singleton
public final class DurableContextMetadataRegistry {

    private final Collection<DurableContextMetadataEncoder<?>> encoders;
    private final Collection<DurableContextMetadataDecoder<?>> decoders;

    /**
     * Constructs the registry, performing boot-time validation of the provided encoder and decoder
     * sets.
     *
     * @param encoders the registered durable metadata encoders; must not be {@code null}
     * @param decoders the registered durable metadata decoders; must not be {@code null}
     * @throws IllegalStateException if duplicate types or duplicate namespaces are detected
     */
    @Inject
    public DurableContextMetadataRegistry(
            Set<DurableContextMetadataEncoder<?>> encoders, Set<DurableContextMetadataDecoder<?>> decoders) {
        validateEncoders(encoders);
        validateDecoders(decoders);
        this.encoders = Set.copyOf(encoders);
        this.decoders = Set.copyOf(decoders);
    }

    // --- Accessors ---

    /**
     * Returns all registered durable metadata encoders.
     *
     * @return an immutable collection of encoders; never {@code null}
     */
    public Collection<DurableContextMetadataEncoder<?>> encoders() {
        return encoders;
    }

    /**
     * Returns all registered durable metadata decoders.
     *
     * @return an immutable collection of decoders; never {@code null}
     */
    public Collection<DurableContextMetadataDecoder<?>> decoders() {
        return decoders;
    }

    /**
     * Returns the union of all types known to this registry — every {@link DurableContextMetadataEncoder#type()}
     * and {@link DurableContextMetadataDecoder#type()}. Used by {@link DurableContextPropagator#bindFrom}
     * to compute the authoritative durable-type set so that a durable boundary's bind operation
     * authoritatively replaces every registered durable type (binding decoded values, clearing
     * absent ones) rather than overlaying onto whatever happened to be ambient.
     *
     * @return an immutable set of registered durable types; never {@code null}, may be empty
     */
    public Set<Class<?>> registeredTypes() {
        Set<Class<?>> types = new HashSet<>(encoders.size() + decoders.size());
        for (DurableContextMetadataEncoder<?> encoder : encoders) {
            types.add(encoder.type());
        }
        for (DurableContextMetadataDecoder<?> decoder : decoders) {
            types.add(decoder.type());
        }
        return Set.copyOf(types);
    }

    // --- Validation helpers ---

    /**
     * Validates that no two encoders share the same type or the same namespace.
     *
     * @param encoders the encoders to validate
     * @throws IllegalStateException if duplicate types or namespaces are found
     */
    private static void validateEncoders(Set<DurableContextMetadataEncoder<?>> encoders) {
        Map<Class<?>, DurableContextMetadataEncoder<?>> byType = new HashMap<>();
        Map<String, DurableContextMetadataEncoder<?>> byNamespace = new HashMap<>();
        for (DurableContextMetadataEncoder<?> encoder : encoders) {
            Class<?> type = encoder.type();
            DurableContextMetadataEncoder<?> existingByType = byType.put(type, encoder);
            if (existingByType != null) {
                throw new IllegalStateException("Duplicate durable encoder type '%s': registered by %s and %s"
                        .formatted(
                                type.getName(),
                                existingByType.getClass().getName(),
                                encoder.getClass().getName()));
            }
            requireValidNamespace(encoder.namespace(), encoder.getClass());
            DurableContextMetadataEncoder<?> existingByNamespace = byNamespace.put(encoder.namespace(), encoder);
            if (existingByNamespace != null) {
                throw new IllegalStateException("Duplicate durable encoder namespace '%s': used by %s and %s"
                        .formatted(
                                encoder.namespace(),
                                existingByNamespace.getClass().getName(),
                                encoder.getClass().getName()));
            }
        }
    }

    /**
     * Validates that no two decoders share the same type or the same namespace.
     *
     * @param decoders the decoders to validate
     * @throws IllegalStateException if duplicate types or namespaces are found
     */
    private static void validateDecoders(Set<DurableContextMetadataDecoder<?>> decoders) {
        Map<Class<?>, DurableContextMetadataDecoder<?>> byType = new HashMap<>();
        Map<String, DurableContextMetadataDecoder<?>> byNamespace = new HashMap<>();
        for (DurableContextMetadataDecoder<?> decoder : decoders) {
            Class<?> type = decoder.type();
            DurableContextMetadataDecoder<?> existingByType = byType.put(type, decoder);
            if (existingByType != null) {
                throw new IllegalStateException("Duplicate durable decoder type '%s': registered by %s and %s"
                        .formatted(
                                type.getName(),
                                existingByType.getClass().getName(),
                                decoder.getClass().getName()));
            }
            requireValidNamespace(decoder.namespace(), decoder.getClass());
            DurableContextMetadataDecoder<?> existingByNamespace = byNamespace.put(decoder.namespace(), decoder);
            if (existingByNamespace != null) {
                throw new IllegalStateException("Duplicate durable decoder namespace '%s': used by %s and %s"
                        .formatted(
                                decoder.namespace(),
                                existingByNamespace.getClass().getName(),
                                decoder.getClass().getName()));
            }
        }
    }

    /**
     * Validates a single durable namespace at boot: it must be non-null, non-blank, and must NOT
     * start with the reserved header prefix ({@link DurableMetadataHeaderCodec#RESERVED_PREFIX}).
     * The reserved prefix is applied only when projecting to a string-keyed carrier (Kafka), not
     * encoded into the namespace itself — a namespace carrying it would double-prefix or be silently
     * dropped on ingress, so it is rejected eagerly.
     *
     * @param namespace  the declared namespace
     * @param ownerClass the encoder/decoder class declaring it (for the error message)
     * @throws IllegalStateException if the namespace is null/blank or reserved-prefixed
     */
    private static void requireValidNamespace(String namespace, Class<?> ownerClass) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalStateException(
                    "Durable context codec %s declared a null or blank namespace".formatted(ownerClass.getName()));
        }
        if (namespace.startsWith(DurableMetadataHeaderCodec.RESERVED_PREFIX)) {
            throw new IllegalStateException(
                    "Durable context codec %s namespace '%s' must not start with the reserved header prefix '%s' — the prefix is applied at the boundary, not encoded into the namespace"
                            .formatted(ownerClass.getName(), namespace, DurableMetadataHeaderCodec.RESERVED_PREFIX));
        }
    }
}
