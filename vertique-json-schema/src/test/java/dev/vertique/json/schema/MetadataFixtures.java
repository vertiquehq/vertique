// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.hibernate.validator.constraints.Length;
import org.hibernate.validator.constraints.Range;
import org.hibernate.validator.constraints.URL;

/**
 * Fixtures for the {@link MetadataConstraintSource} coverage and name-join proofs, adapted from the
 * design's {@code bv-metadata} scratch probes ({@code NameJoinProbe}, {@code CoverageProbe},
 * {@code RenderParityProbe}).
 */
final class MetadataFixtures {

    private MetadataFixtures() {}

    // --- Name-join fixtures ---

    /** BV bean-name is "field"; the Jackson wire name is "wire". */
    static final class RenamedFieldDto {
        @JsonProperty("wire")
        @Size(min = 2, max = 5)
        public String field;
    }

    /** Getter carries a constraint and a Jackson rename that mismatches the BV bean name. */
    static final class RenamedGetterDto {
        private String value;

        @JsonProperty("displayValue")
        @Size(min = 1, max = 9)
        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }

    /** Record component constraint: joins by constructor + index, not by a "get"-prefixed accessor. */
    record RecordDto(@Size(min = 2, max = 5) String code) {}

    /** A Lombok {@code @Builder @Jacksonized @Getter} type; the builder method joins by field name. */
    @Builder
    @Jacksonized
    @Getter
    static class LombokBuilderDto {
        @Size(min = 2, max = 9)
        private String name;
    }

    /** A creator parameter with no {@code javac -parameters}: only {@code @JsonProperty} joins it. */
    static final class CreatorParamDto {
        private final int amountCents;

        @JsonCreator
        CreatorParamDto(@JsonProperty("amount_cents") @Max(10) int amountCents) {
            this.amountCents = amountCents;
        }

        public int getAmountCents() {
            return amountCents;
        }
    }

    // --- Coverage fixtures ---

    static final class ContainerDto {
        public List<@Positive Integer> scores;
    }

    abstract static class BaseWithConstraint {
        @NotNull
        public String baseField;
    }

    /** Inherits a constrained field from a superclass without redeclaring anything. */
    static final class InheritedFieldDto extends BaseWithConstraint {
        public String subField;
    }

    interface Named {
        @NotNull
        String getName();
    }

    /** Inherits a constrained getter from an implemented interface without redeclaring it. */
    static final class InheritedGetterDto implements Named {
        private String name;

        @Override
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    /** A composed constraint bundling {@code @Size} and {@code @Pattern} as its "leaves". */
    @Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = {})
    @Size(min = 2, max = 20)
    @Pattern(regexp = "[A-Za-z0-9]+")
    @interface Code {
        String message() default "invalid code";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    static final class ComposedDto {
        @Code
        public String code;
    }

    interface AdminGroup {}

    /**
     * A constraint declared with a non-default group, on a plain, reflectively visible field: the
     * always-active floor (the schema library's own Jakarta Validation module, group-blind by
     * construction — a schema generator has no Bean Validation group concept) renders it required
     * regardless, matching {@code main}; only the metadata supplement's own contribution is filtered
     * by group.
     */
    static final class NonDefaultGroupDto {
        @NotNull(groups = AdminGroup.class)
        public String secret;
    }

    /** No annotations: "label"'s constraints come entirely from an XML constraint mapping. */
    static class XmlMappedDto {
        public String label;
    }

    /**
     * No annotations: "label" is constrained purely by an XML mapping, in a non-Default group — a
     * shape the floor cannot see at all (no reflective annotation exists), so whether it renders
     * depends entirely on the metadata supplement's own group filter.
     */
    static class XmlMappedNonDefaultGroupDto {
        public String label;
    }

    /** vertiquehq/vertique-dev#606 shapes, plus a CASE_INSENSITIVE @Pattern. */
    static final class Sharp606Dto {
        @Range(min = 10, max = 20)
        public int range;

        @Length(min = 2, max = 8)
        public String length;

        @URL
        public String url;

        @Pattern(regexp = "^abc$", flags = Pattern.Flag.CASE_INSENSITIVE)
        public String caseInsensitivePattern;

        @DecimalMax(value = "9.5", inclusive = false)
        public java.math.BigDecimal exclusiveMax;
    }
}
