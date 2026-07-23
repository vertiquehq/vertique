// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.ext.ParamConverterProvider;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParamConversionResolver}.
 *
 * <p>Verifies the full conversion chain: native-registry delegation, the JAX-RS
 * {@link ParamConverterProvider} bridge consulted only when the provider set is non-empty, lazy
 * materialization of the context's annotation supplier, {@code canResolve} for native and
 * provider-backed types, and the missing-converter failure carrying the context's diagnostics.
 */
class ParamConversionResolverTest {

    // --- Test doubles ---

    /** A domain type with no built-in/enum-synth converter and (by default) no provider. */
    private record UnknownType(String value) {}

    /** A domain type satisfied only by a registered JAX-RS provider in the relevant tests. */
    private record MyType(String value) {}

    /**
     * A JAX-RS {@link ParamConverterProvider} test double that records each {@code getConverter}
     * call and the annotations it was handed, returning a converter only for {@link MyType}.
     */
    private static final class RecordingProvider implements ParamConverterProvider {
        private final AtomicInteger getConverterCalls = new AtomicInteger();
        private Annotation[] lastAnnotations;

        @Override
        @SuppressWarnings("unchecked")
        public <T> jakarta.ws.rs.ext.ParamConverter<T> getConverter(
                Class<T> rawType, Type genericType, Annotation[] annotations) {
            getConverterCalls.incrementAndGet();
            this.lastAnnotations = annotations;
            if (rawType.equals(MyType.class)) {
                return (jakarta.ws.rs.ext.ParamConverter<T>) new MyTypeJaxRsConverter();
            }
            return null;
        }

        int getConverterCalls() {
            return getConverterCalls.get();
        }

        Annotation[] lastAnnotations() {
            return lastAnnotations;
        }
    }

    /** JAX-RS converter for {@link MyType}, wrapping/unwrapping the {@code value} component. */
    private static final class MyTypeJaxRsConverter implements jakarta.ws.rs.ext.ParamConverter<MyType> {
        @Override
        public MyType fromString(String value) {
            return new MyType(value);
        }

        @Override
        public String toString(MyType value) {
            return value.value();
        }
    }

    /**
     * A JAX-RS {@link ParamConverterProvider} test double whose converter (returned for {@link MyType})
     * throws a configured {@link RuntimeException} from {@code fromString} — exercising the resolver's
     * provider-path failure wrapping.
     */
    private static final class ThrowingProvider implements ParamConverterProvider {
        private final RuntimeException toThrow;

        ThrowingProvider(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> jakarta.ws.rs.ext.ParamConverter<T> getConverter(
                Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType.equals(MyType.class)) {
                return (jakarta.ws.rs.ext.ParamConverter<T>) new ThrowingConverter(toThrow);
            }
            return null;
        }
    }

    /** A JAX-RS converter whose {@code fromString} always throws the configured exception. */
    private static final class ThrowingConverter implements jakarta.ws.rs.ext.ParamConverter<MyType> {
        private final RuntimeException toThrow;

        ThrowingConverter(RuntimeException toThrow) {
            this.toThrow = toThrow;
        }

        @Override
        public MyType fromString(String value) {
            throw toThrow;
        }

        @Override
        public String toString(MyType value) {
            throw toThrow;
        }
    }

    // --- Context helpers ---

    /** A supplier that flips a flag (and records call count) when materialized. */
    private static final class RecordingSupplier implements Supplier<Annotation[]> {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Annotation[] get() {
            calls.incrementAndGet();
            return new Annotation[0];
        }

        int calls() {
            return calls.get();
        }
    }

    /** A supplier that throws if ever invoked — proves the lazy path is never touched. */
    private static Supplier<Annotation[]> throwingSupplier() {
        return () -> {
            throw new AssertionError("annotationsLazy supplier must not be invoked");
        };
    }

    private static ConversionContext ctx(String name, Class<?> rawType, Supplier<Annotation[]> annotations) {
        return new ConversionContext(name, ParamSource.QUERY, rawType, rawType, null, annotations);
    }

