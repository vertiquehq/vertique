// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;

import dev.vertique.core.validation.ViolationDetail;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.json.schema.OutputUnit;
import io.vertx.json.schema.Validator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the violation-detail builder: that it renders a validation result as canonical, value-free
 * {@link ViolationDetail} entries at the engine's instance locations, that every structural keyword is a
 * wrapper, that a result whose errors are all structural or whose keyword cannot be resolved falls back to
 * one value-free detail, and the pointer-form conversion and the pattern-input mapping that surround it.
 *
 * <p>Results come from a real Draft 2020-12 validator wherever the engine produces the shape, and from
 * hand-built {@link OutputUnit}s where it does not.
 */
class ViolationDetailsTest {

    /** A value no detail may ever echo. */
    private static final String SECRET = "SECRET-submitted-value";

    /** A schema that declares {@code amount}, so a reported {@code #/amount} survives the cut-back. */
    private static final JsonObject DECLARES_AMOUNT =
            Schemas.json("{'type':'object','properties':" + "{'amount':{'type':'integer','minimum':2}}}");

    @Test
    @DisplayName("each concrete violation is rendered with its keyword, a canonical message, its constraint and"
            + " the engine's location, and no submitted value")
    void collectRendersKeywordCanonicalMessagesWithEngineLocations() {
        // Given a compiled Draft 2020-12 validator for a schema with a required name and two constraints
        JsonObject schema = Schemas.json("{'type':'object','required':['name'],'properties':{"
                + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
        Validator validator = Validators.compile(schema);
        OutputUnit violations =
                validator.validate(new JsonObject().put("name", "abcd").put("n", 1));
        OutputUnit missingName = validator.validate(new JsonObject());

        // When both results are collected in the URI-fragment form
        List<ViolationDetail> details = ViolationDetails.collect(violations, schema, false, PathForm.URI_FRAGMENT);
        List<ViolationDetail> root = ViolationDetails.collect(missingName, schema, false, PathForm.URI_FRAGMENT);

        // Then the two constraint violations are canonical and located, and the root required failure names
        // the missing property at the engine's root location
        assertThat(details)
                .containsExactly(
                        new ViolationDetail(
                                "#/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)),
                        new ViolationDetail("#/n", "must be at least 2", "minimum", Map.of("minimum", 2)));
        assertThat(details.toString()).doesNotContain("abcd");
        assertThat(root).singleElement().satisfies(detail -> {
            assertThat(detail.type()).isEqualTo("required");
            assertThat(detail.path()).isEqualTo("#");
            assertThat(detail.message()).contains("name");
            assertThat(detail.args()).isEqualTo(Map.of("required", true));
        });
    }

    @Test
    @DisplayName("every structural keyword is a wrapper, and a result with no concrete error falls back to one"
            + " value-free detail")
    void collectTreatsEveryStructuralKeywordAsAWrapperAndFallsBackValueFree() {
        // Given a closed object rejecting an undeclared key, the false schema, and two hand-built results
        // whose error has a null keyword location and a raw message echoing a secret
        JsonObject closed = Schemas.json("{'type':'object','additionalProperties':false}");
        List<FallbackCase> cases = List.of(
                new FallbackCase(
                        "additionalProperties false",
                        Validators.validate(closed, new JsonObject().put("zz", SECRET)),
                        closed,
                        "#"),
                new FallbackCase(
                        "the false schema",
                        Validators.compileFalseSchema().validate(new JsonObject().put("zz", SECRET)),
                        new JsonObject(),
                        ""),
                new FallbackCase(
                        "a single error with a null keyword location",
                        ResultShapes.singleError(null, "#/amount"),
                        DECLARES_AMOUNT,
                        "#/amount"),
                new FallbackCase(
                        "one of several errors with a null keyword location",
                        ResultShapes.multiError(ResultShapes.leaf(null, "#/amount")),
                        DECLARES_AMOUNT,
                        "#/amount"));

        // When each is collected in the URI-fragment form
        // Then each yields exactly one value-free detail that names no client text
        for (FallbackCase fallback : cases) {
            List<ViolationDetail> details =
                    ViolationDetails.collect(fallback.result(), fallback.schema(), false, PathForm.URI_FRAGMENT);

            assertThat(details)
                    .as(fallback.label())
                    .containsExactly(new ViolationDetail(
                            fallback.expectedPath(), ViolationDetails.VALUE_FREE_MESSAGE, null, null));
            assertThat(details.toString()).as(fallback.label()).doesNotContain("zz", SECRET);
        }

        // And additionalProperties is classified as a structural wrapper with no constraint keyword
        assertThat(SchemaKeywords.isStructuralLocation("#/additionalProperties"))
                .isTrue();
        assertThat(SchemaKeywords.constraintKeyword(ResultShapes.leaf("#/additionalProperties", "#/zz")))
                .isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
            nullValues = "NULL",
            value = {
                "'#/a%20b/0/a~1b', '/a b/0/a~1b'",
                "'#/%C3%A9', '/é'",
                "'#', ''",
                "'', ''",
                "'/', '/'",
                "'#/', '/'",
                "NULL, ''"
            })
    @DisplayName("a fragment pointer is percent-decoded into an RFC 6901 pointer, the root forms map to empty and"
            + " a lone slash is the member with the empty name")
    void rfc6901DecodesFragmentPointersAndMapsRootToEmpty(String engineLocation, String expected) {
        // Given an engine location, When it is converted, Then the pointer is decoded and root is empty
        assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a member with the empty name keeps its place in a cut-back location, and an undeclared one ends"
            + " the run")
    void emptyNamedMemberKeepsItsPlaceInTheDeclaredLocation() {
        // Given a schema that declares the member "" holding a name, and a schema that reaches the member ""
        // only through additionalProperties while declaring a root name of its own
        JsonObject declared = Schemas.json("{'properties':{'':{'properties':{'name':{'minLength':1}}}}}");
        JsonObject undeclared = Schemas.json("{'properties':{'name':{'type':'string'}},"
                + "'additionalProperties':{'properties':{'name':{'minLength':1}}}}");
        JsonObject body = Schemas.json("{'':{'name':''}}");

        // When each body is validated and collected in the RFC 6901 form
        List<ViolationDetail> throughDeclared =
                ViolationDetails.collect(Validators.validate(declared, body), declared, false, PathForm.RFC6901);
        List<ViolationDetail> throughUndeclared =
                ViolationDetails.collect(Validators.validate(undeclared, body), undeclared, false, PathForm.RFC6901);

        // Then the declared one is located at the empty-named member's name, and the undeclared one stops at the
        // root, never at the different location /name
        assertAll(
                () -> assertThat(throughDeclared)
                        .extracting(ViolationDetail::path, ViolationDetail::type)
                        .containsExactly(tuple("//name", "minLength")),
                () -> assertThat(throughUndeclared)
                        .extracting(ViolationDetail::path, ViolationDetail::type)
                        .containsExactly(tuple("", "minLength")));
    }

    @Test
    @DisplayName("a violation at an undeclared empty-named member stops at the object that holds it")
    void violationAtAnUndeclaredEmptyNamedMemberStopsAtTheObject() {
        // Given a schema that reaches the member "" only through additionalProperties, and a body whose only
        // member is named ""
        JsonObject schema = Schemas.json("{'type':'object','additionalProperties':{'type':'string','minLength':2}}");
        JsonObject body = Schemas.json("{'':'x'}");

        // When the body is validated and collected in the RFC 6901 form
        List<ViolationDetail> details =
                ViolationDetails.collect(Validators.validate(schema, body), schema, false, PathForm.RFC6901);

        // Then the violation is located at the object, never at the client-named location /
        assertThat(details)
                .extracting(ViolationDetail::path, ViolationDetail::type)
                .containsExactly(tuple("", "minLength"));
    }

    @Test
    @DisplayName("guard failures map to typed details with the frozen messages, and any other failure to nothing")
    void patternInputBoundMapsGuardFailuresToTypedDetails() {
        // Given a per-string failure, a per-validation failure wrapped as a cause, and an unrelated failure
        Throwable tooLong = new PatternInputGuard.PatternInputTooLong(8);
        Throwable wrappedTotal = new RuntimeException("wrapper", new PatternInputGuard.PatternInputTotalExceeded(150));
        Throwable unrelated = new IllegalStateException("unrelated");

        // When each is mapped
        Optional<ViolationDetail> length = ViolationDetails.patternInputBound(tooLong);
        Optional<ViolationDetail> total = ViolationDetails.patternInputBound(wrappedTotal);
        Optional<ViolationDetail> none = ViolationDetails.patternInputBound(unrelated);

        // Then the guard failures are typed with their limit and the unrelated one is left unconverted
        assertThat(length)
                .contains(new ViolationDetail(
                        "",
                        "exceeds the maximum length of 8 characters for pattern validation",
                        "patternInputLength",
                        Map.of("limit", 8)));
        assertThat(total)
                .contains(new ViolationDetail(
                        "",
                        "exceeds the maximum total length of 150 characters for pattern validation",
                        "patternInputTotalLength",
                        Map.of("limit", 150)));
        assertThat(none).isEmpty();
    }

    @Nested
    @DisplayName("the frozen constants and the value-free detail")
    class Constants {

        @Test
        @DisplayName("the value-free message and the two pattern-input types are the frozen strings")
        void constantsAreFrozen() {
            assertThat(ViolationDetails.VALUE_FREE_MESSAGE).isEqualTo("does not satisfy the schema");
            assertThat(ViolationDetails.PATTERN_INPUT_LENGTH_TYPE).isEqualTo("patternInputLength");
            assertThat(ViolationDetails.PATTERN_INPUT_TOTAL_LENGTH_TYPE).isEqualTo("patternInputTotalLength");
        }

        @Test
        @DisplayName("valueFree names only the given path, with the frozen message and no type or args")
        void valueFreeNamesOnlyThePath() {
            assertThat(ViolationDetails.valueFree("#/amount"))
                    .isEqualTo(new ViolationDetail("#/amount", "does not satisfy the schema", null, null));
        }

        @Test
        @DisplayName("a bare total-length failure, and a per-string failure wrapped as a cause, are mapped alike")
        void bareAndWrappedGuardFailuresAreMappedAlike() {
            Throwable bareTotal = new PatternInputGuard.PatternInputTotalExceeded(150);
            Throwable wrappedLength = new RuntimeException(new PatternInputGuard.PatternInputTooLong(8));
            Throwable wrappedUnrelated = new RuntimeException(new IllegalStateException("unrelated"));

            assertThat(ViolationDetails.patternInputBound(bareTotal))
                    .get()
                    .extracting(ViolationDetail::type, ViolationDetail::args)
                    .containsExactly("patternInputTotalLength", Map.of("limit", 150));
            assertThat(ViolationDetails.patternInputBound(wrappedLength))
                    .get()
                    .extracting(ViolationDetail::type, ViolationDetail::args)
                    .containsExactly("patternInputLength", Map.of("limit", 8));
            assertThat(ViolationDetails.patternInputBound(wrappedUnrelated)).isEmpty();
        }
    }

    @Nested
    @DisplayName("paths and pointer forms")
    class Paths {

        @Test
        @DisplayName("the RFC 6901 form converts each engine location and maps the root to empty")
        void rfc6901FormConvertsLocations() {
            JsonObject schema = Schemas.json("{'type':'object','required':['name'],'properties':{"
                    + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
            Validator validator = Validators.compile(schema);

            List<ViolationDetail> violations = ViolationDetails.collect(
                    validator.validate(new JsonObject().put("name", "abcd").put("n", 1)),
                    schema,
                    false,
                    PathForm.RFC6901);
            List<ViolationDetail> root =
                    ViolationDetails.collect(validator.validate(new JsonObject()), schema, false, PathForm.RFC6901);

            assertThat(violations).extracting(ViolationDetail::path).containsExactly("/name", "/n");
            assertThat(root).extracting(ViolationDetail::path).containsExactly("");
        }

        @Test
        @DisplayName("a declared name with a space keeps its percent-encoded form, or decodes in RFC 6901 form")
        void aDeclaredNameWithASpaceIsEncodedOrDecoded() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'a b':{'type':'integer'}}}");
            OutputUnit result = Validators.validate(schema, new JsonObject().put("a b", "x"));

            List<ViolationDetail> fragment = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);
            List<ViolationDetail> pointer = ViolationDetails.collect(result, schema, false, PathForm.RFC6901);

            assertThat(fragment)
                    .containsExactly(new ViolationDetail(
                            "#/a%20b", "must be of type: integer", "type", Map.of("type", "integer")));
            assertThat(pointer).extracting(ViolationDetail::path).containsExactly("/a b");
        }

