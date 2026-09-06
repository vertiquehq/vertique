// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Validating registry for service-dispatch context encoders and decoders.
 *
 * <p>Boot-time validation in the constructor enforces:
 * <ul>
 *   <li>No duplicate encoder keys across all registered encoders (FR-CTX-045).
 *   <li>No duplicate decoder keys across all registered decoders (FR-CTX-045).
 *   <li>No duplicate encoder types (FR-CTX-046).
 *   <li>No duplicate decoder types (FR-CTX-046).
 *   <li>Empty encoder and decoder sets are valid (FR-CTX-044).
 * </ul>
 *
 * <p>Encoder and decoder validation are independent: a type may have only an encoder, only a
 * decoder, both, or neither (FR-CTX-047).
 *
 * <p>Framework code MUST inject this registry rather than raw multibinding sets so validation
 * happens once at application bootstrap and cannot be skipped by individual call sites.
 */
@Singleton
public final class ServiceDispatchContextRegistry {

    private final Collection<ServiceDispatchContextEncoder<?>> encoders;
    private final Collection<ServiceDispatchContextDecoder<?>> decoders;
    private final Map<Class<?>, ServiceDispatchContextDecoder<?>> decodersByType;

    /**
     * Constructs the registry, performing boot-time validation of the provided encoder and decoder
     * sets.
     *
     * @param encoders the registered service-dispatch encoders; must not be {@code null}
     * @param decoders the registered service-dispatch decoders; must not be {@code null}
     * @throws IllegalStateException if duplicate keys or duplicate types are detected
     */
    @Inject
    public ServiceDispatchContextRegistry(
            Set<ServiceDispatchContextEncoder<?>> encoders, Set<ServiceDispatchContextDecoder<?>> decoders) {
        validateEncoders(encoders);
        validateDecoders(decoders);
        this.encoders = Set.copyOf(encoders);
        this.decoders = Set.copyOf(decoders);
        Map<Class<?>, ServiceDispatchContextDecoder<?>> byType = new HashMap<>();
        for (ServiceDispatchContextDecoder<?> decoder : decoders) {
            byType.put(decoder.type(), decoder);
        }
        this.decodersByType = Map.copyOf(byType);
    }

    // --- Accessors ---

    /**
     * Returns all registered service-dispatch encoders.
     *
     * @return an immutable collection of encoders; never {@code null}
     */
    public Collection<ServiceDispatchContextEncoder<?>> encoders() {
        return encoders;
    }

    /**
     * Returns all registered service-dispatch decoders.
     *
     * @return an immutable collection of decoders; never {@code null}
     */
    public Collection<ServiceDispatchContextDecoder<?>> decoders() {
        return decoders;
    }

    /**
     * Returns the decoder for the given context type, if one is registered.
     *
     * @param type the context type to look up
     * @return the decoder for {@code type}, or empty if none is registered
     */
    public Optional<ServiceDispatchContextDecoder<?>> decoderForType(Class<?> type) {
        return Optional.ofNullable(decodersByType.get(type));
    }

    // --- Validation helpers ---

    /**
     * Validates that no two encoders share the same key or the same type.
     *
     * @param encoders the encoders to validate
     * @throws IllegalStateException if duplicates are found
     */
    private static void validateEncoders(Set<ServiceDispatchContextEncoder<?>> encoders) {
        Map<String, ServiceDispatchContextEncoder<?>> byKey = new HashMap<>();
        Map<Class<?>, ServiceDispatchContextEncoder<?>> byType = new HashMap<>();
        for (ServiceDispatchContextEncoder<?> encoder : encoders) {
            String key = encoder.key();
            ServiceDispatchContextEncoder<?> existing = byKey.put(key, encoder);
            if (existing != null) {
                throw new IllegalStateException("Duplicate service-dispatch encoder key '%s': registered by %s and %s"
                        .formatted(
                                key,
                                existing.getClass().getName(),
                                encoder.getClass().getName()));
            }
            Class<?> type = encoder.type();
            ServiceDispatchContextEncoder<?> existingByType = byType.put(type, encoder);
            if (existingByType != null) {
                throw new IllegalStateException("Duplicate service-dispatch encoder type '%s': registered by %s and %s"
                        .formatted(
                                type.getName(),
                                existingByType.getClass().getName(),
                                encoder.getClass().getName()));
            }
        }
    }

    /**
     * Validates that no two decoders share the same key or the same type.
     *
     * @param decoders the decoders to validate
     * @throws IllegalStateException if duplicates are found
     */
    private static void validateDecoders(Set<ServiceDispatchContextDecoder<?>> decoders) {
        Map<String, ServiceDispatchContextDecoder<?>> byKey = new HashMap<>();
        Map<Class<?>, ServiceDispatchContextDecoder<?>> byType = new HashMap<>();
        for (ServiceDispatchContextDecoder<?> decoder : decoders) {
            String key = decoder.key();
            ServiceDispatchContextDecoder<?> existing = byKey.put(key, decoder);
            if (existing != null) {
                throw new IllegalStateException("Duplicate service-dispatch decoder key '%s': registered by %s and %s"
                        .formatted(
                                key,
                                existing.getClass().getName(),
                                decoder.getClass().getName()));
            }
            Class<?> type = decoder.type();
            ServiceDispatchContextDecoder<?> existingByType = byType.put(type, decoder);
            if (existingByType != null) {
                throw new IllegalStateException("Duplicate service-dispatch decoder type '%s': registered by %s and %s"
                        .formatted(
                                type.getName(),
                                existingByType.getClass().getName(),
                                decoder.getClass().getName()));
            }
        }
    }
}
