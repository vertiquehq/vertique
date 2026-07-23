// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.keyed;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.json.KeyedBy;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Jackson deserializer for a {@code List<T>} record field annotated with {@link KeyedBy} whose
 * external JSON is a keyed object {@code {key:{...}}}.
 *
 * <p>For each {@code (key, value)} entry, the key is injected into the {@link KeyedBy#value()}
 * property of the value object before it is deserialized to {@code T}. If the value's JSON already
 * declares that property:
 * <ul>
 *   <li>matching the key — the entry is accepted;</li>
 *   <li>differing from the key — a clear error is raised, since the two identities conflict.</li>
 * </ul>
 *
 * <p>The deserializer is contextual: {@link #createContextual(DeserializationContext, BeanProperty)}
 * inspects the target property's {@link KeyedBy} annotation and the element type to build a
 * field-specific instance. The shared key-injection logic is exposed as the public static helper
 * {@link #injectKey(String, JsonNode, String, Map)} so the framework's config parser (in
 * {@code vertique-config-core}) and this deserializer apply exactly the same rules.
 */
public final class KeyedCollectionDeserializer extends JsonDeserializer<List<?>> implements ContextualDeserializer {

    /**
     * The identity property name into which each entry's key is injected; {@code null} on the
     * uncontextualized template instance.
     */
    private final String identityProp;

    /**
     * The element type {@code T} each entry deserializes to; {@code null} on the uncontextualized
     * template instance.
     */
    private final JavaType elementType;

    /**
     * Creates an uncontextualized deserializer. Jackson resolves the per-field configuration via
     * {@link #createContextual(DeserializationContext, BeanProperty)}.
     */
    public KeyedCollectionDeserializer() {
        this.identityProp = null;
        this.elementType = null;
    }

    /**
     * Creates a field-specific deserializer.
     *
     * @param identityProp the identity property each entry's key is injected into
     * @param elementType the element type {@code T} each entry deserializes to
     */
    private KeyedCollectionDeserializer(String identityProp, JavaType elementType) {
        this.identityProp = identityProp;
        this.elementType = elementType;
    }

    /**
     * Builds a field-specific deserializer from the target property's {@link KeyedBy} annotation
     * and element type.
     *
     * @param ctxt the active deserialization context
     * @param property the bean property being deserialized, or {@code null} for the root value
     * @return a deserializer configured for the target keyed-collection field
     * @throws JsonMappingException if the property has no resolvable {@link KeyedBy} identity or
     *     element type
     */
    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property)
            throws JsonMappingException {
        if (property == null) {
            throw JsonMappingException.from(ctxt, "@KeyedBy collection has no bean property to contextualize");
        }
        KeyedBy keyedBy = property.getAnnotation(KeyedBy.class);
        if (keyedBy == null) {
            throw JsonMappingException.from(
                    ctxt, "Property '" + property.getName() + "' is missing the required @KeyedBy annotation");
        }
        JavaType propertyType = property.getType();
        JavaType contentType = propertyType.getContentType();
        if (contentType == null) {
            throw JsonMappingException.from(
                    ctxt,
                    "@KeyedBy property '" + property.getName() + "' must be a List with a resolvable element type");
        }
        return new KeyedCollectionDeserializer(keyedBy.value(), contentType);
    }

    /**
     * Reads the keyed JSON object and produces the typed list, injecting each entry's key into the
     * configured identity property. Each element is deserialized through the surrounding mapper so
     * the lenient config features and any nested {@code @KeyedBy} collections compose.
     *
     * @param p the JSON parser positioned at the keyed object
     * @param ctxt the active deserialization context
     * @return the deserialized list of elements
     * @throws IOException if the JSON is malformed or an entry violates the keyed-collection
     *     contract
     */
    @Override
    public List<?> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull() || node.isEmpty()) {
            return List.of();
        }
        if (!node.isObject()) {
            throw JsonMappingException.from(
                    ctxt,
                    "@KeyedBy collection '" + identityProp + "' expects a JSON object, got " + node.getNodeType());
        }
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        List<Object> result = new ArrayList<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            ObjectNode element = injectKey(entry.getKey(), entry.getValue(), identityProp, Map.of());
            result.add(mapper.convertValue(element, elementType));
        }
        return List.copyOf(result);
    }

    // --- shared key-injection logic ---

    /**
     * Builds the element {@link ObjectNode} for one keyed entry: a copy of {@code value} with the
     * entry key injected into {@code identityProp} and every {@code fixedProps} entry set, applying
     * the keyed-collection contract uniformly for both the framework's config parser (in
     * {@code vertique-config-core}) and this deserializer.
     *
     * <p>Rules enforced:
     * <ul>
     *   <li>{@code key} must be non-blank;</li>
     *   <li>{@code value} must be a JSON object;</li>
     *   <li>if {@code value} already declares {@code identityProp} (or a fixed property) with a
     *       value that does <em>not</em> match the injected one — matched by JSON type, with numeric
     *       values compared by value (so a JSON {@code 2} matches an injected {@code Long 2} and a
     *       JSON {@code 2.0} matches an injected {@code Double 2.0}, but a number never matches a
     *       string) — a {@link ConfigurationException} naming the entry key and property (but not the
     *       conflicting values) is thrown; a matching value is accepted.</li>
     * </ul>
     *
     * @param key the entry key
     * @param value the entry value node, expected to be a JSON object
     * @param identityProp the identity property to inject the key into
     * @param fixedProps constant properties to inject into the element
     * @return the element JSON node ready for deserialization
     * @throws ConfigurationException if the key is blank, the value is not an object, or an explicit
     *     property conflicts with the injected value
     */
    public static ObjectNode injectKey(
            String key, JsonNode value, String identityProp, Map<String, Object> fixedProps) {
        if (key == null || key.isBlank()) {
            throw new ConfigurationException("Keyed-collection entry has a blank key for identity property '"
                    + identityProp + "'; keys must be non-blank");
        }
        if (value == null || !value.isObject()) {
            throw new ConfigurationException("Keyed-collection entry '" + key + "' must be a nested JSON object, got "
                    + (value == null ? "null" : value.getNodeType()));
        }
        ObjectNode element = ((ObjectNode) value).deepCopy();
        injectProperty(element, identityProp, key, key);
        for (Map.Entry<String, Object> fixed : fixedProps.entrySet()) {
            injectProperty(element, fixed.getKey(), fixed.getValue(), key);
        }
        return element;
    }

    /**
     * Sets {@code prop} to {@code injected} on {@code element}, rejecting an explicit existing value
     * that does not {@link #matches(JsonNode, Object) match} {@code injected}. Matching is by JSON
     * type, with numeric values compared by value, so a JSON {@code 2} matches an injected
     * {@code Long 2} and a JSON {@code 2.0} matches an injected {@code Double 2.0}, but a number
     * never matches a string.
     *
     * @param element the element node being populated
     * @param prop the property name to set
     * @param injected the value to inject
     * @param key the entry key, for error messages
     * @throws ConfigurationException if {@code element} already declares {@code prop} with a value
     *     that does not match {@code injected}
     */
    private static void injectProperty(ObjectNode element, String prop, Object injected, String key) {
        if (element.has(prop) && !matches(element.get(prop), injected)) {
            throw new ConfigurationException("Keyed-collection entry '" + key + "' declares property '" + prop
                    + "' with a value that conflicts with the injected key value; remove the explicit declaration"
                    + " or ensure it matches.");
        }
        element.set(prop, JsonNodeFactory.instance.pojoNode(injected));
    }

    /**
     * Tests whether an existing JSON node matches an injected Java value, by JSON type with numeric
     * values compared by <em>value</em>. This avoids spurious conflicts when a numerically-equal
     * value is represented by a different node class — e.g. an injected {@code Long 2} (a
     * {@code LongNode}) against a parsed JSON {@code 2} (an {@code IntNode}), or an injected
     * {@code Double 2.0} (a {@code DecimalNode}) against a parsed {@code 2.0} (a {@code DoubleNode}).
     *
     * <p>Type matters: a numeric {@code existing} never matches a {@link String} {@code injected},
     * so a JSON {@code 2} does not match an injected {@code "2"}.
     *
     * @param existing the existing node already declared on the element
     * @param injected the value to inject (key string or fixed-property value)
     * @return {@code true} if {@code existing} matches {@code injected} under the rules above
     */
    private static boolean matches(JsonNode existing, Object injected) {
        if (injected == null) {
            return existing.isNull();
        }
        if (injected instanceof Number n) {
            return existing.isNumber() && existing.decimalValue().compareTo(toBigDecimal(n)) == 0;
        }
        if (injected instanceof Boolean b) {
            return existing.isBoolean() && existing.booleanValue() == b;
        }
        if (injected instanceof String s) {
            return existing.isTextual() && existing.textValue().equals(s);
        }
        return existing.isTextual() && existing.textValue().equals(String.valueOf(injected));
    }

    /**
     * Converts a {@link Number} to a {@link BigDecimal} for value-based numeric comparison: a
     * {@link BigDecimal} as-is, a {@link BigInteger} via {@link BigDecimal#BigDecimal(BigInteger)},
     * a {@link Double}/{@link Float} via {@link BigDecimal#valueOf(double)}, and an integral
     * {@link Integer}/{@link Long}/{@link Short}/{@link Byte} via {@link BigDecimal#valueOf(long)}.
     *
     * @param n the number to convert
     * @return {@code n} as a {@link BigDecimal}
     */
    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) {
            return bd;
        }
        if (n instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (n instanceof Float f) {
            return new BigDecimal(Float.toString(f));
        }
        if (n instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        return BigDecimal.valueOf(n.longValue());
    }
}