        @Test
        @DisplayName("a root-level violation is located at the engine's root form, or empty in RFC 6901 form")
        void aRootViolationIsLocatedAtTheRoot() {
            JsonObject schema = Schemas.json("{'type':'string'}");
            OutputUnit result = Validators.validate(schema, new JsonObject());

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail("#", "must be of type: string", "type", Map.of("type", "string")));
            assertThat(ViolationDetails.collect(result, schema, false, PathForm.RFC6901))
                    .extracting(ViolationDetail::path)
                    .containsExactly("");
        }

        @Test
        @DisplayName("a location reached through a local $ref keeps its declared names")
        void aLocationThroughARefKeepsItsDeclaredNames() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'pet':{'$ref':'#/$defs/Inner'}},"
                    + "'$defs':{'Inner':{'type':'object','properties':{'name':{'type':'string','maxLength':3}}}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'pet':{'name':'toolong'}}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/pet/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)));
        }

        @Test
        @DisplayName("a tuple position is kept as its index")
        void aTuplePositionIsKept() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'t':{'type':'array','prefixItems':"
                    + "[{'type':'string','maxLength':2},{'type':'integer'}]}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'t':['long',1]}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/t/0", "must have a maximum length of 2", "maxLength", Map.of("maxLength", 2)));
        }

        @Test
        @DisplayName("an index under an items schema is kept")
        void anIndexUnderItemsIsKept() {
            JsonObject schema = Schemas.json("{'type':'array','items':{'type':'integer','minimum':2}}");
            OutputUnit result =
                    Validators.compile(schema).validate(new JsonArray().add(1).add(5));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("#/0", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("a violation under a key the client chose is cut back to the containing location and names"
                + " nothing of the key")
        void aClientChosenKeyIsCutBack() {
            String clientKey = "CLIENT-KEY-" + "q".repeat(50);
            JsonObject schema = Schemas.json("{'type':'object','properties':{'name':{'type':'string','maxLength':3}},"
                    + "'additionalProperties':{'type':'string'}}");
            OutputUnit result = Validators.validate(schema, new JsonObject().put(clientKey, 5));

            List<ViolationDetail> details = ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT);

            assertThat(details)
                    .containsExactly(
                            new ViolationDetail("#", "must be of type: string", "type", Map.of("type", "string")));
            assertThat(details.toString()).doesNotContain(clientKey);
        }

        @Test
        @DisplayName("a single-error result with a resolved keyword is located at the root, not at its instance"
                + " location")
        void aSingleErrorWithAResolvedKeywordIsLocatedAtTheRoot() {
            OutputUnit result = ResultShapes.singleError("#/properties/amount/minimum", "#/amount");

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("a valid result yields no detail")
        void aValidResultYieldsNoDetail() {
            OutputUnit result = Validators.validate(DECLARES_AMOUNT, new JsonObject().put("amount", 5));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("detail content")
    class Content {

        @Test
        @DisplayName("a pattern failure names the declared expression, and a type list is rendered as alternatives")
        void patternAndTypeListMessages() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{"
                    + "'s':{'type':'string','pattern':'^a+$'},'u':{'type':['string','null']}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'s':'b','u':5}"));

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail(
                                    "#/s", "must match pattern: ^a+$", "pattern", Map.of("pattern", "^a+$")),
                            new ViolationDetail(
                                    "#/u",
                                    "must be of type: string or null",
                                    "type",
                                    Map.of("type", List.of("string", "null"))));
        }

        @Test
        @DisplayName("a required failure keeps the engine's message, which names the property and no value")
        void aRequiredFailureKeepsTheEnginesMessage() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{'p':{'type':'object','required':['x']}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'p':{}}"));
            String raw = result.getErrors().stream()
                    .filter(error -> error.getKeywordLocation().endsWith("/required"))
                    .findFirst()
                    .orElseThrow()
                    .getError();

            assertThat(ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail("#/p", raw, "required", Map.of("required", true)));
            assertThat(raw).contains("x");
        }

        @Test
        @DisplayName("fail-fast stops at the first concrete violation")
        void failFastStopsAtTheFirstConcreteViolation() {
            JsonObject schema = Schemas.json("{'type':'object','properties':{"
                    + "'name':{'type':'string','maxLength':3},'n':{'type':'integer','minimum':2}}}");
            OutputUnit result = Validators.validate(schema, Schemas.json("{'name':'abcd','n':1}"));

            assertThat(ViolationDetails.collect(result, schema, true, PathForm.URI_FRAGMENT))
                    .containsExactly(new ViolationDetail(
                            "#/name", "must have a maximum length of 3", "maxLength", Map.of("maxLength", 3)));
        }
    }

    @Nested
    @DisplayName("the value-free fallback")
    class ValueFreeFallback {

        @ParameterizedTest(name = "keyword location [{0}], single error {1}, fail-fast {2}")
        @MethodSource("unresolvedKeywordShapes")
        @DisplayName("an error whose keyword location is absent or empty yields a value-free detail at its own"
                + " instance location, never the raw message")
        void anUnresolvedKeywordYieldsAValueFreeDetail(String keywordLocation, boolean single, boolean failFast) {
            OutputUnit result = single
                    ? ResultShapes.singleError(keywordLocation, "#/amount")
                    : ResultShapes.multiError(ResultShapes.leaf(keywordLocation, "#/amount"));

            List<ViolationDetail> details =
                    ViolationDetails.collect(result, DECLARES_AMOUNT, failFast, PathForm.URI_FRAGMENT);

            assertThat(details).containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(details.toString()).doesNotContain(SECRET);
        }

        static Stream<Arguments> unresolvedKeywordShapes() {
            return Stream.of(
                    Arguments.of(null, true, false),
                    Arguments.of(null, true, true),
                    Arguments.of(null, false, false),
                    Arguments.of(null, false, true),
                    Arguments.of("", true, false),
                    Arguments.of("", true, true),
                    Arguments.of("", false, false),
                    Arguments.of("", false, true));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(
                strings = {
                    "properties",
                    "additionalProperties",
                    "items",
                    "prefixItems",
                    "allOf",
                    "anyOf",
                    "oneOf",
                    "if",
                    "then",
                    "else",
                    "not",
                    "$ref",
                    "unevaluatedProperties",
                    "unevaluatedItems"
                })
        @DisplayName("an error at a structural keyword is a wrapper: alone it yields one value-free detail")
        void aStructuralKeywordIsAWrapper(String keyword) {
            OutputUnit several = ResultShapes.multiError(ResultShapes.leaf("#/" + keyword, "#/amount"));
            OutputUnit single = ResultShapes.singleError("#/" + keyword, "#/amount");

            assertThat(ViolationDetails.collect(several, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(ViolationDetails.collect(single, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
        }

        @Test
        @DisplayName("a structural wrapper beside a concrete error is skipped and adds no value-free detail")
        void aStructuralWrapperBesideAConcreteErrorIsSkipped() {
            OutputUnit result = ResultShapes.multiError(
                    ResultShapes.leaf("#/properties", "#/amount"),
                    ResultShapes.leaf("#/properties/amount/minimum", "#/amount"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            new ViolationDetail("#/amount", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("an unresolved error before a concrete one stops a fail-fast collection, and is followed by"
                + " the concrete one otherwise")
        void anUnresolvedErrorCountsTowardFailFast() {
            OutputUnit result = ResultShapes.multiError(
                    ResultShapes.leaf(null, "#/amount"), ResultShapes.leaf("#/properties/amount/minimum", "#/amount"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, true, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(
                            ViolationDetails.valueFree("#/amount"),
                            new ViolationDetail("#/amount", "must be at least 2", "minimum", Map.of("minimum", 2)));
        }

        @Test
        @DisplayName("the fallback of a result with only structural errors names the first error's location, cut back"
                + " to what the schema declares")
        void theFallbackIsCutBackToDeclaredNames() {
            OutputUnit result = ResultShapes.multiError(ResultShapes.leaf("#/additionalProperties", "#/amount/zz"));

            assertThat(ViolationDetails.collect(result, DECLARES_AMOUNT, false, PathForm.URI_FRAGMENT))
                    .containsExactly(ViolationDetails.valueFree("#/amount"));
        }
    }

    @Nested
    @DisplayName("the cut-back bounds")
    class CutBackBounds {

        @Test
        @DisplayName("a short chain of local references keeps the declared names")
        void aShortReferenceChainKeepsTheDeclaredNames() {
            assertThat(pathBeyondAReferenceChain(3)).isEqualTo("#/pet/name");
        }

        @Test
        @DisplayName("a reference chain far past the hop bound ends the run at the containing location")
        void aLongReferenceChainEndsTheRun() {
            assertThat(pathBeyondAReferenceChain(40)).isEqualTo("#/pet");
        }

        @Test
        @DisplayName("a self-referential reference ends the run at the containing location")
        void aSelfReferenceEndsTheRun() {
            JsonObject schema = Schemas.json(
                    "{'properties':{'pet':{'$ref':'#/$defs/loop'}}," + "'$defs':{'loop':{'$ref':'#/$defs/loop'}}}");

            assertThat(collectedPath(schema, "#/pet/name")).isEqualTo("#/pet");
        }

        @ParameterizedTest(name = "{0} subschemas in the composition")
        @CsvSource({"64, '#/pet/name'", "65, '#/pet'"})
        @DisplayName("a composition of up to 64 subschemas keeps the declared names, and a wider one ends the run")
        void theComposedSubschemaBound(int subschemas, String expectedPath) {
            // the pet node itself, the declaring branch, and empty branches make up the total
            JsonArray branches = new JsonArray().add(Schemas.json("{'properties':{'name':{'type':'string'}}}"));
            IntStream.range(0, subschemas - 2).forEach(index -> branches.add(new JsonObject()));
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put("pet", new JsonObject().put("allOf", branches)));

            assertThat(collectedPath(schema, "#/pet/name")).isEqualTo(expectedPath);
        }

        @ParameterizedTest(name = "segment {0}")
        @CsvSource({
            "0, '#/pet/0'",
            "1234567890, '#/pet/1234567890'",
            "12345678901, '#/pet'",
            "01, '#/pet'",
            "-1, '#/pet'"
        })
        @DisplayName("an array index is named only when it is canonical and at most ten digits")
        void theIndexDigitBound(String segment, String expectedPath) {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'type':'array','items':{'type':'string'}}}}");

            assertThat(collectedPath(schema, "#/pet/" + segment)).isEqualTo(expectedPath);
        }

        @Test
        @DisplayName("a segment that is not a well-formed encoding ends the run")
        void aMalformedEncodingEndsTheRun() {
            JsonObject schema = Schemas.json("{'properties':{'pet':{'properties':{'name':{'type':'string'}}}}}");

            assertThat(collectedPath(schema, "#/pet/na%zzme")).isEqualTo("#/pet");
        }

        private String pathBeyondAReferenceChain(int hops) {
            JsonObject definitions = new JsonObject();
            for (int hop = 0; hop < hops; hop++) {
                definitions.put("h" + hop, new JsonObject().put("$ref", "#/$defs/h" + (hop + 1)));
            }
            definitions.put("h" + hops, Schemas.json("{'properties':{'name':{'type':'string'}}}"));
            JsonObject schema = new JsonObject()
                    .put("properties", new JsonObject().put("pet", new JsonObject().put("$ref", "#/$defs/h0")))
                    .put("$defs", definitions);
            return collectedPath(schema, "#/pet/name");
        }

        /**
         * Collects a hand-built error with an unresolvable keyword at {@code instanceLocation}, which yields the
         * value-free detail at that location after the cut-back, and returns its path.
         */
        private String collectedPath(JsonObject schema, String instanceLocation) {
            OutputUnit result = ResultShapes.singleError(null, instanceLocation);
            return ViolationDetails.collect(result, schema, false, PathForm.URI_FRAGMENT)
                    .get(0)
                    .path();
        }
    }

    @Nested
    @DisplayName("rfc6901 edge forms")
    class Rfc6901EdgeForms {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "'/a%20b', '/a b'",
            "'#/a%zzb', '/a%zzb'",
            "'#/a%2', '/a%2'",
            "'#/a~0b', '/a~0b'",
            "'#/a+b', '/a+b'",
            "'#/a%7E1b', '/a~1b'",
            "'#/0/1', '/0/1'"
        })
        @DisplayName("invalid escapes and escaped tildes are left as they are, and a plus is not a space")
        void edgeForms(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "'#/%4A', '/J'",
            "'#/%4a', '/J'",
            "'#/%c3%A9', '/é'",
            "'#/%C3%a9', '/é'",
            "'#/%2F', '//'",
            "'#/%41%42', '/AB'"
        })
        @DisplayName("an escape written with ASCII hex digits of either case is decoded")
        void asciiHexDigitsOfEitherCaseAreDecoded(String engineLocation, String expected) {
            assertThat(ViolationDetails.rfc6901(engineLocation)).isEqualTo(expected);
        }

        @Test
        @DisplayName("an escape written with full-width digits or letters is not decoded and its text is kept")
        void nonAsciiHexDigitsAreNotDecoded() {
            String fullWidthFour = new String(Character.toChars(0xFF14));
            String fullWidthOne = new String(Character.toChars(0xFF11));
            String fullWidthA = new String(Character.toChars(0xFF21));

            assertThat(ViolationDetails.rfc6901("#/%" + fullWidthFour + fullWidthOne))
                    .isEqualTo("/%" + fullWidthFour + fullWidthOne);
            assertThat(ViolationDetails.rfc6901("#/%" + fullWidthA + "0")).isEqualTo("/%" + fullWidthA + "0");
            assertThat(ViolationDetails.rfc6901("#/%4" + fullWidthOne)).isEqualTo("/%4" + fullWidthOne);
        }

        @Test
        @DisplayName("a multi-byte sequence is decoded as UTF-8")
        void aMultiByteSequenceIsDecodedAsUtf8() {
            String grin = new String(Character.toChars(0x1F600));

            assertThat(ViolationDetails.rfc6901("#/%F0%9F%98%80/x")).isEqualTo("/" + grin + "/x");
        }
    }

    @Nested
    @DisplayName("safeDetail")
    class SafeDetail {

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("messages")
        @DisplayName("a keyword with its constraint is rendered canonically, and the raw engine message is used"
                + " only where it is reviewed to be value-free")
        void rendersTheCanonicalMessage(String keyword, Map<String, Object> args, String rawMessage, String expected) {
            assertThat(ViolationDetails.safeDetail(keyword, args, rawMessage)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"minLength", "maxLength", "minimum", "pattern", "type", "format", "const", "enum"})
        @DisplayName("a keyword whose constraint is missing from the args never returns the engine's message")
        void aMissingConstraintNeverReturnsTheEnginesMessage(String keyword) {
            String sentinel = "SENTINEL-" + SECRET;

            String detail = ViolationDetails.safeDetail(keyword, Map.of(), "the value " + sentinel + " is bad");

            assertThat(detail).isEqualTo(keyword + " constraint violated").doesNotContain(sentinel);
        }

        @Test
        @DisplayName("a null keyword yields the fixed message and never the engine's message")
        void aNullKeywordYieldsTheFixedMessage() {
            String sentinel = "SENTINEL-SECRET-submitted-value";

            String detail = ViolationDetails.safeDetail(null, Map.of(), "the value " + sentinel + " is bad");

            assertThat(detail).isEqualTo("is invalid").doesNotContain(sentinel);
        }

        static Stream<Arguments> messages() {
            String raw = "500 is greater than 100";
            return Stream.of(
                    Arguments.of("minLength", Map.of("minLength", 3), raw, "must have a minimum length of 3"),
                    Arguments.of("maxLength", Map.of("maxLength", 3), raw, "must have a maximum length of 3"),
                    Arguments.of("minimum", Map.of("minimum", 2), raw, "must be at least 2"),
                    Arguments.of("maximum", Map.of("maximum", 9), raw, "must be at most 9"),
                    Arguments.of("exclusiveMinimum", Map.of("exclusiveMinimum", 1), raw, "must be greater than 1"),
                    Arguments.of("exclusiveMaximum", Map.of("exclusiveMaximum", 10), raw, "must be less than 10"),
                    Arguments.of("minItems", Map.of("minItems", 1), raw, "must have at least 1 items"),
                    Arguments.of("maxItems", Map.of("maxItems", 5), raw, "must have at most 5 items"),
                    Arguments.of("minProperties", Map.of("minProperties", 1), raw, "must have at least 1 properties"),
                    Arguments.of("maxProperties", Map.of("maxProperties", 4), raw, "must have at most 4 properties"),
                    Arguments.of("multipleOf", Map.of("multipleOf", 5), raw, "must be a multiple of 5"),
                    Arguments.of("pattern", Map.of("pattern", "^a+$"), raw, "must match pattern: ^a+$"),
                    Arguments.of("required", Map.of("required", true), "missing \"x\"", "missing \"x\""),
                    Arguments.of("required", Map.of("required", true), null, "is missing a required field"),
                    Arguments.of("type", Map.of("type", "string"), raw, "must be of type: string"),
                    Arguments.of(
                            "type", Map.of("type", List.of("string", "null")), raw, "must be of type: string or null"),
                    Arguments.of(
                            "type",
                            Map.of("type", List.of("string", "integer", "null")),
                            raw,
                            "must be of type: string, integer or null"),
                    Arguments.of("type", Map.of("type", true), raw, "must be of the required type"),
                    Arguments.of("type", Map.of("type", List.of()), raw, "must be of the required type"),
                    Arguments.of(
                            "propertyNames",
                            Map.of("propertyNames", true),
                            raw,
                            "contains a property name the schema does not allow"),
                    Arguments.of(
                            "patternProperties", Map.of("patternProperties", true), raw, "does not satisfy the schema"),
                    Arguments.of("format", Map.of("format", true), raw, "format constraint violated"),
                    Arguments.of("const", Map.of("const", true), raw, "const constraint violated"),
                    Arguments.of("minLength", Map.of(), "raw message", "minLength constraint violated"),
                    Arguments.of("pattern", Map.of("other", 1), raw, "pattern constraint violated"),
                    Arguments.of("type", Map.of(), raw, "type constraint violated"),
                    Arguments.of("format", Map.of(), raw, "format constraint violated"),
                    Arguments.of("minLength", Map.of(), null, "minLength constraint violated"),
                    Arguments.of(null, Map.of(), null, "is invalid"));
        }
    }

    /** One result the fallback test collects, with the path its single value-free detail must carry. */
    private record FallbackCase(String label, OutputUnit result, JsonObject schema, String expectedPath) {}

    /** Builders for hand-built results in the shapes the engine's Basic output has. */
    static final class ResultShapes {

        private ResultShapes() {}

        /**
         * Builds one failing error whose raw message echoes {@link #SECRET}.
         *
         * @param keywordLocation the keyword location, possibly {@code null} or empty
         * @param instanceLocation the instance location
         * @return the error unit
         */
        static OutputUnit leaf(String keywordLocation, String instanceLocation) {
            return new OutputUnit()
                    .setValid(false)
                    .setKeywordLocation(keywordLocation)
                    .setInstanceLocation(instanceLocation)
                    .setError("the value " + SECRET + " does not satisfy an unresolvable constraint");
        }

        /**
         * Builds a result that is itself the one error, with no nested {@code errors} list.
         *
         * @param keywordLocation the keyword location, possibly {@code null} or empty
         * @param instanceLocation the instance location
         * @return the result
         */
        static OutputUnit singleError(String keywordLocation, String instanceLocation) {
            return leaf(keywordLocation, instanceLocation);
        }

        /**
         * Builds a failing result carrying the given errors in a nested {@code errors} list.
         *
         * @param errors the errors
         * @return the result
         */
        static OutputUnit multiError(OutputUnit... errors) {
            return new OutputUnit().setValid(false).setErrors(List.of(errors));
        }
    }
}
