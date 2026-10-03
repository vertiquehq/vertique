// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus;

import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.AliasedOrder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.Amounts;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.BuilderOrder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.ClosureHolder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.ConstrainedOrder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.CreatorOrder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.LinkedParts;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.MapValues;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.OptionalExtras;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.RecursiveMapHolder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.Route;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.SetterOrder;
import dev.vertique.rest.openapi.docs.fixture.corpus.CorpusBodies.TreeNode;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * The fixtures of the embedding-equivalence corpus: per operation of {@link CorpusResource}, the input
 * whose schema is compared and a fixed list of JSON instances.
 *
 * <p>Each instance list mixes values the gate's schema accepts and values it rejects, chosen so that
 * every reference of the schema is traversed, the failing ones as deep as the shape allows: a
 * violation inside a nested definition, only in the second of two members of one type, at the
 * innermost level of a reference cycle, or reachable only through one particular reference. Lists
 * also cover closed objects, {@code null} values, and decimal forms, and no instance uses a reserved
 * name as a key. Whether an instance is valid is never written here: the test reads it from the
 * gate's own schema.
 */
public final class CorpusFixtures {

    /** The id of the default built-in profile. */
    public static final String DEFAULT_PROFILE = "vertique";

    /** The id of the strict built-in profile. */
    public static final String STRICT_PROFILE = "vertique-strict";

    private CorpusFixtures() {}

