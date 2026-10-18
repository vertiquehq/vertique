// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.core.validation.ParameterViolation;
import dev.vertique.core.validation.PropertyPath;
import dev.vertique.core.validation.PropertyPath.Index;
import dev.vertique.core.validation.PropertyPath.Opaque;
import dev.vertique.core.validation.PropertyPath.Property;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Path;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@code DefaultBeanValidator.checkParameters} records the structured property path of
 * each parameter violation, relative to the parameter value, and renders its Java path from it.
 *
 * <p>Nothing about Hibernate Validator is mocked: violations come from the real validator over
 * real method signatures. The only stand-in is the failure case, which wraps the real violations
 * so that reading their path throws.
 */
class DefaultBeanValidatorPropertyPathTest {

    private static final String KEY_SENTINEL = "ZZKEY";

    private static ValidatorFactory factory;
    private static Validator hibernateValidator;
    private static DefaultBeanValidator beanValidator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.byProvider(HibernateValidator.class).configure().buildValidatorFactory();
        hibernateValidator = factory.getValidator();
        beanValidator = validatorOver(hibernateValidator);
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    private static DefaultBeanValidator validatorOver(Validator validator) {
        return new DefaultBeanValidator(validator, new ViolationDetailMapper(Set.of(), Set.of(), Set.of()));
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

    public record Item(@NotBlank String name) {}

    public interface Animal {}

    public record Dog(@NotBlank String bark) implements Animal {}

    @Flagged
    public static class Order implements FlagCarrier {
        @Valid
        public List<Item> items;

        public List<List<@NotBlank String>> lists;

        public Map<String, @Valid Item> byKey;

        public Set<@NotBlank String> codes;

        @Valid
        public List<Animal> pets;

        @Valid
        public LinkedList<Item> queue;

        public boolean flagged;

        @Override
        public boolean flagged() {
            return flagged;
        }
    }

    public static class Catalog {
        public List<List<@Valid Item>> nested;

        public Map<String, List<@Valid Item>> grouped;
    }

    public static class Resource {
        public void create(@Valid Order order) {}

        public void createCatalog(@Valid Catalog catalog) {}

        public void createWithPage(@Valid Order order, @Min(1) int page) {}

        public void createAll(@Valid List<Animal> animals) {}

        public void createArray(@Valid Animal[] animals) {}
    }

    private static final Resource RESOURCE = new Resource();

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return Resource.class.getMethod(name, parameterTypes);
    }

    private static List<ParameterViolation> checkCreate(Order order) throws NoSuchMethodException {
        return beanValidator.checkParameters(RESOURCE, method("create", Order.class), new Object[] {order});
    }

    private static PropertyPath path(PropertyPath.Segment... segments) {
        return new PropertyPath(List.of(segments));
    }

    // --- Lookup helpers ---

    private static ParameterViolation violationAt(List<ParameterViolation> violations, String javaPath) {
        return violationAt(violations, 0, javaPath);
    }

    private static ParameterViolation violationAt(
            List<ParameterViolation> violations, int parameterIndex, String javaPath) {
        return violations.stream()
                .filter(v -> v.parameterIndex() == parameterIndex
                        && v.detail().path().equals(javaPath))
                .findFirst()
                .orElseGet(() -> fail("no violation for parameter " + parameterIndex + " at java path '" + javaPath
                        + "'; produced " + describe(violations)));
    }

    private static String describe(List<ParameterViolation> violations) {
        return violations.stream()
                .map(v -> "[param " + v.parameterIndex() + " path='"
                        + v.detail().path() + "' propertyPath=" + v.propertyPath() + "]")
                .toList()
                .toString();
    }

    // --- Tests ---

    @Test
    @DisplayName("a nested list violation carries the structured path and an indexed Java path")
    void nestedListViolationCarriesStructuredAndIndexedPaths() throws Exception {
        // Given
        var order = new Order();
        order.items = new ArrayList<>(List.of(new Item("ok"), new Item("")));
        order.lists = new ArrayList<>(List.of(new ArrayList<>(List.of("a", "b", ""))));

        // When
        var violations = checkCreate(order);

        // Then
        assertEquals(2, violations.size(), () -> describe(violations));
        var item = violationAt(violations, "items[1].name");
        var nested = violationAt(violations, "lists[0][2]");
        assertAll(
                () -> assertEquals(
                        path(new Property("items", Order.class), new Index(1), new Property("name", Item.class)),
                        item.propertyPath()),
                () -> assertEquals(0, item.parameterIndex()),
                () -> assertEquals(
                        path(new Property("lists", Order.class), new Index(0), new Index(2)), nested.propertyPath()),
                () -> assertEquals(0, nested.parameterIndex()));
    }

