// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.context;

import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextDecodeWarning;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.ServiceDispatchContextDecoder;
import dev.vertique.core.context.ServiceDispatchContextEncoder;
import dev.vertique.core.context.ServiceDispatchDecodeContext;
import dev.vertique.core.context.ServiceDispatchEncodeContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * INTERNAL framework seam — consumed by sibling framework modules; not an application contract and
 * outside the maturity promise. Applications program against the SPIs in
 * {@code dev.vertique.core.context} and receive this runtime through the framework's Dagger wiring.
 *
 * Static factory methods for common {@link ServiceDispatchContextEncoder} and
 * {@link ServiceDispatchContextDecoder} implementation patterns.
 *
 * <p>Two patterns are provided:
 * <ul>
 *   <li><b>Snapshot</b> — the encoder applies a snapshot function to produce an immutable wire
 *       form; the decoder reconstructs the live value from that snapshot.
 *   <li><b>Pass-through</b> — the value is placed on the wire unchanged (safe for immutable
 *       types); the decoder type-checks and casts.
 * </ul>
 *
 * <p>Null return from a snapshot or restore function always throws {@link NullPointerException}
 * per FR-CTX-050 (no null returns from encode, no null returns from decode-restore).
 */
public final class ServiceDispatchCodecs {

    private ServiceDispatchCodecs() {}

    // --- Snapshot encoder / decoder ---

    /**
     * Returns an encoder that applies {@code snapshotFn} to the live value and emits the result as
     * the wire value. A null return from {@code snapshotFn} throws {@link NullPointerException} at
     * encode time per FR-CTX-050.
     *
     * @param type       the context value type this encoder handles; must not be {@code null}
     * @param snapshotFn the function that produces an immutable snapshot from the live value; must
     *                   not be {@code null}; must not return {@code null}
     * @param <T>        the context value type
     * @return the encoder; never {@code null}
     */
    public static <T extends ContextValue> ServiceDispatchContextEncoder<T> snapshotEncoder(
            Class<T> type, Function<T, ?> snapshotFn) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(snapshotFn, "snapshotFn must not be null");
        return new SnapshotEncoder<>(type, snapshotFn);
    }

    /**
     * Returns a decoder that accepts the snapshot type and rebuilds the live value via
     * {@code restoreFn}. A null carrier value returns {@link ContextDecodeResult#empty()}; a value
     * that is not an instance of {@code snapshotType} returns a
     * {@link ContextDecodeResult#failure(List) failure} result with a descriptive warning. A null
     * return from {@code restoreFn} throws {@link NullPointerException}.
     *
     * @param type         the context value type this decoder produces; must not be {@code null}
     * @param snapshotType the expected wire type of the snapshot; must not be {@code null}
     * @param restoreFn    the function that rebuilds a live value from the snapshot; must not be
     *                     {@code null}; must not return {@code null}
     * @param <T>          the context value type
     * @param <S>          the snapshot wire type
     * @return the decoder; never {@code null}
     */
    public static <T extends ContextValue, S> ServiceDispatchContextDecoder<T> snapshotDecoder(
            Class<T> type, Class<S> snapshotType, Function<S, T> restoreFn) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(snapshotType, "snapshotType must not be null");
        Objects.requireNonNull(restoreFn, "restoreFn must not be null");
        return new SnapshotDecoder<>(type, snapshotType, restoreFn);
    }

    // --- Pass-through encoder / decoder ---

    /**
     * Returns an encoder that emits the live value as the wire value unchanged. Suitable only for
     * immutable values that are safe to share by reference across dispatch boundaries.
     *
     * @param type the context value type this encoder handles; must not be {@code null}
     * @param <T>  the context value type
     * @return the encoder; never {@code null}
     */
    public static <T extends ContextValue> ServiceDispatchContextEncoder<T> passThroughEncoder(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return new PassThroughEncoder<>(type);
    }

    /**
     * Returns a decoder that type-checks the raw carrier value and casts it to {@code type}. A null
     * carrier value returns {@link ContextDecodeResult#empty()}; a value that is not an instance of
     * {@code type} returns a {@link ContextDecodeResult#failure(List) failure} with a descriptive
     * warning.
     *
     * @param type the context value type this decoder produces; must not be {@code null}
     * @param <T>  the context value type
     * @return the decoder; never {@code null}
     */
    public static <T extends ContextValue> ServiceDispatchContextDecoder<T> passThroughDecoder(Class<T> type) {
        Objects.requireNonNull(type, "type must not be null");
        return new PassThroughDecoder<>(type);
    }

    // --- Implementations ---

    /** Snapshot encoder implementation. */
    private static final class SnapshotEncoder<T extends ContextValue> implements ServiceDispatchContextEncoder<T> {

        private final Class<T> type;
        private final Function<T, ?> snapshotFn;

        SnapshotEncoder(Class<T> type, Function<T, ?> snapshotFn) {
            this.type = type;
            this.snapshotFn = snapshotFn;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public Object encode(T value, ServiceDispatchEncodeContext context) {
            Object snapshot = snapshotFn.apply(value);
            return Objects.requireNonNull(snapshot, "snapshotFn must not return null (FR-CTX-050)");
        }
    }

    /** Snapshot decoder implementation. */
    private static final class SnapshotDecoder<T extends ContextValue, S> implements ServiceDispatchContextDecoder<T> {

        private final Class<T> type;
        private final Class<S> snapshotType;
        private final Function<S, T> restoreFn;

        SnapshotDecoder(Class<T> type, Class<S> snapshotType, Function<S, T> restoreFn) {
            this.type = type;
            this.snapshotType = snapshotType;
            this.restoreFn = restoreFn;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public ContextDecodeResult<T> decode(Object value, ServiceDispatchDecodeContext context) {
            if (value == null) {
                return ContextDecodeResult.empty();
            }
            if (!snapshotType.isInstance(value)) {
                String reason = "Expected " + snapshotType.getName() + " but got "
                        + value.getClass().getName();
                return ContextDecodeResult.failure(
                        List.of(new ContextDecodeWarning(type.getName(), value.toString(), reason)));
            }
            T restored = restoreFn.apply(snapshotType.cast(value));
            return ContextDecodeResult.of(Objects.requireNonNull(restored, "restoreFn must not return null"));
        }
    }

    /** Pass-through encoder implementation. */
    private static final class PassThroughEncoder<T extends ContextValue> implements ServiceDispatchContextEncoder<T> {

        private final Class<T> type;

        PassThroughEncoder(Class<T> type) {
            this.type = type;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public Object encode(T value, ServiceDispatchEncodeContext context) {
            return value;
        }
    }

    /** Pass-through decoder implementation. */
    private static final class PassThroughDecoder<T extends ContextValue> implements ServiceDispatchContextDecoder<T> {

        private final Class<T> type;

        PassThroughDecoder(Class<T> type) {
            this.type = type;
        }

        @Override
        public Class<T> type() {
            return type;
        }

        @Override
        public ContextDecodeResult<T> decode(Object value, ServiceDispatchDecodeContext context) {
            if (value == null) {
                return ContextDecodeResult.empty();
            }
            if (!type.isInstance(value)) {
                String reason = "Expected " + type.getName() + " but got "
                        + value.getClass().getName();
                return ContextDecodeResult.failure(
                        List.of(new ContextDecodeWarning(type.getName(), value.toString(), reason)));
            }
            return ContextDecodeResult.of(type.cast(value));
        }
    }
}
