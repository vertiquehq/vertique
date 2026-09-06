// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * <p>Captures the currently bound context values into a service-dispatch context map by iterating the
 * registered {@link ServiceDispatchContextEncoder encoders}.
 *
 * <p>Used by framework service client factories before constructing the outgoing
 * {@code DispatchEnvelope}. The resulting map is keyed by {@link ServiceDispatchContextEncoder#key}
 * and contains encoded (not necessarily typed) values safe for in-process event-bus dispatch.
 *
 * <p>Framework code MUST use this capturer rather than calling encoders directly to ensure
 * collision detection (FR-CTX-063) and null-encode validation (FR-CTX-050) are applied
 * consistently.
 */
@Singleton
public final class ServiceDispatchContextCapturer {

    private final ServiceDispatchContextRegistry registry;
    private final ContextHolder holder;

    /**
     * Constructs the capturer backed by the given registry and context holder.
     *
     * @param registry the service-dispatch context registry; must not be {@code null}
     * @param holder   the context holder used to read current bindings; must not be {@code null}
     */
    @Inject
    public ServiceDispatchContextCapturer(ServiceDispatchContextRegistry registry, ContextHolder holder) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.holder = Objects.requireNonNull(holder, "holder must not be null");
    }

    /**
     * Captures all currently bound context values by invoking registered encoders. Encoders whose
     * type is not currently bound are skipped. Returns an immutable map keyed by each encoder's
     * {@link ServiceDispatchContextEncoder#key()}.
     *
     * @param context the encode context identifying the dispatch boundary
     * @return an immutable map of encoded context values; never {@code null}
     * @throws IllegalStateException if any encoder returns {@code null} (FR-CTX-050)
     */
    public Map<String, Object> capture(ServiceDispatchEncodeContext context) {
        if (registry.encoders().isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new HashMap<>();
        for (ServiceDispatchContextEncoder<?> encoder : registry.encoders()) {
            captureOne(encoder, context, result);
        }
        return result.isEmpty() ? Map.of() : Map.copyOf(result);
    }

    /**
     * Merges captured context values into an existing caller-provided map. For each key produced by
     * capture: if the caller map already contains the key, an {@link IllegalStateException} is
     * thrown naming the colliding key and encoder type (FR-CTX-063). Otherwise the captured entry
     * is added.
     *
     * @param caller  the caller-provided map to merge into; must not be {@code null}
     * @param context the encode context identifying the dispatch boundary
     * @return an immutable merged map; never {@code null}
     * @throws IllegalStateException if any captured key already exists in {@code caller}
     *                               (FR-CTX-063) or if any encoder returns {@code null}
     *                               (FR-CTX-050)
     */
    public Map<String, Object> mergeCaptured(Map<String, Object> caller, ServiceDispatchEncodeContext context) {
        Objects.requireNonNull(caller, "caller must not be null");
        Map<String, Object> captured = capture(context);
        if (captured.isEmpty()) {
            return caller.isEmpty() ? Map.of() : Map.copyOf(caller);
        }
        if (caller.isEmpty()) {
            return captured;
        }
        Map<String, Object> merged = new HashMap<>(caller);
        for (Map.Entry<String, Object> entry : captured.entrySet()) {
            if (merged.containsKey(entry.getKey())) {
                throw new IllegalStateException(
                        "Service-dispatch context key collision: key '%s' is already present in caller map and was also produced by encoder %s"
                                .formatted(entry.getKey(), encoderClassNameForKey(entry.getKey())));
            }
            merged.put(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(merged);
    }

    private String encoderClassNameForKey(String key) {
        for (ServiceDispatchContextEncoder<?> e : registry.encoders()) {
            if (e.key().equals(key)) {
                return e.getClass().getName();
            }
        }
        return "<unknown encoder>";
    }

    // --- Internal helpers ---

    /**
     * Captures a single encoder's current context value into the result map. If the type is not
     * currently bound, the encoder is skipped. Validates that the encoder does not return
     * {@code null}.
     *
     * @param encoder the encoder to invoke
     * @param context the encode context
     * @param result  the map to accumulate results into
     * @param <T>     the context value type
     * @throws IllegalStateException if the encoder returns {@code null}
     */
    private <T extends ContextValue> void captureOne(
            ServiceDispatchContextEncoder<T> encoder,
            ServiceDispatchEncodeContext context,
            Map<String, Object> result) {
        Optional<T> current = holder.current(encoder.type());
        if (current.isEmpty()) {
            return;
        }
        Object encoded = encoder.encode(current.get(), context);
        if (encoded == null) {
            throw new IllegalStateException(
                    "ServiceDispatchContextEncoder '%s' returned null for type '%s' — null is not a valid encoded value (FR-CTX-050)"
                            .formatted(
                                    encoder.getClass().getName(), encoder.type().getName()));
        }
        result.put(encoder.key(), encoded);
    }
}
