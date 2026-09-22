// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import static dev.vertique.json.schema.SchemaAssertions.assertCanonicalForm;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * FR-016 (T008): how every {@code @JsonAlias} spelling of a visible input property is described, and
 * how the profile's own Jackson configuration decides whether several spellings may appear at once.
 *
 * <p>Four proofs share one fixture family, each fixture named for the shape it stands for:
 *
 * <ul>
 *   <li><strong>TP-001</strong> — every alias of a visible input property that does not back an
 *       any-accessor is listed under {@code properties} with a copy of that property's own published
 *       schema, and a required aliased property leaves the top-level {@code required} list, because
 *       the rule below states its requirement instead.
 *   <li><strong>TP-002</strong> — the rules themselves. Lenient ({@code system}, {@code vertique}):
 *       an optional aliased property has no rule, a required one an {@code anyOf} over its spellings.
 *       Strict ({@code vertique-strict}, whose mapper enables {@code
 *       JsonParser.Feature.STRICT_DUPLICATE_DETECTION}): a required property gets a {@code oneOf}, an
 *       optional one a {@code oneOf} with a none-present {@code not} branch. Rules are combined in
 *       one {@code allOf}, and their branches carry only {@code required} or {@code not} — a branch
 *       carrying {@code properties} would be closed by the MCP hardener.
 *   <li><strong>TP-003</strong> — the spellings that are described nowhere: a spelling equal to
 *       another property's name, a spelling on an any-accessor's backing storage, a spelling more
 *       than one property claims, and a spelling whose owning property the document never publishes.
 *       The last two stay reserved where extras are described (FR-015).
 *   <li><strong>TP-008</strong> — the expansion keyword. Expansion carries each plan in the document
 *       under one generator-private key and strips it afterwards, so a type publishing a property
 *       under that exact wire name is refused at generation rather than silently stripped of its
 *       constraints, and no published document carries the key.
 * </ul>
 *
 * <p>T010 adds two more, for the defects the P01 phase-exit gate found inside that outcome:
 *
 * <ul>
 *   <li><strong>T010 TP-001</strong> (CO-007) — the collision of TP-003's first row decided against
 *       the type's <em>own property names</em> rather than against the finished document, so a
 *       spelling naming a property the generator never publishes is withheld too, and stays reserved.
 *       TP-003's colliding property is a public field, which is published, so there the two tests
 *       agree and the defect is invisible.
 *   <li><strong>T010 TP-004</strong> (CO-009) — the keyword refusal covers a spelling expansion would
 *       publish, not only a wire name the type declares directly.
 * </ul>
 *
 * <p>Documents are generated through the registry's built-in profiles, so the subject is the same
 * generator configuration the REST gate and the MCP tool-input boundary use.
 *
 * <p>Failure messages carry the whole document: a schema proof that reports only a missing keyword
 * forces the reader to regenerate the document to see what was published instead.
 */
class AliasDescriptionTest {

    // --- Generation helpers ---

    /**
     * Resolves a built-in profile from a registry holding no application profiles.
     *
     * @param profileId the built-in profile id
     * @return the resolved profile
     */
    private static JsonMapperProfile profile(String profileId) {
        return new DefaultJsonMapperProfileRegistry(Set.of()).profile(JsonProfileId.of(profileId));
    }

    /**
     * Generates a type's input-direction document under the {@code vertique} profile.
     *
     * @param type the body type
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Type type) {
        return inputDocument(type, "vertique");
    }

    /**
     * Generates a type's input-direction document under a named built-in profile.
     *
     * @param type      the body type
     * @param profileId the built-in profile id
     * @return the parsed canonical document
     */
    private static JsonNode inputDocument(Type type, String profileId) {
        return assertCanonicalForm(inputText(type, profileId));
    }

    /**
     * Generates a type's input-direction canonical document text under a named built-in profile.
     *
     * @param type      the body type
     * @param profileId the built-in profile id
     * @return the canonical document text
     */
    private static String inputText(Type type, String profileId) {
        return AnnotationJsonSchemaGenerator.forInputProfile(profile(profileId)).generateCanonical(type);
    }

    /**
     * Binds a JSON body with the {@code vertique} profile's own mapper, so a proof about what a
     * document must describe can state what the binder actually does with the same key.
     *
     * @param type the bound type
     * @param body the JSON body
     * @param <T>  the bound type
     * @return the bound instance
     */
    private static <T> T bind(Class<T> type, String body) {
        try {
            return profile("vertique").mapper().readValue(body, type);
        } catch (JsonProcessingException unbindable) {
            fail("the binder must accept " + body + " for " + type.getSimpleName(), unbindable);
            throw new AssertionError("unreachable");
        }
    }

    // --- Document readers ---

    /**
     * Returns the document's {@code properties} member, or a missing node when it publishes none.
     *
     * @param schema the schema of the type itself
     * @return the {@code properties} node
     */
    private static JsonNode properties(JsonNode schema) {
        return schema.path("properties");
    }

    /**
     * Follows a local {@code $ref} into the document's own definitions, so a proof reads the same
     * schema whether the generator inlined a type or shared it under {@code $defs}.
     *
     * @param document the whole document, which owns the definitions
     * @param node     the node that may be a reference
     * @return the referenced schema, or {@code node} when it is not a local reference
     */
    private static JsonNode resolve(JsonNode document, JsonNode node) {
        JsonNode reference = node.path("$ref");
        if (!reference.isTextual() || !reference.asText().startsWith("#/")) {
            return node;
        }
        return document.at(reference.asText().substring(1));
    }

