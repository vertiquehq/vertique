// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ElementKind;
import jakarta.validation.Path;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.path.ContainerElementNode;
import org.hibernate.validator.path.PropertyNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Characterizes the {@link Path} node shapes the real Hibernate Validator produces for bean and
 * method validation: node kinds, names, iterable placement, index and key availability, and the
 * runtime class of the values the Hibernate node interfaces expose.
 *
 * <p>Nothing here is mocked. Each assertion records what the shipped validator does today so code
 * that converts a violation path into a structured path knows which node facts it may rely on.
 * Nodes are rendered as {@code KIND name [i]} tokens: {@code [i]} is the list or array index,
 * {@code [key]} marks an in-iterable node with a map key and no index, and {@code [*]} marks an
 * in-iterable node that has neither.
 */
class HibernatePathShapeCharacterizationTest {

    private static final String KEY_SENTINEL = "ZZKEY-value";
    private static final String BAD_KEY_SENTINEL = "ZZKEY-toolong";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void buildValidator() {
        factory = Validation.byProvider(HibernateValidator.class).configure().buildValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    // --- Rendering helpers ---

    /** Renders the ordered node list of a violation as {@code KIND name [i]} tokens. */
    private static List<String> nodes(ConstraintViolation<?> violation) {
        List<String> tokens = new ArrayList<>();
        for (Path.Node node : violation.getPropertyPath()) {
            StringBuilder token = new StringBuilder(node.getKind().name());
            if (node.getName() != null) {
                token.append(' ').append(node.getName());
            }
            if (node.isInIterable()) {
                token.append(" [");
                if (node.getIndex() != null) {
                    token.append(node.getIndex());
                } else if (node.getKey() != null) {
                    token.append("key");
                } else {
                    token.append('*');
                }
                token.append(']');
            }
            tokens.add(token.toString());
        }
        return tokens;
    }

    /**
     * Renders the runtime class of the value each property or container element node exposes
     * through the Hibernate node interfaces; {@code -} for kinds without a value and {@code null}
     * for a null value.
     */
    private static List<String> valueClasses(ConstraintViolation<?> violation) {
        List<String> classes = new ArrayList<>();
        for (Path.Node node : violation.getPropertyPath()) {
            Object value;
            switch (node.getKind()) {
                case PROPERTY -> value = node.as(PropertyNode.class).getValue();
                case CONTAINER_ELEMENT ->
                    value = node.as(ContainerElementNode.class).getValue();
                default -> {
                    classes.add("-");
                    continue;
                }
            }
            classes.add(value == null ? "null" : value.getClass().getSimpleName());
        }
        return classes;
    }

    private static <T> T single(Set<? extends T> violations) {
        assertEquals(1, violations.size(), () -> "expected exactly one violation but got " + violations);
        return violations.iterator().next();
    }

    private static ConstraintViolation<?> onlyViolation(Object root) {
        return single(validator.validate(root));
    }

    private static ConstraintViolation<?> onlyParameterViolation(Object target, Method method, Object... args) {
        return single(validator.forExecutables().validateParameters(target, method, args));
    }

    // --- Fixtures ---

    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = RejectingValidator.class)
    @interface Rejecting {
        String message() default "rejected";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    /** A bean that can ask its class-level constraint to fail. */
    public interface Rejectable {
        boolean rejected();
    }

    public static class RejectingValidator implements ConstraintValidator<Rejecting, Rejectable> {
        @Override
        public boolean isValid(Rejectable value, ConstraintValidatorContext context) {
            return !value.rejected();
        }
    }

    public static class Item {
        @NotBlank
        public String name;

        Item(String name) {
            this.name = name;
        }
    }

    @Rejecting
    public static class RejectedItem implements Rejectable {
        @Override
        public boolean rejected() {
            return true;
        }
    }

    @Rejecting
    public static class RejectedRoot implements Rejectable {
        @Override
        public boolean rejected() {
            return true;
        }
    }

    public static class Animal {}

    public static class Dog extends Animal {
        @NotBlank
        public String bark;

        Dog(String bark) {
            this.bark = bark;
        }
    }

    public static class ItemListHolder {
        @Valid
        public List<Item> items;
    }

    public static class TypeArgumentItemListHolder {
        public List<@Valid Item> items;
    }

    public static class TagHolder {
        public List<@NotBlank String> tags;
    }