    private static ParamConversionResolver resolverWith(Set<ParamConverterProvider> providers) {
        return ParamConversionResolver.of(ParamConverterRegistry.of(Set.of()), providers);
    }

    // --- Native path ---

    @Test
    @DisplayName("fromString resolves a UUID via the native registry when no providers are present")
    void nativeRegistryResolvesUuid() {
        ParamConversionResolver resolver = resolverWith(Set.of());
        UUID id = UUID.randomUUID();

        Object result = resolver.fromString(id.toString(), ctx("id", UUID.class, throwingSupplier()));

        assertThat(result).isEqualTo(id);
    }

    @Test
    @DisplayName("toString delegates to the native registry, yielding the UUID canonical string")
    void resolverToStringDelegatesToRegistry() {
        ParamConversionResolver resolver = resolverWith(Set.of());
        UUID id = UUID.randomUUID();

        String result = resolver.toString(id, ctx("id", UUID.class, throwingSupplier()));

        assertThat(result).isEqualTo(id.toString());
    }

    // --- JAX-RS provider bridge ---

    @Test
    @DisplayName("Provider getConverter is never called when the provider set is empty")
    void jaxrsProviderConsultedOnlyWhenProviderSetNonEmpty() {
        RecordingProvider provider = new RecordingProvider();
        // Provider exists but is NOT registered with the resolver (empty set).
        ParamConversionResolver resolver = resolverWith(Set.of());

        assertThatThrownBy(() -> resolver.fromString("x", ctx("p", UnknownType.class, throwingSupplier())))
                .isInstanceOf(ParamConverterNotFoundException.class);
        assertThat(provider.getConverterCalls()).isZero();
    }

    @Test
    @DisplayName("Provider getConverter is invoked and its converter used when the native registry misses")
    void jaxrsProviderConsultedWhenNativeRegistryMisses() {
        RecordingProvider provider = new RecordingProvider();
        ParamConversionResolver resolver = resolverWith(Set.of(provider));

        Object result = resolver.fromString("hello", ctx("p", MyType.class, new RecordingSupplier()));

        assertThat(provider.getConverterCalls()).isPositive();
        assertThat(result).isEqualTo(new MyType("hello"));
    }

    // --- Lazy annotations ---

    @Test
    @DisplayName("annotationsLazy supplier is never materialized when no provider is registered")
    void annotationsNotMaterializedWhenNoProvider() {
        ParamConversionResolver resolver = resolverWith(Set.of());

        // throwingSupplier would blow up if touched; UUID resolves natively.
        Object result = resolver.fromString(UUID.randomUUID().toString(), ctx("id", UUID.class, throwingSupplier()));

        assertThat(result).isInstanceOf(UUID.class);
    }

    @Test
    @DisplayName("annotationsLazy supplier is materialized and passed to getConverter when a provider is present")
    void annotationsMaterializedWhenProviderPresent() {
        RecordingProvider provider = new RecordingProvider();
        RecordingSupplier supplier = new RecordingSupplier();
        ParamConversionResolver resolver = resolverWith(Set.of(provider));

        resolver.fromString("hi", ctx("p", MyType.class, supplier));

        assertThat(supplier.calls()).isEqualTo(1);
        assertThat(provider.lastAnnotations()).isNotNull();
    }

    // --- canResolve ---

    @Test
    @DisplayName("canResolve is true for a native built-in type")
    void canResolveReturnsTrueForNativeType() {
        ParamConversionResolver resolver = resolverWith(Set.of());

        assertThat(resolver.canResolve(ctx("id", UUID.class, throwingSupplier())))
                .isTrue();
    }

    @Test
    @DisplayName("canResolve is true for a provider-backed type (full chain)")
    void canResolveReturnsTrueForProviderBackedType() {
        ParamConversionResolver resolver = resolverWith(Set.of(new RecordingProvider()));

        assertThat(resolver.canResolve(ctx("p", MyType.class, new RecordingSupplier())))
                .isTrue();
    }