    /**
     * Returns the reserved names a schema publishes as {@code propertyNames: {"not": {"enum":
     * [...]}}}, or an empty list when it reserves none.
     *
     * @param schema the schema of the any-setter type itself
     * @return the reserved names, in document order
     */
    private static List<String> reservedNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("propertyNames").path("not").path("enum").forEach(name -> names.add(name.asText()));
        return names;
    }

    /**
     * Returns the top-level {@code required} list, or an empty list when the document carries none.
     *
     * @param schema the schema of the type itself
     * @return the required property names, in document order
     */
    private static List<String> requiredNames(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("required").forEach(name -> names.add(name.asText()));
        return names;
    }

    /**
     * Returns the alias rules a schema carries: the members of its {@code allOf}, or the schema
     * itself when one rule stands alone under a free {@code anyOf} or {@code oneOf} keyword.
     *
     * <p>A rule is an object carrying exactly one of {@code anyOf} or {@code oneOf}, whose value is
     * the rule's branch list. The distinction between "combined in one {@code allOf}" and "stands
     * alone" is itself under test, so it is read here rather than assumed.
     *
     * @param schema the schema of the type itself
     * @return the rules, in document order
     */
    private static List<JsonNode> rules(JsonNode schema) {
        List<JsonNode> found = new ArrayList<>();
        if (schema.path("allOf").isArray()) {
            schema.path("allOf").forEach(found::add);
            return found;
        }
        if (schema.path("anyOf").isArray() || schema.path("oneOf").isArray()) {
            found.add(schema);
        }
        return found;
    }

    /**
     * Returns a rule's keyword, {@code anyOf} or {@code oneOf}.
     *
     * @param rule the rule node
     * @return the keyword, or {@code null} when the node carries neither
     */
    private static String ruleKeyword(JsonNode rule) {
        if (rule.path("oneOf").isArray()) {
            return "oneOf";
        }
        return rule.path("anyOf").isArray() ? "anyOf" : null;
    }

    /**
     * Renders a rule's branch list as canonical JSON text, so an expected rule is compared as one
     * value rather than branch by branch.
     *
     * @param rule the rule node
     * @return the branch array's JSON text
     */
    private static String branchesOf(JsonNode rule) {
        String keyword = ruleKeyword(rule);
        return keyword == null ? "<no rule keyword>" : rule.path(keyword).toString();
    }

    // --- Assertion helpers ---

    /**
     * Asserts that a spelling is listed under {@code properties} carrying exactly the schema its
     * owning property is published with.
     *
     * @param document the whole document, printed on failure
     * @param schema   the schema of the aliased type itself
     * @param property the property's own wire name
     * @param alias    the alias spelling
     * @param subject  the fixture name and position, for the failure message
     */
    private static void assertAliasCopiesItsProperty(
            JsonNode document, JsonNode schema, String property, String alias, String subject) {
        assertTrue(
                properties(schema).has(property),
                subject + ": the fixture's own property '" + property + "' must be published, or the alias proof"
                        + " below is vacuous; document: " + document);
        assertTrue(
                properties(schema).has(alias),
                subject + " must list the alias spelling '" + alias + "' under properties: an undescribed spelling"
                        + " passes the REST gate unvalidated and is rejected at a closed MCP object; document: "
                        + document);
        assertEquals(
                properties(schema).path(property).toString(),
                properties(schema).path(alias).toString(),
                subject + ": the alias '" + alias + "' must be published with a copy of the schema its property '"
                        + property + "' is published with, so the same constraint applies under either spelling;"
                        + " document: " + document);
    }

    // --- The generator-private expansion keyword ---

    /**
     * The expansion keyword as design proof v7 measured it, used only when the generator does not yet
     * declare it — that is, at T008's parent, where alias expansion does not exist and no constant
     * could be read. Once the generator declares the keyword, {@link #expansionKeyword()} reads it
     * from the generator itself and this literal is never consulted, so the proof follows the
     * implementation rather than pinning a second copy of it.
     */
    private static final String MEASURED_EXPANSION_KEYWORD = "x-vertique-alias-plan";

    /**
     * Returns the generator's own expansion keyword, found by value rather than by constant name: the
     * one {@code static final String} the generator or one of its nested classes declares whose value
     * starts with the framework's {@code x-vertique-} extension prefix.
     *
     * @return the generator's keyword, or {@link #MEASURED_EXPANSION_KEYWORD} while the generator
     *     declares none
     */
    private static String expansionKeyword() {
        List<String> declared = new ArrayList<>();
        List<Class<?>> candidates = new ArrayList<>();
        candidates.add(AnnotationJsonSchemaGenerator.class);
        candidates.addAll(List.of(AnnotationJsonSchemaGenerator.class.getDeclaredClasses()));
        for (Class<?> candidate : candidates) {
            for (Field field : candidate.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                    continue;
                }
                field.setAccessible(true);
                try {
                    Object value = field.get(null);
                    if (value instanceof String text && text.startsWith("x-vertique-")) {
                        declared.add(text);
                    }
                } catch (IllegalAccessException unreadable) {
                    fail(
                            "the generator's expansion keyword constant must be readable from its own package",
                            unreadable);
                }
            }
        }
        if (declared.isEmpty()) {
            return MEASURED_EXPANSION_KEYWORD;
        }
        assertEquals(
                1,
                declared.size(),
                "the generator must declare exactly one x-vertique- keyword, so this proof can identify the alias"
                        + " expansion keyword by value; found: " + declared);
        return declared.get(0);
    }

    // --- TP-001: every alias is listed with its property's schema ---

    @Test
    @DisplayName("An optional alias is listed with a copy of its property's schema")
    void optionalAliasIsListedWithThePropertySchema() {
        JsonNode document = inputDocument(OptionalAliasedQuantity.class);

        assertAliasCopiesItsProperty(document, document, "quantity", "qty", "OptionalAliasedQuantity");
        assertEquals(
                "{\"maximum\":10,\"type\":\"integer\"}",
                properties(document).path("qty").toString(),
                "the copied schema must carry the property's own constraint: without it {\"qty\":999} binds a"
                        + " property declared @Max(10) past the gate (security round 7, N1); document: " + document);
        assertEquals(
                "{\"type\":\"string\"}",
                properties(document).path("note").toString(),
                "the unaliased property beside it must be untouched; document: " + document);
    }

    @Test
    @DisplayName("A required aliased property leaves the top-level required list")
    void requiredAliasLeavesTopLevelRequired() {
        JsonNode document = inputDocument(RequiredAliasedQuantity.class);

        assertAliasCopiesItsProperty(document, document, "quantity", "qty", "RequiredAliasedQuantity");
        assertEquals(
                List.of(),
                requiredNames(document),
                "a required aliased property must leave the top-level required list, because the alias rule"
                        + " states its requirement instead: keeping it there rejects the legal body {\"qty\":5};"
                        + " document: " + document);
    }

    @Test
    @DisplayName("Both aliases of one property are listed")
    void twoAliasesAreBothListed() {
        JsonNode document = inputDocument(TwoAliasesOnOneProperty.class);

        assertAll(
                () -> assertAliasCopiesItsProperty(document, document, "name", "nm", "TwoAliasesOnOneProperty"),
                () -> assertAliasCopiesItsProperty(document, document, "name", "nm2", "TwoAliasesOnOneProperty"),
                () -> assertEquals(
                        "{\"maxLength\":3,\"type\":\"string\"}",
                        properties(document).path("nm").toString(),
                        "each spelling must carry the property's own @Size bound; document: " + document));
    }

    @Test
    @DisplayName("All ten aliases of a ten-property class are listed")
    void tenAliasedPropertiesAreListed() {
        JsonNode document = inputDocument(TenAliasedProperties.class);

        List<Executable> checks = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            String property = "p" + index;
            String alias = "a" + index;
            checks.add(() -> assertAliasCopiesItsProperty(document, document, property, alias, "TenAliasedProperties"));
        }
        checks.add(() -> assertEquals(
                "{\"properties\":{\"name\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"}",
                properties(document).path("a4").toString(),
                "the object-valued property's alias must carry the whole resolved subschema, which is why the"
                        + " copy is taken after generation rather than at attribute-override time, where the"
                        + " reference is still a placeholder; document: " + document));
        checks.add(() -> assertEquals(
                List.of(),
                requiredNames(document),
                "all three required properties are aliased, so the top-level required list must be gone;"
                        + " document: " + document));
        assertAll(checks);
    }

    @Test
    @DisplayName("An aliased record component is listed")
    void recordComponentAliasIsListed() {
        JsonNode document = inputDocument(AliasedRecordComponent.class);

        assertAliasCopiesItsProperty(document, document, "quantity", "qty", "AliasedRecordComponent");
    }

    @Test
    @DisplayName("An aliased creator parameter on an any-setter type is listed and is not reserved")
    void creatorParameterAliasIsListed() {
        JsonNode document = inputDocument(CreatorParameterAliasOnAnySetterType.class);

        assertAliasCopiesItsProperty(document, document, "quantity", "qty", "CreatorParameterAliasOnAnySetterType");
        assertFalse(
                reservedNames(document).contains("qty"),
                "a published spelling must be a released one: listing 'qty' under properties while also"
                        + " reserving it would reject the very body the listing describes; reserved: "
                        + reservedNames(document) + "; document: " + document);
    }

    @Test
    @DisplayName("An alias on a nested any-setter type is listed at the nested position and is not reserved")
    void nestedAnySetterTypeAliasIsListed() {
        JsonNode document = inputDocument(HoldsAnAliasedAnySetterType.class);

        JsonNode nested = resolve(document, properties(document).path("detail"));
        assertAliasCopiesItsProperty(document, nested, "quantity", "qty", "HoldsAnAliasedAnySetterType.detail");
        assertFalse(
                reservedNames(nested).contains("qty"),
                "the nested any-setter type must release the spelling it publishes; reserved: " + reservedNames(nested)
                        + "; document: " + document);
    }

    // --- TP-002: lenient and strict rules follow the mapper's duplicate detection ---

    @Test
    @DisplayName("Under system and vertique an optional alias has no rule and a required one an anyOf")
    void lenientRulesUnderSystemAndVertique() {
        for (String profileId : List.of("system", "vertique")) {
            JsonNode optional = inputDocument(OptionalAliasedQuantity.class, profileId);
            JsonNode required = inputDocument(RequiredAliasedQuantity.class, profileId);
            JsonNode ten = inputDocument(TenAliasedProperties.class, profileId);

            assertEquals(
                    List.of(),
                    rules(optional).stream()
                            .map(AliasDescriptionTest::branchesOf)
                            .toList(),
                    "under " + profileId + " an optional aliased property needs no rule: its mapper accepts both"
                            + " spellings, so any rule would reject a body the binder binds; document: " + optional);

            assertEquals(
                    1,
                    rules(required).size(),
                    "under " + profileId + " the required type carries one rule;" + " document: " + required);
            assertEquals(
                    "anyOf",
                    ruleKeyword(rules(required).get(0)),
                    "under " + profileId + " a required aliased property needs at least one spelling, which is"
                            + " anyOf: its mapper does not reject a body carrying both; document: " + required);
            assertEquals(
                    "[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]}]",
                    branchesOf(rules(required).get(0)),
                    "the rule must name the property's own spelling first and then each alias; document: " + required);
            assertFalse(
                    required.has("allOf"),
                    "a single rule must stand alone when its keyword is free: wrapping it in allOf adds a level"
                            + " no reader or hardener needs; document: " + required);

            assertTrue(
                    ten.path("allOf").isArray(),
                    "under " + profileId + " the ten-property class must combine its"
                            + " rules in one allOf; document: " + ten);
            assertEquals(
                    3,
                    rules(ten).size(),
                    "under " + profileId + " only the three required properties of the ten-property class carry a"
                            + " rule; document: " + ten);
            assertEquals(
                    List.of("anyOf", "anyOf", "anyOf"),
                    rules(ten).stream().map(AliasDescriptionTest::ruleKeyword).toList(),
                    "every lenient rule is an anyOf; document: " + ten);
        }
    }

    @Test
    @DisplayName("Under vertique-strict a required alias gets a oneOf and an optional one a none-present branch")
    void strictRulesUnderVertiqueStrict() {
        JsonNode optional = inputDocument(OptionalAliasedQuantity.class, "vertique-strict");
        JsonNode required = inputDocument(RequiredAliasedQuantity.class, "vertique-strict");
        JsonNode ten = inputDocument(TenAliasedProperties.class, "vertique-strict");

        assertEquals(1, rules(required).size(), "the strict required type carries one rule; document: " + required);
        assertEquals(
                "oneOf",
                ruleKeyword(rules(required).get(0)),
                "vertique-strict's mapper enables STRICT_DUPLICATE_DETECTION, which is the signal that selects"
                        + " exactly one spelling; document: " + required);
        assertEquals(
                "[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]}]",
                branchesOf(rules(required).get(0)),
                "a strict required property needs exactly one of its spellings; document: " + required);

        assertEquals(1, rules(optional).size(), "the strict optional type carries one rule; document: " + optional);
        assertEquals(
                "oneOf",
                ruleKeyword(rules(optional).get(0)),
                "a strict optional property needs at most one spelling, expressed as a oneOf; document: " + optional);
        assertEquals(
                "[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]},"
                        + "{\"not\":{\"anyOf\":[{\"required\":[\"quantity\"]},{\"required\":[\"qty\"]}]}}]",
                branchesOf(rules(optional).get(0)),
                "an optional property's strict rule needs the none-present branch: without it a body omitting"
                        + " the property entirely satisfies no branch and is rejected; document: " + optional);
        assertFalse(
                optional.has("allOf"),
                "a single rule must stand alone when its keyword is free; document: " + optional);

        assertEquals(
                10,
                rules(ten).size(),
                "under vertique-strict every aliased property carries a rule, the optional ones too;" + " document: "
                        + ten);
        assertEquals(
                List.of("oneOf", "oneOf", "oneOf", "oneOf", "oneOf", "oneOf", "oneOf", "oneOf", "oneOf", "oneOf"),
                rules(ten).stream().map(AliasDescriptionTest::ruleKeyword).toList(),
                "every strict rule is a oneOf; document: " + ten);
    }

    /**
     * The {@code vertique} document design proof v4 measured for the ten-property class. The {@code
     * system} document equals it.
     */
    private static final String MEASURED_TEN_LENIENT =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"allOf\":[{\"anyOf\":[{\"required\":"
                    + "[\"p0\"]},{\"required\":[\"a0\"]}]},{\"anyOf\":[{\"required\":[\"p1\"]},{\"required\":"
                    + "[\"a1\"]}]},{\"anyOf\":[{\"required\":[\"p2\"]},{\"required\":[\"a2\"]}]}],\"properties\":"
                    + "{\"a0\":{\"maxLength\":3,\"type\":\"string\"},\"a1\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a2\":{\"maxLength\":3,\"type\":\"string\"},\"a3\":{\"maximum\":10,\"type\":\"integer\"},"
                    + "\"a4\":{\"properties\":{\"name\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"},"
                    + "\"a5\":{\"maxLength\":3,\"type\":\"string\"},\"a6\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a7\":{\"maxLength\":3,\"type\":\"string\"},\"a8\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a9\":{\"maxLength\":3,\"type\":\"string\"},\"note\":{\"type\":\"string\"},"
                    + "\"p0\":{\"maxLength\":3,\"type\":\"string\"},\"p1\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p2\":{\"maxLength\":3,\"type\":\"string\"},\"p3\":{\"maximum\":10,\"type\":\"integer\"},"
                    + "\"p4\":{\"properties\":{\"name\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"},"
                    + "\"p5\":{\"maxLength\":3,\"type\":\"string\"},\"p6\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p7\":{\"maxLength\":3,\"type\":\"string\"},\"p8\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p9\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"}";

    /** The {@code vertique-strict} document design proof v4 measured for the ten-property class. */
    private static final String MEASURED_TEN_STRICT =
            "{\"$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"allOf\":[{\"oneOf\":[{\"required\":"
                    + "[\"p0\"]},{\"required\":[\"a0\"]}]},{\"oneOf\":[{\"required\":[\"p1\"]},{\"required\":"
                    + "[\"a1\"]}]},{\"oneOf\":[{\"required\":[\"p2\"]},{\"required\":[\"a2\"]}]},{\"oneOf\":"
                    + "[{\"required\":[\"p3\"]},{\"required\":[\"a3\"]},{\"not\":{\"anyOf\":[{\"required\":"
                    + "[\"p3\"]},{\"required\":[\"a3\"]}]}}]},{\"oneOf\":[{\"required\":[\"p4\"]},{\"required\":"
                    + "[\"a4\"]},{\"not\":{\"anyOf\":[{\"required\":[\"p4\"]},{\"required\":[\"a4\"]}]}}]},"
                    + "{\"oneOf\":[{\"required\":[\"p5\"]},{\"required\":[\"a5\"]},{\"not\":{\"anyOf\":"
                    + "[{\"required\":[\"p5\"]},{\"required\":[\"a5\"]}]}}]},{\"oneOf\":[{\"required\":[\"p6\"]},"
                    + "{\"required\":[\"a6\"]},{\"not\":{\"anyOf\":[{\"required\":[\"p6\"]},{\"required\":"
                    + "[\"a6\"]}]}}]},{\"oneOf\":[{\"required\":[\"p7\"]},{\"required\":[\"a7\"]},{\"not\":"
                    + "{\"anyOf\":[{\"required\":[\"p7\"]},{\"required\":[\"a7\"]}]}}]},{\"oneOf\":[{\"required\":"
                    + "[\"p8\"]},{\"required\":[\"a8\"]},{\"not\":{\"anyOf\":[{\"required\":[\"p8\"]},"
                    + "{\"required\":[\"a8\"]}]}}]},{\"oneOf\":[{\"required\":[\"p9\"]},{\"required\":[\"a9\"]},"
                    + "{\"not\":{\"anyOf\":[{\"required\":[\"p9\"]},{\"required\":[\"a9\"]}]}}]}],\"properties\":"
                    + "{\"a0\":{\"maxLength\":3,\"type\":\"string\"},\"a1\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a2\":{\"maxLength\":3,\"type\":\"string\"},\"a3\":{\"maximum\":10,\"type\":\"integer\"},"
                    + "\"a4\":{\"properties\":{\"name\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"},"
                    + "\"a5\":{\"maxLength\":3,\"type\":\"string\"},\"a6\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a7\":{\"maxLength\":3,\"type\":\"string\"},\"a8\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"a9\":{\"maxLength\":3,\"type\":\"string\"},\"note\":{\"type\":\"string\"},"
                    + "\"p0\":{\"maxLength\":3,\"type\":\"string\"},\"p1\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p2\":{\"maxLength\":3,\"type\":\"string\"},\"p3\":{\"maximum\":10,\"type\":\"integer\"},"
                    + "\"p4\":{\"properties\":{\"name\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"},"
                    + "\"p5\":{\"maxLength\":3,\"type\":\"string\"},\"p6\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p7\":{\"maxLength\":3,\"type\":\"string\"},\"p8\":{\"maxLength\":3,\"type\":\"string\"},"
                    + "\"p9\":{\"maxLength\":3,\"type\":\"string\"}},\"type\":\"object\"}";

    @Test
    @DisplayName("The ten-property documents equal the two documents design proof v4 measured")
    void tenPropertyDocumentsMatchTheMeasuredDocuments() {
        assertAll(
                () -> assertEquals(
                        MEASURED_TEN_LENIENT,
                        inputText(TenAliasedProperties.class, "vertique"),
                        "the vertique document must equal the one design proof v4 measured for this fixture shape"),
                () -> assertEquals(
                        MEASURED_TEN_LENIENT,
                        inputText(TenAliasedProperties.class, "system"),
                        "the system document equals the vertique one: neither mapper enables strict duplicate"
                                + " detection, and neither overrides any type this fixture uses"),
                () -> assertEquals(
                        MEASURED_TEN_STRICT,
                        inputText(TenAliasedProperties.class, "vertique-strict"),
                        "the vertique-strict document must equal the one design proof v4 measured"));
    }

    @Test
    @DisplayName("Every rule branch carries only required or not, never properties")
    void branchesCarryOnlyRequiredOrNot() {
        JsonNode document = inputDocument(TenAliasedProperties.class, "vertique-strict");

        // Precondition first: without it this proof passes on a document carrying no rule at all,
        // which is exactly what removing alias expansion produces.
        long multiBranchRules = rules(document).stream()
                .filter(rule -> ruleKeyword(rule) != null)
                .filter(rule -> rule.path(ruleKeyword(rule)).size() >= 2)
                .count();
        assertTrue(
                multiBranchRules >= 1,
                "the document under test must carry at least one rule with at least two branches, or the branch"
                        + " assertion below holds vacuously; document: " + document);

        for (JsonNode rule : rules(document)) {
            for (JsonNode branch : rule.path(ruleKeyword(rule))) {
                List<String> keywords = new ArrayList<>();
                branch.fieldNames().forEachRemaining(keywords::add);
                assertTrue(
                        Set.of("required", "not").containsAll(keywords),
                        "a rule branch must carry only required or not: the MCP hardener closes any object"
                                + " carrying properties, so a branch that listed them would reject every body"
                                + " naming another property; branch keywords: " + keywords + "; document: "
                                + document);
            }
        }
    }

    @Test
    @DisplayName("Recursive and shared types generate byte-identically across passes (CO-006)")
    void recursiveAndSharedTypesGenerateIdenticallyAcrossPasses() {
        List<Type> types = List.of(
                SelfRecursiveAnySetterNode.class,
                MutuallyRecursiveA.class,
                MutuallyRecursiveB.class,
                RecursiveCreatorRenameAnySetterType.class);

        // A fresh generator per pass, then one generator shared across every type, twice: the first
        // detects a per-type record of resolved members drifting between instances, the second detects
        // it drifting inside one cached instance, which is the residual risk CO-006 carries.
        Map<Type, String> freshFirst = new LinkedHashMap<>();
        Map<Type, String> freshSecond = new LinkedHashMap<>();
        types.forEach(type -> freshFirst.put(type, inputText(type, "vertique")));
        types.forEach(type -> freshSecond.put(type, inputText(type, "vertique")));

        AnnotationJsonSchemaGenerator shared = AnnotationJsonSchemaGenerator.forInputProfile(profile("vertique"));
        Map<Type, String> sharedFirst = new LinkedHashMap<>();
        Map<Type, String> sharedSecond = new LinkedHashMap<>();
        types.forEach(type -> sharedFirst.put(type, shared.generateCanonical(type)));
        types.forEach(type -> sharedSecond.put(type, shared.generateCanonical(type)));

        List<Executable> checks = new ArrayList<>();
        for (Type type : types) {
            checks.add(() -> assertEquals(
                    freshFirst.get(type),
                    freshSecond.get(type),
                    type.getTypeName() + " must generate identically from two fresh generators"));
            checks.add(() -> assertEquals(
                    freshFirst.get(type),
                    sharedFirst.get(type),
                    type.getTypeName() + " must generate identically from a generator shared across types: the"
                            + " per-type record of resolved members must not carry another type's members"));
            checks.add(() -> assertEquals(
                    sharedFirst.get(type),
                    sharedSecond.get(type),
                    type.getTypeName() + " must generate identically on a repeat call to one cached generator:"
                            + " a record that drifts between calls changes what the document reserves"));
        }
        checks.add(() -> assertTrue(
                freshFirst.get(MutuallyRecursiveA.class).split("\"level\":\\{\"maximum\":10", -1).length - 1 >= 2,
                "the mutually recursive pair must describe its accessor-bound name in the root and in the"
                        + " nested definition: a description that reached only the root leaves the nested"
                        + " position open; document: " + freshFirst.get(MutuallyRecursiveA.class)));
        assertAll(checks);
    }

    // --- TP-003: colliding, shared, backing-storage, and unpublished-property aliases ---

    @Test
    @DisplayName("An alias equal to another property's name is not listed and leaves that property alone")
    void aliasEqualToAnotherPropertyIsNotListed() {
        for (String profileId : List.of("vertique", "vertique-strict")) {
            JsonNode document = inputDocument(AliasEqualToAnotherPropertyName.class, profileId);

            assertEquals(
                    "{\"type\":\"string\"}",
                    properties(document).path("note").toString(),
                    "a spelling that is already another property's name must not overwrite that property's"
                            + " schema: expansion must leave an existing entry alone under " + profileId
                            + "; document: " + document);
            assertEquals(
                    List.of(),
                    rules(document).stream()
                            .map(AliasDescriptionTest::branchesOf)
                            .toList(),
                    "a spelling already published as a property is named in no rule under " + profileId + "; document: "
                            + document);
        }
    }

    @Test
    @DisplayName("An alias on an any-accessor's backing storage is not listed")
    void aliasOfBackingStorageIsNotListed() {
        for (String profileId : List.of("vertique", "vertique-strict")) {
            JsonNode document = inputDocument(AliasOnAnySetterStorage.class, profileId);

            assertFalse(
                    properties(document).has("more"),
                    "backing storage is not a named property, so its alias describes nothing: listing 'more'"
                            + " would publish a slot Jackson routes into the extras map under " + profileId
                            + "; document: " + document);
            assertEquals(
                    List.of(),
                    rules(document).stream()
                            .map(AliasDescriptionTest::branchesOf)
                            .toList(),
                    "the storage's alias is named in no rule under " + profileId + "; document: " + document);
        }
    }

    @Test
    @DisplayName("A spelling two properties claim is published with the claimant the binder routes it to")
    void aContestedSpellingIsPublishedWithTheClaimantTheBinderRoutesItTo() {
        for (String profileId : List.of("vertique", "vertique-strict")) {
            for (Class<?> type : List.of(
                    ContestedSpellingConstrainedSortsFirst.class,
                    ContestedSpellingConstrainedSortsLast.class,
                    ContestedSpellingWithAnySetter.class)) {
                JsonNode document = inputDocument(type, profileId);
                // The deserializer, not an ordered map, decides which claimant a contested spelling
                // reaches; the document is read from that same deserializer, so it must publish the
                // spelling with exactly the claimant's schema — the property {"x": ...} binds into.
                String claimant = claimantOf(type, "x", "abc");
                assertAll(
                        () -> assertTrue(
                                claimant != null,
                                "the binder must route the contested spelling to one of its claimants for "
                                        + type.getSimpleName()),
                        () -> assertEquals(
                                properties(document).path(claimant).toString(),
                                properties(document).path("x").toString(),
                                "the contested spelling is published with the schema of the claimant the binder"
                                        + " routes it to (" + claimant + ") under " + profileId + "; document: "
                                        + document),
                        () -> assertFalse(
                                document.has("propertyNames"),
                                "the spelling is published, so nothing is reserved under " + profileId + "; document: "
                                        + document));
            }
        }
    }

    /**
     * Binds a body carrying one key and returns the name of the public field that received the value:
     * the binder's own answer to which member a spelling reaches.
     */
    private static String claimantOf(Class<?> type, String key, String value) {
        Object bound = bind(type, "{\"" + key + "\":\"" + value + "\"}");
        for (java.lang.reflect.Field field : type.getFields()) {
            try {
                if (value.equals(field.get(bound))) {
                    return field.getName();
                }
            } catch (IllegalAccessException inaccessible) {
                throw new AssertionError(inaccessible);
            }
        }
        return null;
    }

    @Test
    @DisplayName("An alias of a hidden property stays reserved; an alias of an accessor pair is listed with it")
    void aliasOfAHiddenPropertyStaysReservedAndAnAccessorPairAliasIsListed() {
        for (String profileId : List.of("vertique", "vertique-strict")) {
            JsonNode hidden = inputDocument(HiddenAliasedFieldOnAnySetterType.class, profileId);
            JsonNode accessorPair = inputDocument(AccessorPairAliasedNoFieldOnAnySetterType.class, profileId);

            assertAll(
                    () -> assertFalse(
                            properties(hidden).has("lvl"),
                            "a spelling is listed exactly where its property's own wire name is published, and"
                                    + " 'level' is hidden from this document under " + profileId + "; document: "
                                    + hidden),
                    () -> assertEquals(
                            List.of("level", "lvl"),
                            reservedNames(hidden),
                            "an alias spelling is reserved by default and released only where its owning"
                                    + " property is published: releasing 'lvl' at introspection time leaves it"
                                    + " neither published nor reserved, and it binds the @Max(10) field past the"
                                    + " schema at REST and MCP (design proof v9, AH1) under " + profileId
                                    + "; document: " + hidden),
                    () -> assertEquals(
                            "{\"maximum\":10,\"type\":\"integer\"}",
                            properties(accessorPair).path("level").toString(),
                            "the accessor pair is bound through its setter, so 'level' is described with the"
                                    + " getter's own constraint under " + profileId + "; document: " + accessorPair),
                    () -> assertEquals(
                            properties(accessorPair).path("level").toString(),
                            properties(accessorPair).path("lv").toString(),
                            "its spelling is listed with the same schema, so a key under either spelling is"
                                    + " validated before it reaches the setter (design proof v9, AS3) under "
                                    + profileId + "; document: " + accessorPair),
                    () -> assertFalse(
                            accessorPair.has("propertyNames"),
                            "every bound name is published, so nothing is reserved under " + profileId + "; document: "
                                    + accessorPair));
        }
    }

    // --- T010 TP-001: a spelling naming an unpublished property of the same type ---

    @Test
    @DisplayName("A spelling naming a hidden property stays reserved; one naming an accessor pair yields to it")
    void aSpellingNamingAnUnpublishedPropertyIsNeverPublishedAndStaysReserved() {
        for (String profileId : List.of("vertique", "vertique-strict")) {
            JsonNode hiddenAny = inputDocument(SpellingNamesAHiddenPropertyOnAnySetterType.class, profileId);
            JsonNode hiddenClosed = inputDocument(SpellingNamesAHiddenProperty.class, profileId);
            JsonNode accessorAny = inputDocument(SpellingNamesAnAccessorPairOnAnySetterType.class, profileId);

            List<Executable> checks = new ArrayList<>();
            // The collision is decided against the type's own property names, not against the finished
            // document, so a spelling naming a property the generator never publishes is withheld here
            // exactly as it is when the named property is a published field (TP-003 above).
            Map<String, JsonNode> bySubject = new LinkedHashMap<>();
            bySubject.put("SpellingNamesAHiddenPropertyOnAnySetterType", hiddenAny);
            bySubject.put("SpellingNamesAHiddenProperty", hiddenClosed);
            bySubject.forEach((subject, document) -> {
                checks.add(() -> assertFalse(
                        properties(document).has("secret"),
                        subject + ": the spelling 'secret' is already this type's own property name, so it is"
                                + " listed nowhere — published, it carries 'level''s schema over a member the"
                                + " document deliberately hides, and {\"secret\":99} binds the @Max(3) field"
                                + " through a @Max(10) description under " + profileId + "; document: " + document));
                checks.add(() -> assertEquals(
                        List.of(),
                        rules(document).stream()
                                .map(AliasDescriptionTest::branchesOf)
                                .toList(),
                        subject + ": a withheld spelling is named in no rule branch either under " + profileId
                                + "; document: " + document));
                checks.add(() -> assertEquals(
                        "{\"maximum\":10,\"type\":\"integer\"}",
                        properties(document).path("level").toString(),
                        subject + ": the aliasing property keeps its own entry and its own @Max(10) — the"
                                + " collision withholds one spelling and changes nothing else under " + profileId
                                + "; document: " + document));
                checks.add(() -> assertFalse(
                        document.toString().contains("\"maximum\":3"),
                        subject + ": no entry anywhere may carry the hidden member's @Max(3), which is the"
                                + " schema a copy under the spelling would have to differ from; the two bounds"
                                + " differ precisely so a misplaced schema shows as a number under " + profileId
                                + "; document: " + document));
            });
            checks.add(() -> assertEquals(
                    List.of("secret"),
                    reservedNames(hiddenAny),
                    "SpellingNamesAHiddenPropertyOnAnySetterType: 'secret' is bound by Jackson and published"
                            + " nowhere, so where extras are described it must stay reserved. Publishing it"
                            + " releases it through FR-015's first subtraction — the spelling belongs to"
                            + " 'level', which is published — and the guard disappears entirely under "
                            + profileId + "; document: " + hiddenAny));
            checks.add(() -> assertEquals(
                    List.of(),
                    reservedNames(hiddenClosed),
                    "SpellingNamesAHiddenProperty: no any-setter, so no extras are described and no name is"
                            + " reserved; this shape is guarded by the closed object alone, which is why"
                            + " publishing 'secret' on it reopens a key the MCP hardener refused in 0.2.0"
                            + " under " + profileId + "; document: " + hiddenClosed));
            checks.add(() -> assertEquals(
                    "{\"maximum\":3,\"type\":\"integer\"}",
                    properties(accessorAny).path("level").toString(),
                    "SpellingNamesAnAccessorPairOnAnySetterType: 'level' is this type's own bound name — an"
                            + " accessor pair over a differently named field — so it is described with the"
                            + " getter's own @Max(3) and the spelling on 'amount' is listed nowhere: {\"level\":99}"
                            + " is refused by the setter's own bound, never admitted through 'amount''s @Max(10)"
                            + " under " + profileId + "; document: " + accessorAny));
            checks.add(() -> assertFalse(
                    accessorAny.has("propertyNames"),
                    "SpellingNamesAnAccessorPairOnAnySetterType: every bound name is published, so nothing is"
                            + " reserved under " + profileId + "; document: " + accessorAny));
            checks.add(() -> assertEquals(
                    "{\"maximum\":10,\"type\":\"integer\"}",
                    properties(accessorAny).path("amount").toString(),
                    "SpellingNamesAnAccessorPairOnAnySetterType: the aliasing property is untouched under " + profileId
                            + "; document: " + accessorAny));
            assertAll(checks);
        }
    }

    @Test
    @DisplayName("Jackson binds the colliding key into the unpublished member, which is why it must stay reserved")
    void theCollidingKeyBindsIntoTheUnpublishedMember() {
        SpellingNamesAHiddenPropertyOnAnySetterType boundOnAnySetterType =
                bind(SpellingNamesAHiddenPropertyOnAnySetterType.class, "{\"secret\":99}");
        SpellingNamesAHiddenProperty boundOnClosedType = bind(SpellingNamesAHiddenProperty.class, "{\"secret\":99}");
        SpellingNamesAnAccessorPairOnAnySetterType boundAccessorPair =
                bind(SpellingNamesAnAccessorPairOnAnySetterType.class, "{\"level\":99}");

        assertAll(
                () -> assertEquals(
                        99,
                        boundOnAnySetterType.secret,
                        "the binder routes 'secret' to the type's own hidden member, not to the aliasing"
                                + " property and not into the extras map: that is why a document publishing"
                                + " 'secret' with 'level''s @Max(10) schema admits a value the member's own"
                                + " @Max(3) forbids, and why the name must stay reserved"),
                () -> assertEquals(
                        0,
                        boundOnAnySetterType.level,
                        "the aliasing property is not the key's destination, so the published schema under"
                                + " that spelling would describe the wrong member"),
                () -> assertTrue(
                        boundOnAnySetterType.extras.isEmpty(),
                        "the key is not an extra either: it reaches a real member, so the extras description"
                                + " never applies to it; extras: " + boundOnAnySetterType.extras),
                () -> assertEquals(
                        99,
                        boundOnClosedType.secret,
                        "the same binding happens with no any-setter, where only the closed object refuses"
                                + " the key"),
                () -> assertEquals(
                        99,
                        boundAccessorPair.getLevel(),
                        "the accessor pair's setter is the key's destination, so 'level' must be described"
                                + " with the setter's own constraint rather than with 'amount''s schema"));
    }

    // --- T010 TP-004: a spelling equal to the generator's expansion keyword ---

    @Test
    @DisplayName("An alias spelling equal to the generator keyword refuses generation")
    void anAliasSpellingEqualToTheGeneratorKeywordIsRefused() {
        String keyword = expansionKeyword();

        // A Java annotation value must be a compile-time constant, so the two fixtures below name the
        // measured literal; pinning it against the generator's own constant here is what keeps a
        // rename from leaving the fixtures aliasing an ordinary spelling and this proof vacuous.
        assertEquals(
                MEASURED_EXPANSION_KEYWORD,
                keyword,
                "the generator's expansion keyword must be the one the fixtures below spell in their"
                        + " @JsonAlias, or this refusal proof tests an ordinary alias spelling");

        for (String profileId : List.of("vertique", "vertique-strict")) {
            for (Class<?> type : List.of(KeywordSpellingAlias.class, KeywordSpellingAliasOnAnySetterType.class)) {
                JsonSchemaGenerationException refused = assertThrows(
                        JsonSchemaGenerationException.class,
                        () -> inputText(type, profileId),
                        "generation must be refused for a type whose alias expansion would publish a property"
                                + " under the expansion keyword, exactly as for a type declaring that wire name"
                                + " directly (FR-008, FR-016): the refusal runs before expansion today, so the"
                                + " spelling is published, stripped again by the same descent that removes the"
                                + " plan, and left reserved nowhere, under " + profileId);
                assertTrue(
                        refused.getMessage().contains(type.getTypeName()),
                        "the refusal must name the offending type; message: " + refused.getMessage());
                assertTrue(
                        refused.getMessage().contains(keyword),
                        "the refusal must name the reserved wire name, so the application can rename the"
                                + " spelling; message: " + refused.getMessage());
                assertFalse(
                        refused.getMessage().contains("maximum"),
                        "the diagnostic keeps its existing bound and carries no fragment content; message: "
                                + refused.getMessage());
            }
        }
    }

    // --- TP-008: a property named like the expansion keyword refuses generation ---

    @Test
    @DisplayName("A property published under the expansion keyword refuses generation")
    void propertyNamedLikeTheExpansionKeywordRefusesGeneration() {
        String keyword = expansionKeyword();

        // A Java annotation value must be a compile-time constant, so the two fixtures below cannot
        // name the generator's constant through the reflective reader. Pinning the two together here
        // is what keeps them from drifting apart silently: a generator that renamed its keyword would
        // leave the fixtures testing an ordinary property name and the refusal proof would pass
        // vacuously.
        assertEquals(
                MEASURED_EXPANSION_KEYWORD,
                keyword,
                "the generator's expansion keyword must be the one design proof v7 measured, because the two"
                        + " fixtures below must publish a property under exactly that wire name to be a proof at"
                        + " all; rename the fixtures' @JsonProperty value with the constant");

        for (String profileId : List.of("vertique", "vertique-strict")) {
            for (Class<?> type : List.of(KeywordNamedProperty.class, KeywordNamedPropertyOnAnySetterType.class)) {
                JsonSchemaGenerationException refused = assertThrows(
                        JsonSchemaGenerationException.class,
                        () -> inputText(type, profileId),
                        "generation must be refused for a type publishing a property under the expansion keyword:"
                                + " expansion strips that key from every object node, including the properties"
                                + " object, so the property would be published stripped of its constraints"
                                + " (security round 8, rows MK1 and MK2) under " + profileId);
                assertTrue(
                        refused.getMessage().contains(type.getTypeName()),
                        "the refusal must name the offending type; message: " + refused.getMessage());
                assertTrue(
                        refused.getMessage().contains(keyword),
                        "the refusal must name the reserved wire name, so the application can rename the"
                                + " property; message: " + refused.getMessage());
                assertFalse(
                        refused.getMessage().contains("maximum"),
                        "the diagnostic is bounded and carries no fragment content; message: " + refused.getMessage());
            }
        }
    }

    @Test
    @DisplayName("No published document carries the expansion keyword at any depth")
    void noPublishedDocumentCarriesTheExpansionKeyword() {
        String keyword = expansionKeyword();

        List<Executable> checks = new ArrayList<>();
        for (String profileId : List.of("vertique", "vertique-strict")) {
            for (Type type : PUBLISHED_FIXTURES) {
                checks.add(() -> {
                    String document = inputText(type, profileId);
                    assertFalse(
                            document.contains(keyword),
                            "expansion must strip its own key from the finished document: " + type.getTypeName()
                                    + " published it under " + profileId + "; document: " + document);
                });
            }
        }
        assertAll(checks);
    }

    /** Every fixture of TP-001 through TP-003, which must all generate a document. */
    private static final List<Type> PUBLISHED_FIXTURES = List.of(
            OptionalAliasedQuantity.class,
            RequiredAliasedQuantity.class,
            TwoAliasesOnOneProperty.class,
            TenAliasedProperties.class,
            AliasedRecordComponent.class,
            CreatorParameterAliasOnAnySetterType.class,
            HoldsAnAliasedAnySetterType.class,
            SelfRecursiveAnySetterNode.class,
            MutuallyRecursiveA.class,
            MutuallyRecursiveB.class,
            RecursiveCreatorRenameAnySetterType.class,
            AliasEqualToAnotherPropertyName.class,
            AliasOnAnySetterStorage.class,
            ContestedSpellingConstrainedSortsFirst.class,
            ContestedSpellingConstrainedSortsLast.class,
            ContestedSpellingWithAnySetter.class,
            HiddenAliasedFieldOnAnySetterType.class,
            AccessorPairAliasedNoFieldOnAnySetterType.class,
            SpellingNamesAHiddenPropertyOnAnySetterType.class,
            SpellingNamesAHiddenProperty.class,
            SpellingNamesAnAccessorPairOnAnySetterType.class);

    // --- Fixtures: one per shape, named for it ---

    /** An optional aliased property carrying a constraint, beside an unaliased one. */
    static final class OptionalAliasedQuantity {

        /** The aliased property: {@code qty} must carry the same {@code maximum} it does. */
        @JsonAlias("qty")
        @Max(10)
        public Integer quantity;

        /** An unaliased property, so the expansion is proven beside an untouched entry. */
        public String note;
    }

    /** The same property, required, so the top-level {@code required} list is under test. */
    static final class RequiredAliasedQuantity {

        /** The required aliased property. */
        @JsonAlias("qty")
        @Max(10)
        @NotNull
        public Integer quantity;

        /** An unaliased property. */
        public String note;
    }

    /** One property claiming two spellings, so both must be listed with its schema. */
    static final class TwoAliasesOnOneProperty {

        /** The doubly aliased property. */
        @JsonAlias({"nm", "nm2"})
        @Size(max = 3)
        public String name;
    }

    /**
     * The ten-property class design proof v4 measured: {@code p0} to {@code p9} aliased {@code a0} to
     * {@code a9}, of which {@code p0} to {@code p2} are required and {@code p4} is object-valued,
     * beside an unaliased {@code note}.
     */
    static final class TenAliasedProperties {

        /** A required aliased string. */
        @JsonAlias("a0")
        @Size(max = 3)
        @NotNull
        public String p0;

        /** A required aliased string. */
        @JsonAlias("a1")
        @Size(max = 3)
        @NotNull
        public String p1;

        /** A required aliased string. */
        @JsonAlias("a2")
        @Size(max = 3)
        @NotNull
        public String p2;

        /** An optional aliased integer, so a non-string copy is proven. */
        @JsonAlias("a3")
        @Max(10)
        public Integer p3;

        /** An optional aliased object, whose copy must carry the whole resolved subschema. */
        @JsonAlias("a4")
        public Detail p4;

        /** An optional aliased string. */
        @JsonAlias("a5")
        @Size(max = 3)
        public String p5;

        /** An optional aliased string. */
        @JsonAlias("a6")
        @Size(max = 3)
        public String p6;

        /** An optional aliased string. */
        @JsonAlias("a7")
        @Size(max = 3)
        public String p7;

        /** An optional aliased string. */
        @JsonAlias("a8")
        @Size(max = 3)
        public String p8;

        /** An optional aliased string. */
        @JsonAlias("a9")
        @Size(max = 3)
        public String p9;

        /** The unaliased property, which must carry no rule and gain no spelling. */
        public String note;

        /** The object-valued property's type. */
        static final class Detail {

            /** One constrained member, so the copied subschema is observably more than {@code type}. */
            @Size(max = 3)
            public String name;
        }
    }

    /**
     * An aliased record component.
     *
     * @param quantity the aliased component
     * @param note     an unaliased component
     */
    record AliasedRecordComponent(@JsonAlias("qty") @Max(10) Integer quantity, String note) {}

    /**
     * An aliased {@code @JsonCreator} parameter on an any-setter type: the alias is declared on the
     * parameter alone, and the constraint on the getter the document publishes.
     */
    static final class CreatorParameterAliasOnAnySetterType {

        private final Integer quantity;

        /** The any-setter's backing storage, so the type also reserves names. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Binds the quantity under its own spelling or its alias.
         *
         * @param quantity the bound value
         */
        @JsonCreator
        CreatorParameterAliasOnAnySetterType(@JsonProperty("quantity") @JsonAlias("qty") Integer quantity) {
            this.quantity = quantity;
        }

        /**
         * Returns the quantity, carrying the constraint the document publishes.
         *
         * @return the quantity
         */
        @Max(10)
        public Integer getQuantity() {
            return quantity;
        }
    }

    /** A type holding an aliased any-setter type as a property. */
    static final class HoldsAnAliasedAnySetterType {

        /** The nested any-setter type, whose alias must be listed at this position. */
        public AliasedAnySetterType detail;
    }

    /** An any-setter type with one aliased, constrained property. */
    static final class AliasedAnySetterType {

        /** The aliased property. */
        @JsonAlias("qty")
        @Max(10)
        public Integer quantity;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A self-recursive any-setter type with a setter-only name (design proof v8, {@code Node}). */
    static final class SelfRecursiveAnySetterNode {

        /** An ordinary property. */
        public String name;

        /** The recursive position. */
        public SelfRecursiveAnySetterNode child;

        /**
         * Binds {@code admin} with nothing to publish it from.
         *
         * @param admin the bound value
         */
        public void setAdmin(boolean admin) {
            // The value itself is irrelevant to the document under test.
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * One half of a mutually recursive pair (design proof v8, {@code A}). Both halves carry the
     * accessor pair and the any-setter, so the pair's reserved set must appear at the root and in the
     * inlined nested copy of the other half.
     */
    static final class MutuallyRecursiveA {

        /** An ordinary property. */
        public String name;

        private int lvl;

        /** The mutual position, which inlines a nested copy of the other half. */
        public MutuallyRecursiveB other;

        /**
         * Returns the level.
         *
         * @return the level
         */
        @Max(10)
        public int getLevel() {
            return lvl;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(int level) {
            this.lvl = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** The other half, carrying an accessor pair over a differently named field. */
    static final class MutuallyRecursiveB {

        private int lvl;

        /** The mutual position back. */
        public MutuallyRecursiveA other;

        /**
         * Returns the level.
         *
         * @return the level
         */
        @Max(10)
        public int getLevel() {
            return lvl;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(int level) {
            this.lvl = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A recursive any-setter type with a renamed creator parameter (design proof v8, {@code C}). */
    static final class RecursiveCreatorRenameAnySetterType {

        private final int amount;

        /** The recursive position. */
        public RecursiveCreatorRenameAnySetterType child;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Binds the amount under a wire name that matches no member.
         *
         * @param amount the bound value
         */
        @JsonCreator
        RecursiveCreatorRenameAnySetterType(@JsonProperty("amount_cents") int amount) {
            this.amount = amount;
        }

        /**
         * Returns the amount.
         *
         * @return the amount
         */
        public int getAmount() {
            return amount;
        }
    }

    /** A spelling that is already another property's own name. */
    static final class AliasEqualToAnotherPropertyName {

        /** The aliased property, whose spelling collides with {@code note}. */
        @JsonAlias("note")
        @Max(10)
        public Integer quantity;

        /** The property whose name the spelling collides with. */
        public String note;
    }

    /** A {@code @JsonAlias} on an any-setter's backing storage, which is not a named property. */
    static final class AliasOnAnySetterStorage {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage, carrying a spelling that describes nothing. */
        @JsonAnySetter
        @JsonAlias("more")
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * One spelling claimed by two properties, with the constrained claimant sorting first. The
     * defect depended on which claimant the generator's ordered map reached first, so both orders
     * are separate fixtures.
     */
    static final class ContestedSpellingConstrainedSortsFirst {

        /** The constrained claimant, sorting before the other. */
        @JsonAlias("x")
        @Size(max = 3)
        public String aa;

        /** The unconstrained claimant. */
        @JsonAlias("x")
        public String zz;
    }

    /** The same pair with the constrained claimant sorting last. */
    static final class ContestedSpellingConstrainedSortsLast {

        /** The unconstrained claimant, sorting before the other. */
        @JsonAlias("x")
        public String aa;

        /** The constrained claimant. */
        @JsonAlias("x")
        @Size(max = 3)
        public String zz;
    }

    /** The contested pair on an any-setter type, where the spelling must also be reserved. */
    static final class ContestedSpellingWithAnySetter {

        /** The unconstrained claimant. */
        @JsonAlias("x")
        public String aa;

        /** The constrained claimant. */
        @JsonAlias("x")
        @Size(max = 3)
        public String zz;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An alias on a hidden field: bound by Jackson, published nowhere (design proof v9, AH1). */
    static final class HiddenAliasedFieldOnAnySetterType {

        /** An ordinary property. */
        public String name;

        /** Hidden from the document, still bound by Jackson under either spelling. */
        @Schema(hidden = true)
        @Max(10)
        @JsonAlias("lvl")
        public int level;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An alias on an accessor pair with no same-named field (design proof v9, AS3). */
    static final class AccessorPairAliasedNoFieldOnAnySetterType {

        /** An ordinary property. */
        public String name;

        /** The storage behind the accessor pair, under a different name. */
        private int lvl;

        /**
         * Returns the level, carrying the constraint both bound spellings escape.
         *
         * @return the level
         */
        @Max(10)
        @JsonAlias("lv")
        public int getLevel() {
            return lvl;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(int level) {
            this.lvl = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * The measured critical shape (spec.md § Amendments, round 10): a published property whose alias
     * spelling is already the name of a property the document never publishes.
     *
     * <p>The two bounds differ on purpose. Publishing {@code secret} with {@code level}'s schema
     * describes a {@code @Max(10)} slot over a member declared {@code @Max(3)}, so the wrong schema
     * is visible as a number rather than only as a presence.
     */
    static final class SpellingNamesAHiddenPropertyOnAnySetterType {

        /** The aliasing property, published, whose spelling is the hidden member's own name. */
        @Max(10)
        @JsonAlias("secret")
        public int level;

        /** Hidden from the document, still bound by Jackson under its own name. */
        @Schema(hidden = true)
        @Max(3)
        public int secret;

        /** The any-setter's backing storage, which makes the reserved set observable. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * The same shape with no any-setter, where the closed object was the whole guard: this is the
     * regression against the released MCP boundary rather than a missed tightening.
     */
    static final class SpellingNamesAHiddenProperty {

        /** The aliasing property, published, whose spelling is the hidden member's own name. */
        @Max(10)
        @JsonAlias("secret")
        public int level;

        /** Hidden from the document, still bound by Jackson under its own name. */
        @Schema(hidden = true)
        @Max(3)
        public int secret;
    }

    /**
     * A second unpublished-but-bound member of FR-015's reserved set reached the same way: an accessor
     * pair with no same-named field, whose name another property aliases.
     */
    static final class SpellingNamesAnAccessorPairOnAnySetterType {

        /** The aliasing property, published, whose spelling is the accessor pair's bound name. */
        @Max(10)
        @JsonAlias("level")
        public int amount;

        /** The storage behind the accessor pair, under a different name. */
        private int lvl;

        /**
         * Returns the level, carrying the bound the spelling would escape.
         *
         * @return the level
         */
        @Max(3)
        public int getLevel() {
            return lvl;
        }

        /**
         * Binds the level.
         *
         * @param level the bound value
         */
        public void setLevel(int level) {
            this.lvl = level;
        }

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A published property whose alias spelling is the generator's own expansion keyword. */
    static final class KeywordSpellingAlias {

        /** The aliased property: expansion would publish its schema under the reserved keyword. */
        @JsonAlias(MEASURED_EXPANSION_KEYWORD)
        @Max(10)
        public Integer plan;
    }

    /** The same shape on an any-setter type, where the spelling must also never be left unreserved. */
    static final class KeywordSpellingAliasOnAnySetterType {

        /** The aliased property: expansion would publish its schema under the reserved keyword. */
        @JsonAlias(MEASURED_EXPANSION_KEYWORD)
        @Max(10)
        public Integer plan;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A DTO publishing a property under the generator's own expansion keyword. */
    static final class KeywordNamedProperty {

        /** A constrained property whose wire name collides with the keyword expansion strips. */
        @JsonProperty(MEASURED_EXPANSION_KEYWORD)
        @Max(10)
        public Integer plan;
    }

    /** The same shape on an any-setter type, where security round 8 measured an MCP regression. */
    static final class KeywordNamedPropertyOnAnySetterType {

        /** A constrained property whose wire name collides with the keyword expansion strips. */
        @JsonProperty(MEASURED_EXPANSION_KEYWORD)
        @Max(10)
        public Integer plan;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }
}