    public static class NullableTagHolder {
        public List<@NotNull String> tags;
    }

    public static class NestedListHolder {
        public List<List<@NotBlank String>> lists;
    }

    public static class ItemArrayHolder {
        @Valid
        public Item[] items;
    }

    public static class CodeSetHolder {
        public Set<@NotBlank String> codes;
    }

    public static class ItemMapHolder {
        public Map<@NotBlank String, @Valid Item> byKey;
    }

    public static class ShortKeyMapHolder {
        public Map<@Size(max = 5) String, String> byKey;
    }

    public static class NicknameHolder {
        public Optional<@NotBlank String> nickname;
    }

    public static class RejectedItemListHolder {
        @Valid
        public List<RejectedItem> items;
    }

    public static class AnimalListHolder {
        @Valid
        public List<Animal> animals;
    }

    @Rejecting
    public static class Order implements Rejectable {
        @Valid
        public List<Animal> pets;

        public boolean rejected;

        @Override
        public boolean rejected() {
            return rejected;
        }
    }

    public static class Resource {
        public void create(@Valid Order body, @Min(1) int page) {}

        public void createAll(@Valid List<Animal> animals) {}

        public void nullElements(List<@NotNull String> values) {}

        public void blankElements(List<@NotBlank String> values) {}
    }

    private static Method resourceMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return Resource.class.getMethod(name, parameterTypes);
    }

    // --- Bean validation ---

    @Nested
    @DisplayName("lists and arrays")
    class ListFamily {