    @Test
    @DisplayName("map keys and set members become opaque markers and never reach the path")
    void mapKeysAndSetMembersBecomeOpaqueMarkers() throws Exception {
        // Given
        var order = new Order();
        order.byKey = new HashMap<>(Map.of(KEY_SENTINEL + ".[x]", new Item("")));
        order.codes = new HashSet<>(Set.of(" "));

        // When
        var violations = checkCreate(order);

        // Then
        assertEquals(2, violations.size(), () -> describe(violations));
        var mapValue = violationAt(violations, "byKey[].name");
        var setMember = violationAt(violations, "codes[]");
        assertAll(
                () -> assertEquals(
                        path(new Property("byKey", Order.class), new Opaque(), new Property("name", Item.class)),
                        mapValue.propertyPath()),
                () -> assertEquals(path(new Property("codes", Order.class), new Opaque()), setMember.propertyPath()),
                () -> violations.forEach(v -> {
                    assertNotNull(
                            v.propertyPath(),
                            () -> "no structured path for " + v.detail().path());
                    assertFalse(
                            v.detail().path().contains(KEY_SENTINEL),
                            () -> "map key leaked into detail path: "
                                    + v.detail().path());
                    assertFalse(
                            v.propertyPath().toJavaPath().contains(KEY_SENTINEL),
                            () -> "map key leaked into java path: "
                                    + v.propertyPath().toJavaPath());
                    assertFalse(
                            v.propertyPath().toString().contains(KEY_SENTINEL),
                            () -> "map key leaked into path: " + v.propertyPath());
                    v.propertyPath()
                            .segments()
                            .forEach(s -> assertFalse(
                                    s.toString().contains(KEY_SENTINEL), () -> "map key leaked into segment: " + s));
                }));
    }

    @Test
    @DisplayName("owner types are runtime classes; class-level and parameter-level violations are the root")
    void ownerTypesAreRuntimeClassesAndBeanViolationsAreTheRoot() throws Exception {
        // Given
        var order = new Order();
        order.pets = new ArrayList<>(List.of(new Dog("")));
        order.flagged = true;

        // When
        var violations = beanValidator.checkParameters(
                RESOURCE, method("createWithPage", Order.class, int.class), new Object[] {order, 0});

        // Then
        assertEquals(3, violations.size(), () -> describe(violations));
        var bark = violationAt(violations, 0, "pets[0].bark");
        var classLevel = violationAt(violations, 0, "");
        var page = violationAt(violations, 1, "");
        assertAll(
                () -> assertEquals(
                        path(new Property("pets", Order.class), new Index(0), new Property("bark", Dog.class)),
                        bark.propertyPath()),
                () -> assertEquals(PropertyPath.root(), classLevel.propertyPath()),
                () -> assertEquals(PropertyPath.root(), page.propertyPath()),
                () -> assertEquals(1, page.parameterIndex()));
    }

    @Test
    @DisplayName("root lists, sequential lists and path conversion failures are handled")
    void rootListsSequentialListsAndFailuresAreHandled() throws Exception {
        // Given
        var createAll = method("createAll", List.class);
        List<Animal> arrayList = new ArrayList<>(List.of(new Dog("")));
        List<Animal> linkedList = new LinkedList<>(List.of(new Dog("")));
        var queued = new Order();
        queued.queue = new LinkedList<>(List.of(new Item("ok"), new Item("")));
        var order = new Order();
        order.items = new ArrayList<>(List.of(new Item("")));

        // When
        var fromArrayList = beanValidator.checkParameters(RESOURCE, createAll, new Object[] {arrayList});
        var fromLinkedList = beanValidator.checkParameters(RESOURCE, createAll, new Object[] {linkedList});
        var fromQueue = checkCreate(queued);
        var fromArray = beanValidator.checkParameters(
                RESOURCE, method("createArray", Animal[].class), new Object[] {new Animal[] {new Dog("")}});

        // Then
        assertAll(
                () -> {
                    assertEquals(1, fromArrayList.size(), () -> describe(fromArrayList));
                    assertEquals(
                            path(new Index(0), new Property("bark", Dog.class)),
                            violationAt(fromArrayList, "[0].bark").propertyPath());
                },
                () -> {
                    assertEquals(1, fromLinkedList.size(), () -> describe(fromLinkedList));
                    assertEquals(
                            path(new Index(0), new Property("bark", null)),
                            violationAt(fromLinkedList, "[0].bark").propertyPath());
                },
                () -> {
                    assertEquals(1, fromQueue.size(), () -> describe(fromQueue));
                    assertEquals(
                            path(new Property("queue", Order.class), new Index(1), new Property("name", Item.class)),
                            violationAt(fromQueue, "queue[1].name").propertyPath());
                },
                () -> {
                    assertEquals(1, fromArray.size(), () -> describe(fromArray));
                    assertEquals(
                            path(new Index(0), new Property("bark", Dog.class)),
                            violationAt(fromArray, "[0].bark").propertyPath());
                },
                () -> assertFailingConversionIsContained(
                        order, () -> new IllegalStateException("path iterator failed"), false),
                () -> assertFailingConversionIsContained(order, StackOverflowError::new, false),
                () -> assertFailingConversionIsContained(
                        order, () -> new IllegalStateException("node inspection failed"), true));
    }

