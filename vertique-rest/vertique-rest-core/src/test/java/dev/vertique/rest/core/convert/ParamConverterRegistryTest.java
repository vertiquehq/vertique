// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.convert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParamConverterRegistry}.
 *
 * <p>Verifies built-in resolution (exact-class and enum-synthesis), application-binding override of
 * built-ins, duplicate-binding rejection at construction, and the empty result for unknown
 * non-enum types.
 */
class ParamConverterRegistryTest {

    // --- Test doubles ---

    /** A domain type with no built-in or enum-synth converter, used to prove empty resolution. */
    private record MyCustomDomainType(String value) {}

    /** A local enum used to exercise the {@code isEnum} synthesis rule. */
    private enum SomeTestEnum {
        ALPHA,
        BETA
    }

    /** A converter that tags its output so override behaviour is observable in assertions. */
    private static final class TaggingUuidConverter implements ParamConverter<UUID> {
        @Override
        public UUID fromString(String value) {
            return UUID.fromString(value);
        }

        @Override
        public String toString(UUID value) {
            return "app:" + value;
        }
    }

    /** A converter for the local enum, used to override the generic enum-synth rule. */
    private static final class TaggingEnumConverter implements ParamConverter<SomeTestEnum> {
        @Override
        public SomeTestEnum fromString(String value) {
            return SomeTestEnum.valueOf(value);
        }

        @Override
        public String toString(SomeTestEnum value) {
            return "app:" + value.name();
        }
    }

    // --- Exact-class built-in resolution ---

    @Test
    @DisplayName("find(UUID.class) resolves a built-in that round-trips a UUID")
    void exactClassLookupFindsBuiltinUuid() {
        ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

        Optional<ParamConverter<?>> found = registry.find(UUID.class);

        assertThat(found).isPresent();
        UUID id = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        ParamConverter<UUID> converter = (ParamConverter<UUID>) found.orElseThrow();
        assertThat(converter.fromString(id.toString())).isEqualTo(id);
    }

    @Test
    @DisplayName("find(Instant.class) resolves a built-in converter")
    void exactClassLookupFindsBuiltinInstant() {
        ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

        assertThat(registry.find(Instant.class)).isPresent();
    }

    // --- Application-binding override ---

    @Test
    @DisplayName("App ParamConverterBinding<UUID> overrides the built-in for UUID")
    void appBindingOverridesBuiltin() {
        TaggingUuidConverter appConverter = new TaggingUuidConverter();
        ParamConverterRegistry registry =
                ParamConverterRegistry.of(Set.of(new ParamConverterBinding<>(UUID.class, appConverter)));

        @SuppressWarnings("unchecked")
        ParamConverter<UUID> resolved =
                (ParamConverter<UUID>) registry.find(UUID.class).orElseThrow();

        assertThat(resolved).isSameAs(appConverter);
        assertThat(resolved.toString(UUID.randomUUID())).startsWith("app:");
    }

    // --- Duplicate binding rejection ---

    @Test
    @DisplayName("Two bindings for the same target type fail at of(...) with IllegalStateException")
    void duplicateSameTypeFails() {
        ParamConverter<String> first = new ParamConverter<>() {
            @Override
            public String fromString(String value) {
                return value;
            }

            @Override
            public String toString(String value) {
                return value;
            }
        };
        ParamConverter<String> second = new ParamConverter<>() {
            @Override
            public String fromString(String value) {
                return value.toUpperCase();
            }

            @Override
            public String toString(String value) {
                return value;
            }
        };
        Set<ParamConverterBinding<?>> bindings = Set.of(
                new ParamConverterBinding<>(String.class, first), new ParamConverterBinding<>(String.class, second));

        assertThatThrownBy(() -> ParamConverterRegistry.of(bindings)).isInstanceOf(IllegalStateException.class);
    }

    // --- Enum synthesis ---

    @Test
    @DisplayName("find(enumClass) activates the isEnum synthesis rule with no explicit binding")
    void enumSynthConverterActivated() {
        ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

        assertThat(registry.find(SomeTestEnum.class)).isPresent();
    }

    @Test
    @DisplayName("App binding for a specific enum overrides the generic enum-synth rule")
    void appBindingForSpecificEnumOverridesGenericRule() {
        TaggingEnumConverter appConverter = new TaggingEnumConverter();
        ParamConverterRegistry registry =
                ParamConverterRegistry.of(Set.of(new ParamConverterBinding<>(SomeTestEnum.class, appConverter)));

        assertThat(registry.find(SomeTestEnum.class).orElseThrow()).isSameAs(appConverter);
    }

    // --- Unknown type ---

    @Test
    @DisplayName("find(unregistered non-enum type) returns Optional.empty()")
    void unknownTypeReturnsEmpty() {
        ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

        assertThat(registry.find(MyCustomDomainType.class)).isEmpty();
    }

    // --- Sanity reference (keeps DayOfWeek import meaningful for enum-synth parity) ---

    @Test
    @DisplayName("Built-in enum-synth resolves a JDK enum (DayOfWeek)")
    void enumSynthResolvesJdkEnum() {
        ParamConverterRegistry registry = ParamConverterRegistry.of(Set.of());

        assertThat(registry.find(DayOfWeek.class)).isPresent();
    }
}