        @Test
        @DisplayName("bean validation node shapes: cascaded list element property is in-iterable with an index")
        void beanValidationNodeShapes() {
            // Given
            var root = new ItemListHolder();
            root.items = new ArrayList<>(List.of(new Item("ok"), new Item("")));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY items", "PROPERTY name [1]"), nodes(violation));
            assertEquals(List.of("Item", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: type-argument cascade matches the container cascade")
        void typeArgumentCascadeHasSameShape() {
            // Given
            var root = new TypeArgumentItemListHolder();
            root.items = new ArrayList<>(List.of(new Item("ok"), new Item("")));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY items", "PROPERTY name [1]"), nodes(violation));
            assertEquals(List.of("Item", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: list element constraint is a container element node")
        void listElementConstraint() {
            // Given
            var root = new TagHolder();
            root.tags = new ArrayList<>(List.of("a", " "));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY tags", "CONTAINER_ELEMENT <list element> [1]"), nodes(violation));
            assertEquals(List.of("ArrayList", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: null list element is a container element node with null value")
        void nullListElement() {
            // Given
            var root = new NullableTagHolder();
            root.tags = new ArrayList<>(Arrays.asList("a", null));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY tags", "CONTAINER_ELEMENT <list element> [1]"), nodes(violation));
            assertEquals(List.of("ArrayList", "null"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: nested lists carry one index per container element node")
        void nestedLists() {
            // Given
            var root = new NestedListHolder();
            root.lists = new ArrayList<>(List.of(new ArrayList<>(List.of("x", ""))));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(
                    List.of(
                            "PROPERTY lists",
                            "CONTAINER_ELEMENT <list element> [0]",
                            "CONTAINER_ELEMENT <list element> [1]"),
                    nodes(violation));
            assertEquals(List.of("ArrayList", "ArrayList", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: cascaded array element property is in-iterable with an index")
        void cascadedArray() {
            // Given
            var root = new ItemArrayHolder();
            root.items = new Item[] {new Item("ok"), new Item("")};

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY items", "PROPERTY name [1]"), nodes(violation));
            assertEquals(List.of("Item", "String"), valueClasses(violation));
        }
    }

    @Nested
    @DisplayName("sets")
    class SetFamily {

        @Test
        @DisplayName("bean validation node shapes: set element is in-iterable with neither index nor key")
        void beanValidationNodeShapes() {
            // Given
            var root = new CodeSetHolder();
            root.codes = new HashSet<>(List.of(""));

            // When
            var violation = onlyViolation(root);

            // Then
            var elementNode = lastNode(violation);
            assertEquals(List.of("PROPERTY codes", "CONTAINER_ELEMENT <iterable element> [*]"), nodes(violation));
            assertNull(elementNode.getIndex());
            assertNull(elementNode.getKey());
            assertEquals(List.of("HashSet", "String"), valueClasses(violation));
        }
    }

    @Nested
    @DisplayName("maps")
    class MapFamily {

        @Test
        @DisplayName("bean validation node shapes: map value property is in-iterable with a key and no index")
        void beanValidationNodeShapes() {
            // Given
            var root = new ItemMapHolder();
            root.byKey = new HashMap<>(Map.of(KEY_SENTINEL, new Item("")));

            // When
            var violation = onlyViolation(root);

            // Then
            var valueNode = lastNode(violation);
            assertEquals(List.of("PROPERTY byKey", "PROPERTY name [key]"), nodes(violation));
            assertNull(valueNode.getIndex());
            // The key is reachable through getKey(); a converter must discard it deliberately.
            assertEquals(KEY_SENTINEL, valueNode.getKey());
            assertEquals(List.of("Item", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: map key constraint is a container element node carrying the key")
        void mapKeyConstraint() {
            // Given
            var root = new ShortKeyMapHolder();
            root.byKey = new HashMap<>(Map.of(BAD_KEY_SENTINEL, "v"));

            // When
            var violation = onlyViolation(root);

            // Then
            var keyNode = lastNode(violation);
            assertEquals(List.of("PROPERTY byKey", "CONTAINER_ELEMENT <map key> [key]"), nodes(violation));
            assertNull(keyNode.getIndex());
            assertEquals(BAD_KEY_SENTINEL, keyNode.getKey());
            assertEquals(List.of("HashMap", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: a blank map key is still a present key")
        void blankMapKeyIsPresent() {
            // Given
            var root = new ItemMapHolder();
            root.byKey = new HashMap<>(Map.of("", new Item("ok")));

            // When
            var violation = onlyViolation(root);

            // Then
            var keyNode = lastNode(violation);
            assertEquals(List.of("PROPERTY byKey", "CONTAINER_ELEMENT <map key> [key]"), nodes(violation));
            assertNull(keyNode.getIndex());
            assertEquals("", keyNode.getKey());
        }
    }

    @Nested
    @DisplayName("optionals")
    class OptionalFamily {

        @Test
        @DisplayName("bean validation node shapes: optional value is one property node, not in-iterable")
        void beanValidationNodeShapes() {
            // Given
            var root = new NicknameHolder();
            root.nickname = Optional.of("");

            // When
            var violation = onlyViolation(root);

            // Then
            var node = lastNode(violation);
            assertEquals(List.of("PROPERTY nickname"), nodes(violation));
            assertFalse(node.isInIterable());
            // The property node already exposes the unwrapped value, not the Optional.
            assertEquals(List.of("String"), valueClasses(violation));
        }
    }

    @Nested
    @DisplayName("class-level constraints")
    class ClassLevelFamily {

        @Test
        @DisplayName("bean validation node shapes: class-level violation on the root is the only BEAN node")
        void beanValidationNodeShapes() {
            // Given
            var root = new RejectedRoot();

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("BEAN"), nodes(violation));
            assertNull(lastNode(violation).getName());
            assertEquals(List.of("-"), valueClasses(violation));
        }

        @Test
        @DisplayName("bean validation node shapes: class-level violation on a list element ends in an in-iterable BEAN")
        void listElementClassLevel() {
            // Given
            var root = new RejectedItemListHolder();
            root.items = new ArrayList<>(List.of(new RejectedItem()));

            // When
            var violation = onlyViolation(root);

            // Then
            var beanNode = lastNode(violation);
            assertEquals(ElementKind.BEAN, beanNode.getKind());
            assertEquals(List.of("PROPERTY items", "BEAN [0]"), nodes(violation));
            assertNull(beanNode.getName());
            assertEquals(List.of("RejectedItem", "-"), valueClasses(violation));
        }
    }

    @Nested
    @DisplayName("polymorphic elements")
    class PolymorphicFamily {

        @Test
        @DisplayName("bean validation node shapes: subtype property of a list element is named by the subtype")
        void beanValidationNodeShapes() {
            // Given
            var root = new AnimalListHolder();
            root.animals = new ArrayList<>(List.of(new Dog("")));

            // When
            var violation = onlyViolation(root);

            // Then
            assertEquals(List.of("PROPERTY animals", "PROPERTY bark [0]"), nodes(violation));
            assertEquals(List.of("Dog", "String"), valueClasses(violation));
            assertSame(root.animals.get(0), violation.getLeafBean());
        }
    }

    // --- Method validation ---

    @Nested
    @DisplayName("method parameters")
    class MethodParameterFamily {

        @Test
        @DisplayName("method validation shapes and node values: plain parameter violation is METHOD then PARAMETER")
        void methodValidationShapesAndNodeValues() throws Exception {
            // Given
            var resource = new Resource();
            var body = new Order();
            var method = resourceMethod("create", Order.class, int.class);

            // When
            var violation = onlyParameterViolation(resource, method, body, 0);

            // Then
            assertEquals(List.of("METHOD create", "PARAMETER arg1"), nodes(violation));
            assertEquals(1, parameterNode(violation).getParameterIndex());
            assertSame(body, violation.getExecutableParameters()[0]);
            assertEquals(0, violation.getExecutableParameters()[1]);
            assertSame(resource, violation.getLeafBean());
        }

        @Test
        @DisplayName("method validation shapes and node values: cascaded property value is the element, not the list")
        void cascadedPropertyValueIsTheElement() throws Exception {
            // Given
            var resource = new Resource();
            var dog = new Dog("");
            var body = new Order();
            body.pets = new ArrayList<>(List.of(dog));
            var method = resourceMethod("create", Order.class, int.class);

            // When
            var violation = onlyParameterViolation(resource, method, body, 5);

            // Then
            assertEquals(
                    List.of("METHOD create", "PARAMETER arg0", "PROPERTY pets", "PROPERTY bark [0]"), nodes(violation));
            assertEquals(0, parameterNode(violation).getParameterIndex());
            assertSame(body, violation.getExecutableParameters()[0]);
            assertEquals(List.of("-", "-", "Dog", "String"), valueClasses(violation));
            var petsNode = violation.getPropertyPath().iterator();
            petsNode.next();
            petsNode.next();
            assertSame(dog, petsNode.next().as(PropertyNode.class).getValue());
            assertSame(dog, violation.getLeafBean());
        }

        @Test
        @DisplayName("method validation shapes and node values: class-level violation on the body ends in BEAN")
        void classLevelViolationOnBody() throws Exception {
            // Given
            var resource = new Resource();
            var body = new Order();
            body.rejected = true;
            var method = resourceMethod("create", Order.class, int.class);

            // When
            var violation = onlyParameterViolation(resource, method, body, 5);

            // Then
            assertEquals(List.of("METHOD create", "PARAMETER arg0", "BEAN"), nodes(violation));
            assertEquals(0, parameterNode(violation).getParameterIndex());
            assertSame(body, violation.getExecutableParameters()[0]);
            assertSame(body, violation.getLeafBean());
        }

        @Test
        @DisplayName("method validation shapes and node values: root list argument has no value on its parameter node")
        void rootListArgument() throws Exception {
            // Given
            var resource = new Resource();
            var dog = new Dog("");
            List<Animal> animals = new ArrayList<>(List.of(dog));
            var method = resourceMethod("createAll", List.class);

            // When
            var violation = onlyParameterViolation(resource, method, animals);

            // Then
            assertEquals(List.of("METHOD createAll", "PARAMETER arg0", "PROPERTY bark [0]"), nodes(violation));
            var parameter = nthNode(violation, 1);
            assertEquals(ElementKind.PARAMETER, parameter.getKind());
            assertThrows(ClassCastException.class, () -> parameter.as(PropertyNode.class));
            assertThrows(ClassCastException.class, () -> parameter.as(ContainerElementNode.class));
            // The element is reachable only through the argument itself.
            assertSame(animals, violation.getExecutableParameters()[0]);
            assertSame(dog, ((List<?>) violation.getExecutableParameters()[0]).get(0));
            assertSame(dog, violation.getLeafBean());
            assertEquals(List.of("-", "-", "String"), valueClasses(violation));
        }

        @Test
        @DisplayName("method validation shapes and node values: list element node value is the element")
        void containerElementValueIsTheElement() throws Exception {
            // Given
            var resource = new Resource();
            var method = resourceMethod("blankElements", List.class);

            // When
            var violation = onlyParameterViolation(resource, method, new ArrayList<>(List.of("a", " ")));

            // Then
            assertEquals(
                    List.of("METHOD blankElements", "PARAMETER arg0", "CONTAINER_ELEMENT <list element> [1]"),
                    nodes(violation));
            assertEquals(" ", lastNode(violation).as(ContainerElementNode.class).getValue());
        }

        @Test
        @DisplayName("method validation shapes and node values: null list element node value is null")
        void containerElementValueIsNullForNullElement() throws Exception {
            // Given
            var resource = new Resource();
            var method = resourceMethod("nullElements", List.class);

            // When
            var violation = onlyParameterViolation(resource, method, new ArrayList<>(Arrays.asList("a", null)));

            // Then
            assertEquals(
                    List.of("METHOD nullElements", "PARAMETER arg0", "CONTAINER_ELEMENT <list element> [1]"),
                    nodes(violation));
            assertNull(lastNode(violation).as(ContainerElementNode.class).getValue());
        }
    }

    @Nested
    @DisplayName("node kind discrimination")
    class NodeKindFamily {

        @Test
        @DisplayName("node kinds: kinds are told apart by getKind, not instanceof")
        void nodeKindsAreToldApartByGetKind() throws Exception {
            // Given
            var body = new Order();
            body.pets = new ArrayList<>(List.of(new Dog("")));
            var method = resourceMethod("create", Order.class, int.class);

            // When
            var violation = onlyParameterViolation(new Resource(), method, body, 5);

            // Then
            var methodNode = nthNode(violation, 0);
            var parameterNode = nthNode(violation, 1);
            var propertyNode = nthNode(violation, 2);
            assertEquals(ElementKind.METHOD, methodNode.getKind());
            assertEquals(ElementKind.PARAMETER, parameterNode.getKind());
            assertEquals(ElementKind.PROPERTY, propertyNode.getKind());

            // every node answers instanceof for the Hibernate node interfaces regardless of its kind
            for (Path.Node node : violation.getPropertyPath()) {
                assertInstanceOf(PropertyNode.class, node);
                assertInstanceOf(ContainerElementNode.class, node);
            }

            // as() succeeds only for the node's own kind
            assertNotNull(propertyNode.as(PropertyNode.class));
            assertEquals(0, parameterNode.as(Path.ParameterNode.class).getParameterIndex());
            assertThrows(ClassCastException.class, () -> propertyNode.as(ContainerElementNode.class));
            assertThrows(ClassCastException.class, () -> parameterNode.as(PropertyNode.class));
            assertThrows(ClassCastException.class, () -> methodNode.as(PropertyNode.class));
        }

        @Test
        @DisplayName("node kinds: container element rejects the property node view")
        void containerElementRejectsPropertyView() {
            // Given
            var root = new TagHolder();
            root.tags = new ArrayList<>(List.of(" "));

            // When
            var violation = onlyViolation(root);

            // Then
            var elementNode = lastNode(violation);
            assertEquals(ElementKind.CONTAINER_ELEMENT, elementNode.getKind());
            assertNotNull(elementNode.as(ContainerElementNode.class));
            assertThrows(ClassCastException.class, () -> elementNode.as(PropertyNode.class));
        }

        @Test
        @DisplayName("node kinds: bean node supports the bean view only")
        void beanNodeView() {
            // Given
            var root = new RejectedRoot();

            // When
            var violation = onlyViolation(root);

            // Then
            var beanNode = lastNode(violation);
            assertEquals(ElementKind.BEAN, beanNode.getKind());
            assertNotNull(beanNode.as(Path.BeanNode.class));
            assertThrows(ClassCastException.class, () -> beanNode.as(PropertyNode.class));
        }
    }

    // --- Node access ---

    private static Path.Node nthNode(ConstraintViolation<?> violation, int position) {
        int current = 0;
        for (Path.Node node : violation.getPropertyPath()) {
            if (current++ == position) {
                return node;
            }
        }
        throw new AssertionError("path has no node at position " + position + ": " + violation.getPropertyPath());
    }

    private static Path.Node lastNode(ConstraintViolation<?> violation) {
        Path.Node last = null;
        for (Path.Node node : violation.getPropertyPath()) {
            last = node;
        }
        return assertNotNullNode(last);
    }

    private static Path.Node assertNotNullNode(Path.Node node) {
        assertNotNull(node, "violation path has no nodes");
        return node;
    }

    private static Path.ParameterNode parameterNode(ConstraintViolation<?> violation) {
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.PARAMETER) {
                return node.as(Path.ParameterNode.class);
            }
        }
        throw new AssertionError("path has no parameter node: " + violation.getPropertyPath());
    }
}