    @Test
    @DisplayName("properties after nested bean containers take the element class as their owner")
    void propertiesAfterNestedContainersTakeTheElementClassAsOwner() throws Exception {
        // Given
        var catalog = new Catalog();
        catalog.nested = new ArrayList<>(List.of(new ArrayList<>(List.of(new Item("ok"), new Item("")))));
        catalog.grouped = new HashMap<>(Map.of(KEY_SENTINEL, new ArrayList<>(List.of(new Item("")))));

        // When
        var violations =
                beanValidator.checkParameters(RESOURCE, method("createCatalog", Catalog.class), new Object[] {catalog});

        // Then
        assertEquals(2, violations.size(), () -> describe(violations));
        var nested = violationAt(violations, "nested[0][1].name");
        var grouped = violationAt(violations, "grouped[][0].name");
        assertAll(
                () -> assertEquals(
                        path(
                                new Property("nested", Catalog.class),
                                new Index(0),
                                new Index(1),
                                new Property("name", Item.class)),
                        nested.propertyPath()),
                () -> assertEquals(
                        path(
                                new Property("grouped", Catalog.class),
                                new Opaque(),
                                new Index(0),
                                new Property("name", Item.class)),
                        grouped.propertyPath()));
    }

    @Test
    @DisplayName("the converter never reads the key of a path node")
    void converterNeverReadsTheKeyOfAPathNode() throws Exception {
        // Given
        var catalog = new Catalog();
        catalog.grouped = new HashMap<>(Map.of(KEY_SENTINEL, new ArrayList<>(List.of(new Item("")))));
        catalog.nested = new ArrayList<>(List.of(new ArrayList<>(List.of(new Item("")))));
        var keyReads = new AtomicInteger();
        var keyGuarded = validatorOver(wrappingNodes(hibernateValidator, node -> keyGuardedNode(node, keyReads)));

        // When
        var violations =
                keyGuarded.checkParameters(RESOURCE, method("createCatalog", Catalog.class), new Object[] {catalog});

        // Then
        assertEquals(2, violations.size(), () -> describe(violations));
        assertAll(
                () -> assertEquals(0, keyReads.get(), "Path.Node.getKey() was called"),
                () -> assertEquals(
                        path(
                                new Property("grouped", Catalog.class),
                                new Opaque(),
                                new Index(0),
                                new Property("name", Item.class)),
                        violationAt(violations, "grouped[][0].name").propertyPath()),
                () -> assertEquals(
                        path(
                                new Property("nested", Catalog.class),
                                new Index(0),
                                new Index(0),
                                new Property("name", Item.class)),
                        violationAt(violations, "nested[0][0].name").propertyPath()));
    }

    /**
     * Runs {@code checkParameters} over real violations whose path (or, when {@code atNode} is set,
     * whose nodes) throws when read, and asserts the violation survives with no structured path.
     */
    private static void assertFailingConversionIsContained(Order order, Supplier<Throwable> failure, boolean atNode)
            throws Exception {
        var failing = validatorOver(failingPaths(hibernateValidator, failure, atNode));
        var method = method("create", Order.class);

        var violations = assertDoesNotThrow(() -> failing.checkParameters(RESOURCE, method, new Object[] {order}));

        assertEquals(1, violations.size(), () -> describe(violations));
        var violation = violations.get(0);
        assertNull(violation.propertyPath(), () -> "expected no structured path, got " + violation.propertyPath());
        // An unreadable path leaves the parameter index undeterminable (-1); an unreadable node keeps it.
        assertEquals(atNode ? 0 : -1, violation.parameterIndex());
        if (atNode) {
            // The previous behavior is preserved for the violation: dotted property names.
            assertEquals("items.name", violation.detail().path());
        }
    }