    @Test
    @DisplayName("canResolve is false for an unknown type with no provider")
    void canResolveReturnsFalseForUnknownType() {
        ParamConversionResolver resolver = resolverWith(Set.of());

        assertThat(resolver.canResolve(ctx("p", UnknownType.class, throwingSupplier())))
                .isFalse();
    }

    // --- Missing converter ---

    // --- Conversion-failure context (FR-015-08a) ---

    @Test
    @DisplayName("Native parse failure is re-contextualized with the resolver's paramName/source/targetType")
    void nativeParseFailureCarriesContextFromResolver() {
        ParamConversionResolver resolver = resolverWith(Set.of());
        ConversionContext ctx =
                new ConversionContext("id", ParamSource.PATH, UUID.class, UUID.class, null, throwingSupplier());

        assertThatThrownBy(() -> resolver.fromString("not-a-uuid", ctx))
                .isInstanceOf(ParamConversionException.class)
                .satisfies(t -> {
                    ParamConversionException ex = (ParamConversionException) t;
                    assertThat(ex.paramName()).isEqualTo("id");
                    assertThat(ex.source()).isEqualTo(ParamSource.PATH);
                    assertThat(ex.targetType()).isEqualTo(UUID.class);
                    assertThat(ex.getCause()).isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName(
            "A provider converter throwing IllegalArgumentException is wrapped as ParamConversionException with context")
    void providerParseFailureWrappedAsParamConversion() {
        ParamConversionResolver resolver =
                resolverWith(Set.of(new ThrowingProvider(new IllegalArgumentException("boom"))));
        ConversionContext ctx = new ConversionContext(
                "p", ParamSource.HEADER, MyType.class, MyType.class, null, new RecordingSupplier());

        assertThatThrownBy(() -> resolver.fromString("x", ctx))
                .isInstanceOf(ParamConversionException.class)
                .satisfies(t -> {
                    ParamConversionException ex = (ParamConversionException) t;
                    assertThat(ex.paramName()).isEqualTo("p");
                    assertThat(ex.source()).isEqualTo(ParamSource.HEADER);
                    assertThat(ex.targetType()).isEqualTo(MyType.class);
                    assertThat(ex.getCause())
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("boom");
                });
    }

    @Test
    @DisplayName(
            "A provider converter throwing WebApplicationException is uniformly wrapped as ParamConversionException")
    void providerWebApplicationExceptionWrappedAsParamConversion() {
        jakarta.ws.rs.WebApplicationException wae = new jakarta.ws.rs.WebApplicationException("nope");
        ParamConversionResolver resolver = resolverWith(Set.of(new ThrowingProvider(wae)));
        ConversionContext ctx = new ConversionContext(
                "p", ParamSource.QUERY, MyType.class, MyType.class, null, new RecordingSupplier());

        assertThatThrownBy(() -> resolver.fromString("x", ctx))
                .isInstanceOf(ParamConversionException.class)
                .satisfies(t -> {
                    ParamConversionException ex = (ParamConversionException) t;
                    assertThat(ex.paramName()).isEqualTo("p");
                    assertThat(ex.source()).isEqualTo(ParamSource.QUERY);
                    assertThat(ex.targetType()).isEqualTo(MyType.class);
                    assertThat(ex.getCause()).isSameAs(wae);
                });
    }

    // --- Missing converter ---

    @Test
    @DisplayName("fromString for an unknown type with no provider throws carrying name + source + targetType")
    void missingConverterThrowsParamConverterNotFoundException() {
        ParamConversionResolver resolver = resolverWith(Set.of());
        ConversionContext ctx = new ConversionContext(
                "mystery", ParamSource.HEADER, UnknownType.class, UnknownType.class, null, throwingSupplier());

        assertThatThrownBy(() -> resolver.fromString("v", ctx))
                .isInstanceOf(ParamConverterNotFoundException.class)
                .satisfies(t -> {
                    ParamConverterNotFoundException ex = (ParamConverterNotFoundException) t;
                    assertThat(ex.paramName()).isEqualTo("mystery");
                    assertThat(ex.source()).isEqualTo(ParamSource.HEADER);
                    assertThat(ex.targetType()).isEqualTo(UnknownType.class);
                });
    }
}
