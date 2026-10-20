// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.core.validation.JakartaPathPointers;
import dev.vertique.core.validation.ParameterViolation;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Proves that the two mapper-only entry points of {@link JakartaPathPointers} agree on real method
 * validation output: the structured path a violation carries and the Java path string rendered from
 * it resolve to the same JSON pointer.
 *
 * <p>Violations come from the real validator over a real method signature; nothing is mocked. The
 * body type is a record, so every property binds through its creator and resolves without a document.
 */
class PointerOverloadParityTest {

    private static final String KEY_SENTINEL = "ZZKEY";

    private static ValidatorFactory factory;
    private static DefaultBeanValidator beanValidator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.byProvider(HibernateValidator.class).configure().buildValidatorFactory();
        beanValidator = new DefaultBeanValidator(
                factory.getValidator(), new ViolationDetailMapper(Set.of(), Set.of(), Set.of()));
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    // --- Fixtures ---

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = FlaggedValidator.class)
    public @interface Flagged {
        String message() default "flagged as invalid";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public interface FlagCarrier {
        boolean flagged();
    }

    public static class FlaggedValidator implements ConstraintValidator<Flagged, FlagCarrier> {
        @Override
        public boolean isValid(FlagCarrier value, ConstraintValidatorContext context) {
            return !value.flagged();
        }
    }

    /** A list or map element with a renamed property and a class-level constraint. */
    @Flagged
    public record Item(@NotBlank @JsonProperty("item_name") String name, boolean flagged) implements FlagCarrier {}

    /** The body: renamed fields, nested lists, a map, a set and a class-level constraint. */
    @Flagged
    public record Body(
            @NotBlank @JsonProperty("display_name") String displayName,
            List<@Valid Item> items,
            List<List<@NotBlank String>> lists,
            Map<@Size(max = 3) String, @Valid Item> byKey,
            Set<@NotBlank String> codes,
            boolean flagged)
            implements FlagCarrier {}

    public static class Resource {
        public void create(@Valid Body body) {}
    }

    private static final Resource RESOURCE = new Resource();

    /** Java path of each violation the fixture argument produces, and the pointer both entry points must give. */
    private static final Map<String, String> EXPECTED = Map.of(
            "", "",
            "displayName", "/display_name",
            "items[0]", "/items/0",
            "items[1].name", "/items/1/item_name",
            "lists[0][2]", "/lists/0/2",
            "byKey[]", "/byKey",
            "byKey[].name", "/byKey",
            "codes[]", "/codes");

    private static Body violatingBody() {
        var items = new ArrayList<>(List.of(new Item("ok", true), new Item("", false)));
        var lists = new ArrayList<List<String>>(List.of(new ArrayList<>(List.of("a", "b", ""))));
        var byKey = new HashMap<String, Item>(Map.of(KEY_SENTINEL, new Item("", false)));
        var codes = new HashSet<>(Set.of(" "));
        return new Body("", items, lists, byKey, codes, true);
    }

    // --- Test ---

    @Test
    @DisplayName("string and structured mapper-only resolution agree on validator output")
    void stringAndStructuredResolutionAgreeOnValidatorOutput() throws Exception {
        // Given: one argument violating every shape of the body type, validated as a method parameter
        Method create = Resource.class.getMethod("create", Body.class);
        List<ParameterViolation> violations =
                beanValidator.checkParameters(RESOURCE, create, new Object[] {violatingBody()});
        ObjectMapper mapper = VertiqueJson.mapper();
        JakartaPathPointers.Resolver session = JakartaPathPointers.resolver(mapper, null);

        // When: each violation is resolved through the structured path and through the Java path string
        Map<String, List<String[]>> resolvedByJavaPath = violations.stream()
                .collect(Collectors.groupingBy(
                        violation -> violation.detail().path(),
                        Collectors.mapping(
                                violation -> {
                                    assertNotNull(
                                            violation.propertyPath(),
                                            () -> "no structured path for "
                                                    + violation.detail().path());
                                    return new String[] {
                                        session.toPointer(violation.propertyPath(), Body.class, null),
                                        JakartaPathPointers.toPointer(
                                                violation.detail().path(), Body.class, mapper)
                                    };
                                },
                                Collectors.toList())));

        // Then: every expected Java path was produced, and nothing else
        assertEquals(
                new TreeSet<>(EXPECTED.keySet()),
                new TreeSet<>(resolvedByJavaPath.keySet()),
                "Java paths of the violations " + describe(violations));

        // And: for every violation both entry points give the same, expected pointer, free of the map key
        List<Executable> checks = new ArrayList<>();
        resolvedByJavaPath.forEach((javaPath, pairs) -> pairs.forEach(pair -> {
            String structured = pair[0];
            String string = pair[1];
            checks.add(() -> assertEquals(
                    structured, string, "string and structured pointers differ for Java path '" + javaPath + "'"));
            checks.add(() -> assertEquals(
                    EXPECTED.get(javaPath), structured, "structured pointer for Java path '" + javaPath + "'"));
            checks.add(() -> assertFalse(
                    structured.contains(KEY_SENTINEL) || string.contains(KEY_SENTINEL),
                    "a map key reached the pointer for Java path '" + javaPath + "'"));
        }));
        assertAll(checks);
    }

    private static String describe(List<ParameterViolation> violations) {
        return violations.stream()
                .map(v -> "[path='" + v.detail().path() + "' propertyPath=" + v.propertyPath() + "]")
                .toList()
                .toString();
    }
}