    // --- Failure injection: real violations whose path cannot be read ---

    private static Validator failingPaths(Validator real, Supplier<Throwable> failure, boolean atNode) {
        return proxy(Validator.class, (self, m, args) -> {
            if (m.getName().equals("forExecutables")) {
                var executable = real.forExecutables();
                return proxy(jakarta.validation.executable.ExecutableValidator.class, (s, em, eargs) -> {
                    var result = invoke(executable, em, eargs);
                    if (!em.getName().equals("validateParameters")) {
                        return result;
                    }
                    var wrapped = new LinkedHashSet<ConstraintViolation<?>>();
                    for (Object violation : (Set<?>) result) {
                        wrapped.add(failingViolation((ConstraintViolation<?>) violation, failure, atNode));
                    }
                    return wrapped;
                });
            }
            return invoke(real, m, args);
        });
    }

    private static ConstraintViolation<?> failingViolation(
            ConstraintViolation<?> real, Supplier<Throwable> failure, boolean atNode) {
        return proxy(ConstraintViolation.class, (self, m, args) -> {
            if (m.getName().equals("getPropertyPath") && m.getParameterCount() == 0) {
                return failingPath(real.getPropertyPath(), failure, atNode);
            }
            return invoke(real, m, args);
        });
    }

    private static Path failingPath(Path real, Supplier<Throwable> failure, boolean atNode) {
        return proxy(Path.class, (self, m, args) -> {
            if (m.getName().equals("iterator")) {
                if (!atNode) {
                    throw failure.get();
                }
                List<Path.Node> nodes = new ArrayList<>();
                for (Path.Node node : real) {
                    nodes.add(failingNode(node, failure));
                }
                Iterator<Path.Node> it = nodes.iterator();
                return it;
            }
            return invoke(real, m, args);
        });
    }

    private static Path.Node failingNode(Path.Node real, Supplier<Throwable> failure) {
        return proxy(Path.Node.class, (self, m, args) -> {
            if (m.getName().equals("isInIterable")) {
                throw failure.get();
            }
            return invoke(real, m, args);
        });
    }

    // --- Key guard: real violations whose nodes fail when their key is read ---

    private static Validator wrappingNodes(Validator real, UnaryOperator<Path.Node> wrapNode) {
        return proxy(Validator.class, (self, m, args) -> {
            if (m.getName().equals("forExecutables")) {
                var executable = real.forExecutables();
                return proxy(jakarta.validation.executable.ExecutableValidator.class, (s, em, eargs) -> {
                    var result = invoke(executable, em, eargs);
                    if (!em.getName().equals("validateParameters")) {
                        return result;
                    }
                    var wrapped = new LinkedHashSet<ConstraintViolation<?>>();
                    for (Object violation : (Set<?>) result) {
                        wrapped.add(nodeWrappingViolation((ConstraintViolation<?>) violation, wrapNode));
                    }
                    return wrapped;
                });
            }
            return invoke(real, m, args);
        });
    }

    private static ConstraintViolation<?> nodeWrappingViolation(
            ConstraintViolation<?> real, UnaryOperator<Path.Node> wrapNode) {
        return proxy(ConstraintViolation.class, (self, m, args) -> {
            if (m.getName().equals("getPropertyPath") && m.getParameterCount() == 0) {
                var realPath = real.getPropertyPath();
                return proxy(Path.class, (ps, pm, pargs) -> {
                    if (pm.getName().equals("iterator")) {
                        List<Path.Node> nodes = new ArrayList<>();
                        for (Path.Node node : realPath) {
                            nodes.add(wrapNode.apply(node));
                        }
                        Iterator<Path.Node> it = nodes.iterator();
                        return it;
                    }
                    return invoke(realPath, pm, pargs);
                });
            }
            return invoke(real, m, args);
        });
    }

    private static Path.Node keyGuardedNode(Path.Node real, AtomicInteger keyReads) {
        return proxy(Path.Node.class, (self, m, args) -> {
            if (m.getName().equals("getKey")) {
                keyReads.incrementAndGet();
                throw new IllegalStateException("node key must not be read");
            }
            return invoke(real, m, args);
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
