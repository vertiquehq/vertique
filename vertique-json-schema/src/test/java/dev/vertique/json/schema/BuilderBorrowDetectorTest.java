// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import jakarta.validation.constraints.Max;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link BuilderBorrowDetector} in isolation from schema generation. Detection is
 * entirely {@code java.lang.reflect} over Jackson's own runtime-visible annotations — {@code
 * @JsonDeserialize(builder = ...)} on the built type and {@code @JsonPOJOBuilder(withPrefix = "",
 * buildMethodName = "build")} on the builder, the exact shape {@code @Jacksonized} generates — so each
 * fixture here is built to violate (or satisfy) exactly one condition of that shape.
 */
class BuilderBorrowDetectorTest {

    // --- fixtures: a real Lombok builder ---

    @Builder
    @Jacksonized
    @Getter
    static final class LombokDto {
        @Max(10)
        private final int amount;
    }

    // --- fixtures: a hand-written builder that reproduces the Lombok shape exactly by hand ---

    @JsonDeserialize(builder = HandWrittenShapedDto.HandWrittenShapedDtoBuilder.class)
    static final class HandWrittenShapedDto {
        @Max(10)
        private final int amount;

        private HandWrittenShapedDto(int amount) {
            this.amount = amount;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        static final class HandWrittenShapedDtoBuilder {
            private int amount;

            HandWrittenShapedDtoBuilder amount(int amount) {
                this.amount = amount;
                return this;
            }

            HandWrittenShapedDto build() {
                return new HandWrittenShapedDto(amount);
            }
        }
    }

    // --- fixtures: hand-written builders that each violate exactly one shape condition ---

    /** Builder class named plainly, not {@code <Type>Builder}: fails the naming condition alone. */
    @JsonDeserialize(builder = WrongBuilderNameDto.Builder.class)
    static final class WrongBuilderNameDto {
        @Max(10)
        private final int amount;

        private WrongBuilderNameDto(int amount) {
            this.amount = amount;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        static final class Builder {
            private int amount;

            Builder amount(int amount) {
                this.amount = amount;
                return this;
            }

            WrongBuilderNameDto build() {
                return new WrongBuilderNameDto(amount);
            }
        }
    }

    /** No {@code @JsonPOJOBuilder} at all: fails that condition alone, even though everything else matches. */
    @JsonDeserialize(builder = NoPojoBuilderAnnotationDto.NoPojoBuilderAnnotationDtoBuilder.class)
    static final class NoPojoBuilderAnnotationDto {
        @Max(10)
        private final int amount;

        private NoPojoBuilderAnnotationDto(int amount) {
            this.amount = amount;
        }

        static final class NoPojoBuilderAnnotationDtoBuilder {
            private int amount;

            NoPojoBuilderAnnotationDtoBuilder amount(int amount) {
                this.amount = amount;
                return this;
            }

            NoPojoBuilderAnnotationDto build() {
                return new NoPojoBuilderAnnotationDto(amount);
            }
        }
    }

    /** Builder method name does not match the field name: fails the method-name condition alone. */
    @JsonDeserialize(builder = WrongMethodNameDto.WrongMethodNameDtoBuilder.class)
    static final class WrongMethodNameDto {
        @Max(10)
        private final int amount;

        private WrongMethodNameDto(int amount) {
            this.amount = amount;
        }

        @JsonPOJOBuilder(withPrefix = "", buildMethodName = "build")
        static final class WrongMethodNameDtoBuilder {
            private int amount;

            WrongMethodNameDtoBuilder putAmount(int amount) {
                this.amount = amount;
                return this;
            }

            WrongMethodNameDto build() {
                return new WrongMethodNameDto(amount);
            }
        }
    }

    private static Method builderSetter(Class<?> builderClass, String name) throws NoSuchMethodException {
        for (Method method : builderClass.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1) {
                return method;
            }
        }
        throw new NoSuchMethodException(builderClass.getName() + "." + name);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        return type.getDeclaredField(name);
    }

    @Test
    @DisplayName("a real Lombok builder's generated shape is recognized: @JsonDeserialize/@JsonPOJOBuilder plus a"
            + " matching build() and setter")
    void lombokBuilderShapeIsSound() throws Exception {
        Method setter = builderSetter(lombokBuilderClass(), "amount");
        Field field = field(LombokDto.class, "amount");

        assertTrue(
                BuilderBorrowDetector.isSoundBorrow(setter, LombokDto.class, field),
                "@Jacksonized emits @JsonDeserialize(builder = ...) and @JsonPOJOBuilder(withPrefix = \"\","
                        + " buildMethodName = \"build\") — both RUNTIME-retained — so the detector must"
                        + " recognize this shape entirely through java.lang.reflect");
    }

    @Test
    @DisplayName("a hand-written builder that reproduces the Lombok shape exactly is also recognized")
    void handWrittenShapeIsSoundWhenItMatchesExactly() throws Exception {
        Method setter = builderSetter(HandWrittenShapedDto.HandWrittenShapedDtoBuilder.class, "amount");
        Field field = field(HandWrittenShapedDto.class, "amount");

        assertTrue(
                BuilderBorrowDetector.isSoundBorrow(setter, HandWrittenShapedDto.class, field),
                "the owner ruling tolerates a hand-written builder resolving when it reproduces the shape"
                        + " exactly — it allows a hand-written builder to go unresolved, it never requires"
                        + " it");
    }

    @Test
    @DisplayName("a builder class not named in the <Type>Builder convention is refused")
    void wrongBuilderClassNameIsRefused() throws Exception {
        Method setter = builderSetter(WrongBuilderNameDto.Builder.class, "amount");
        Field field = field(WrongBuilderNameDto.class, "amount");

        assertFalse(
                BuilderBorrowDetector.isSoundBorrow(setter, WrongBuilderNameDto.class, field),
                "a builder class named plainly \"Builder\" rather than \"WrongBuilderNameDtoBuilder\" must"
                        + " not match the Lombok shape");
    }

    @Test
    @DisplayName("a builder class with no @JsonPOJOBuilder at all is refused")
    void missingPojoBuilderAnnotationIsRefused() throws Exception {
        Method setter = builderSetter(NoPojoBuilderAnnotationDto.NoPojoBuilderAnnotationDtoBuilder.class, "amount");
        Field field = field(NoPojoBuilderAnnotationDto.class, "amount");

        assertFalse(
                BuilderBorrowDetector.isSoundBorrow(setter, NoPojoBuilderAnnotationDto.class, field),
                "a builder carrying no @JsonPOJOBuilder at all must not match the Lombok shape, even"
                        + " though its class naming, build(), and setter all otherwise match");
    }

    @Test
    @DisplayName("a builder method whose name does not match the field's name is refused")
    void mismatchedMethodNameIsRefused() throws Exception {
        Method setter = builderSetter(WrongMethodNameDto.WrongMethodNameDtoBuilder.class, "putAmount");
        Field field = field(WrongMethodNameDto.class, "amount");

        assertFalse(
                BuilderBorrowDetector.isSoundBorrow(setter, WrongMethodNameDto.class, field),
                "a builder method named \"putAmount\" for a field named \"amount\" must not match the"
                        + " Lombok shape, even though the builder class itself is named conventionally and"
                        + " carries a matching @JsonPOJOBuilder");
    }

    private static Class<?> lombokBuilderClass() {
        for (Class<?> nested : LombokDto.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("LombokDtoBuilder")) {
                return nested;
            }
        }
        throw new IllegalStateException("Lombok did not generate LombokDtoBuilder for " + LombokDto.class);
    }
}