    /**
     * One fixture: an operation, the input of it whose schema is compared, and its instances.
     *
     * @param name          the fixture's display name
     * @param operationId   the operation id
     * @param bodyType      the request body type, or {@code null} when the compared input is a query
     *                      parameter
     * @param profileId     the profile the operation resolves
     * @param queryParameter the compared query parameter's name, or {@code null} when the compared
     *                      input is the body
     * @param instances     the instances, each as JSON text
     */
    public record CorpusFixture(
            String name,
            String operationId,
            @Nullable Class<?> bodyType,
            String profileId,
            @Nullable String queryParameter,
            List<String> instances) {

        /** Checks that exactly one of body type and query parameter is set. */
        public CorpusFixture {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(profileId, "profileId");
            if ((bodyType == null) == (queryParameter == null)) {
                throw new IllegalArgumentException("a fixture compares either its body or one query parameter");
            }
            instances = List.copyOf(instances);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * Returns the schema the recording source returns for {@link CorpusResource#parameterRefs}'s
     * query parameter instead of the canonical one: local definitions, a reference to a definition, a
     * reference into a definition, a reference into the root, and a reference to the root itself. Its
     * meaning: a lowercase code of at most eight letters; or an entry object with a code, an optional
     * positive rank, and an optional next value of this same schema, and nothing else; or an array of
     * at most three codes; or an object holding only {@code groups}, an array of such code arrays.
     *
     * @return a fresh object
     */
    public static JsonObject parameterSchema() {
        return new JsonObject("{"
                + "\"$defs\":{"
                + "\"Code\":{\"type\":\"string\",\"pattern\":\"^[a-z]+$\",\"maxLength\":8},"
                + "\"Entry\":{\"type\":\"object\","
                + "\"properties\":{\"code\":{\"$ref\":\"#/$defs/Code\"},"
                + "\"rank\":{\"type\":\"integer\",\"minimum\":1},"
                + "\"next\":{\"$ref\":\"#\"}},"
                + "\"required\":[\"code\"],\"additionalProperties\":false}},"
                + "\"anyOf\":["
                + "{\"$ref\":\"#/$defs/Code\"},"
                + "{\"$ref\":\"#/$defs/Entry\"},"
                + "{\"type\":\"array\",\"maxItems\":3,\"items\":{\"$ref\":\"#/$defs/Entry/properties/code\"}},"
                + "{\"type\":\"object\",\"required\":[\"groups\"],\"additionalProperties\":false,"
                + "\"properties\":{\"groups\":{\"type\":\"array\",\"items\":{\"$ref\":\"#/anyOf/2\"}}}}"
                + "]}");
    }

    /** Instances of both same-named {@code Item} types: a code valid for one is invalid for the other. */
    private static final List<String> SAME_NAME_INSTANCES = List.of(
            "{\"primary\":{\"code\":\"ab\"},\"secondary\":{\"code\":\"abc\"}}",
            "{\"primary\":{\"code\":\"12345\"},\"secondary\":{\"code\":\"1\"}}",
            "{\"secondary\":{\"code\":\"1\"}}",
            "{\"primary\":{\"code\":\"1\"},\"secondary\":{\"code\":\"abcd\"}}",
            "{\"secondary\":{\"code\":7}}",
            "{\"primary\":\"x\"}",
            "{}");

    /** Instances of the type used by both shared-type operations. */
    private static final List<String> ROUTE_INSTANCES = List.of(
            "{\"from\":{\"city\":\"Oulu\"},\"to\":{\"city\":\"Pori\",\"zip\":\"28100\"}}",
            "{\"from\":{\"city\":\"Oulu\"},\"to\":{\"city\":\"Pori\",\"zip\":\"2810\"}}",
            "{\"from\":{\"city\":\"Oulu\"},\"to\":{\"zip\":\"28100\"}}",
            "{\"from\":{\"city\":\"Helsinki\"}}",
            "{\"to\":{\"city\":\"Vaasa\",\"zip\":\"65100\"}}",
            "{\"to\":{\"city\":\"Kemi\",\"zip\":65100}}",
            "{}",
            "[]");

    /** Instances of the nullable-decimal type, exercising decimal forms on the wire. */
    private static final List<String> AMOUNT_INSTANCES = List.of(
            "{\"amount\":\"12.50\"}",
            "{\"amount\":\"-0.5\"}",
            "{\"amount\":\"1e3\"}",
            "{\"amount\":\"12.\"}",
            "{\"amount\":12.5}",
            "{\"amount\":1000}",
            "{\"amount\":1e3}",
            "{\"amount\":null}",
            "{\"amount\":true}",
            "{\"note\":null}",
            "{\"note\":\"n\"}",
            "{\"note\":5}",
            "{}");

    /**
     * Returns every fixture, in a fixed order.
     *
     * @return the fixtures
     */
    public static List<CorpusFixture> all() {
        return List.of(
                body(
                        "creator-bound body",
                        CorpusResource.CREATOR_ORDER,
                        CreatorOrder.class,
                        List.of(
                                "{\"quantity\":3,\"label\":\"ab\"}",
                                "{\"quantity\":5,\"label\":\"abcd\"}",
                                "{\"quantity\":6,\"label\":\"ab\"}",
                                "{\"quantity\":1,\"label\":\"abcde\"}",
                                "{\"quantity\":\"3\"}",
                                "{\"quantity\":2,\"label\":null}",
                                "{}",
                                "null")),
                body(
                        "setter-bound body",
                        CorpusResource.SETTER_ORDER,
                        SetterOrder.class,
                        List.of(
                                "{\"amount\":1,\"code\":\"ab\"}",
                                "{\"amount\":0}",
                                "{\"amount\":-1}",
                                "{\"code\":\"abcd\"}",
                                "{\"code\":\"abc\"}",
                                "{\"amount\":1.5}",
                                "{}")),
                body(
                        "builder-bound body",
                        CorpusResource.BUILDER_ORDER,
                        BuilderOrder.class,
                        List.of(
                                "{\"amount\":5}",
                                "{\"amount\":6}",
                                "{\"amount\":2,\"note\":\"x\"}",
                                "{\"note\":3}",
                                "{\"amount\":\"2\"}",
                                "{}")),
                body(
                        "constraints without a validator",
                        CorpusResource.METADATA_FLOOR,
                        ConstrainedOrder.class,
                        List.of(
                                "{\"code\":\"ab\"}",
                                "{\"code\":\"ab\",\"level\":9,\"slug\":\"abc\"}",
                                "{}",
                                "{\"code\":\"a\"}",
                                "{\"code\":\"abcdef\"}",
                                "{\"code\":\"ab\",\"level\":10}",
                                "{\"code\":\"ab\",\"level\":0}",
                                "{\"code\":\"ab\",\"level\":1.5}",
                                "{\"code\":\"ab\",\"slug\":\"ABC\"}",
                                "{\"code\":null}")),
                body(
                        "aliased properties",
                        CorpusResource.ALIASES,
                        AliasedOrder.class,
                        List.of(
                                "{\"quantity\":3}",
                                "{\"qty\":3}",
                                "{\"qty\":11}",
                                "{\"quantity\":11}",
                                "{\"quantity\":2,\"nm\":\"ab\"}",
                                "{\"quantity\":1,\"nm\":\"abcd\"}",
                                "{\"quantity\":1,\"name\":\"abcd\"}",
                                "{\"quantity\":1,\"qty\":2}",
                                "{\"name\":\"ab\"}",
                                "{}")),
                body(
                        "self-referencing graph",
                        CorpusResource.RECURSIVE_GRAPH,
                        TreeNode.class,
                        List.of(
                                "{\"value\":\"a\"}",
                                "{\"value\":\"a\",\"parent\":{\"value\":\"b\",\"parent\":{\"value\":\"c\"}}}",
                                "{\"value\":\"a\",\"parent\":{\"value\":1}}",
                                "{\"value\":\"a\",\"parent\":{\"value\":\"b\",\"parent\":{\"value\":\"long\"}}}",
                                "{\"value\":\"a\",\"parent\":{\"parent\":{\"parent\":\"x\"}}}",
                                "{\"parent\":\"x\"}",
                                "{\"value\":\"a\",\"parent\":null}",
                                "{\"value\":\"long\"}")),
                body(
                        "mutually referencing types",
                        CorpusResource.MUTUAL_REFERENCES,
                        LinkedParts.class,
                        List.of(
                                "{\"first\":{\"label\":\"a\",\"next\":{\"count\":1,\"back\":{\"label\":\"b\"}}}}",
                                "{\"first\":{\"label\":\"a\",\"next\":{\"count\":1,\"back\":{\"label\":\"long\"}}}}",
                                "{\"second\":{\"count\":2,\"back\":{\"label\":\"ab\",\"next\":{\"count\":3}}}}",
                                "{\"second\":{\"count\":1,\"back\":{\"label\":\"a\",\"next\":{\"count\":10}}}}",
                                "{\"first\":{\"next\":{\"back\":{\"next\":{\"count\":\"x\"}}}}}",
                                "{\"first\":{\"next\":{\"back\":{\"next\":{\"back\":{\"label\":\"abc\"}}}}}}",
                                "{\"second\":{\"back\":{\"next\":{\"back\":{\"label\":7}}}}}",
                                "{\"first\":[]}",
                                "{}")),
                body("one nested type used twice", CorpusResource.SHARED_NESTED_FIRST, Route.class, ROUTE_INSTANCES),
                body(
                        "the same body type in a second operation",
                        CorpusResource.SHARED_NESTED_SECOND,
                        Route.class,
                        ROUTE_INSTANCES),
                body(
                        "same simple name, first package",
                        CorpusResource.SAME_NAME_A,
                        dev.vertique.rest.openapi.docs.fixture.corpus.a.Item.class,
                        SAME_NAME_INSTANCES),
                body(
                        "same simple name, second package",
                        CorpusResource.SAME_NAME_B,
                        dev.vertique.rest.openapi.docs.fixture.corpus.b.Item.class,
                        SAME_NAME_INSTANCES),
                body(
                        "map values",
                        CorpusResource.MAP_VALUES,
                        MapValues.class,
                        List.of(
                                "{\"codes\":{\"a\":\"xy\"},\"limits\":{\"x\":{\"max\":3}},\"fallback\":{\"max\":10}}",
                                "{\"codes\":{\"a\":\"long\"}}",
                                "{\"codes\":{\"a\":1}}",
                                "{\"limits\":{\"x\":{\"max\":3},\"y\":{\"max\":11}}}",
                                "{\"fallback\":{\"max\":11}}",
                                "{\"limits\":{\"x\":null}}",
                                "{\"codes\":{\"a\":null}}",
                                "{\"limits\":[]}",
                                "{}")),
                body(
                        "self-referencing map",
                        CorpusResource.RECURSIVE_MAP,
                        RecursiveMapHolder.class,
                        List.of(
                                "{\"tree\":{}}",
                                "{\"tree\":{\"a\":{\"b\":{}}}}",
                                "{\"tree\":{\"a\":{\"b\":1}}}",
                                "{\"tree\":{\"a\":{\"b\":{\"c\":\"x\"}}}}",
                                "{\"tree\":{\"a\":\"x\"}}",
                                "{\"tree\":[]}",
                                "{}")),
                body(
                        "member-level closure",
                        CorpusResource.MEMBER_CLOSURE,
                        ClosureHolder.class,
                        List.of(
                                "{\"closed\":{},\"open\":{\"k\":\"v\"}}",
                                "{\"closed\":{\"k\":\"v\"}}",
                                "{\"open\":{\"k\":1}}",
                                "{\"open\":{}}",
                                "{\"closed\":{},\"open\":{\"k\":\"v\",\"j\":null}}",
                                "{\"closed\":\"x\"}",
                                "{}")),
                body(
                        "optional-valued extras",
                        CorpusResource.OPTIONAL_EXTRAS,
                        OptionalExtras.class,
                        List.of(
                                "{}",
                                "{\"a\":{\"name\":\"ab\"}}",
                                "{\"a\":null}",
                                "{\"a\":{\"name\":\"abcd\"}}",
                                "{\"a\":{\"name\":\"ab\"},\"b\":{\"name\":\"abcd\"}}",
                                "{\"a\":\"x\"}",
                                "[]")),
                new CorpusFixture(
                        "nullable decimals, strict profile",
                        CorpusResource.STRICT_AMOUNTS,
                        Amounts.class,
                        STRICT_PROFILE,
                        null,
                        AMOUNT_INSTANCES),
                body(
                        "nullable decimals, default profile",
                        CorpusResource.DEFAULT_AMOUNTS,
                        Amounts.class,
                        AMOUNT_INSTANCES),
                new CorpusFixture(
                        "replaced query parameter schema",
                        CorpusResource.PARAMETER_REFS,
                        null,
                        DEFAULT_PROFILE,
                        CorpusResource.CODE,
                        List.of(
                                "\"abc\"",
                                "\"ABC\"",
                                "\"abcdefghi\"",
                                "{\"code\":\"ab\",\"rank\":2}",
                                "{\"code\":\"ab\",\"rank\":0}",
                                "{\"rank\":2}",
                                "{\"code\":\"ab\",\"other\":1}",
                                "{\"code\":\"ab\",\"next\":\"cd\"}",
                                "{\"code\":\"ab\",\"next\":{\"code\":\"cd\",\"next\":[\"ef\"]}}",
                                "{\"code\":\"ab\",\"next\":{\"code\":\"CD\"}}",
                                "[\"ab\",\"cd\"]",
                                "[\"ab\",\"CD\"]",
                                "[\"a\",\"b\",\"c\",\"d\"]",
                                "{\"groups\":[[\"ab\"],[\"cd\",\"ef\"]]}",
                                "{\"groups\":[[\"AB\"]]}",
                                "{\"groups\":[[\"a\",\"b\",\"c\",\"d\"]]}",
                                "5",
                                "null")));
    }

    private static CorpusFixture body(String name, String operationId, Class<?> bodyType, List<String> instances) {
        return new CorpusFixture(name, operationId, bodyType, DEFAULT_PROFILE, null, instances);
    }
}
