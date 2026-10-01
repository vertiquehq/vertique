// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.corpus;

import dev.vertique.core.json.JsonProfile;
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
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The resource of {@link CorpusApi}: one {@code POST} operation per corpus fixture, each at its own
 * path and with its own operation id (the method name). Every method returns {@code void} and does
 * nothing; no member carries a security requirement, a schema rename, or a hiding marker.
 *
 * <p>{@link #strictAmounts} selects the {@code vertique-strict} profile; every other operation uses
 * the default profile. {@link #parameterRefs} has no body: its one query parameter is the input whose
 * schema the test replaces.
 */
@Path("/")
public class CorpusResource {

    /** The operation id of {@link #creatorOrder}. */
    public static final String CREATOR_ORDER = "creatorOrder";

    /** The operation id of {@link #setterOrder}. */
    public static final String SETTER_ORDER = "setterOrder";

    /** The operation id of {@link #builderOrder}. */
    public static final String BUILDER_ORDER = "builderOrder";

    /** The operation id of {@link #metadataFloor}. */
    public static final String METADATA_FLOOR = "metadataFloor";

    /** The operation id of {@link #aliases}. */
    public static final String ALIASES = "aliases";

    /** The operation id of {@link #recursiveGraph}. */
    public static final String RECURSIVE_GRAPH = "recursiveGraph";

    /** The operation id of {@link #mutualReferences}. */
    public static final String MUTUAL_REFERENCES = "mutualReferences";

    /** The operation id of {@link #sharedNestedFirst}. */
    public static final String SHARED_NESTED_FIRST = "sharedNestedFirst";

    /** The operation id of {@link #sharedNestedSecond}. */
    public static final String SHARED_NESTED_SECOND = "sharedNestedSecond";

    /** The operation id of {@link #sameNameA}. */
    public static final String SAME_NAME_A = "sameNameA";

    /** The operation id of {@link #sameNameB}. */
    public static final String SAME_NAME_B = "sameNameB";

    /** The operation id of {@link #mapValues}. */
    public static final String MAP_VALUES = "mapValues";

    /** The operation id of {@link #recursiveMap}. */
    public static final String RECURSIVE_MAP = "recursiveMap";

    /** The operation id of {@link #memberClosure}. */
    public static final String MEMBER_CLOSURE = "memberClosure";

    /** The operation id of {@link #optionalExtras}. */
    public static final String OPTIONAL_EXTRAS = "optionalExtras";

    /** The operation id of {@link #strictAmounts}. */
    public static final String STRICT_AMOUNTS = "strictAmounts";

    /** The operation id of {@link #defaultAmounts}. */
    public static final String DEFAULT_AMOUNTS = "defaultAmounts";

    /** The operation id of {@link #parameterRefs}. */
    public static final String PARAMETER_REFS = "parameterRefs";

    /** The name of {@link #parameterRefs}'s query parameter. */
    public static final String CODE = "code";

    /** Public {@code @Inject} constructor. */
    @Inject
    public CorpusResource() {}

    /**
     * Accepts a creator-bound body.
     *
     * @param body the body
     */
    @POST
    @Path("/creatorOrder")
    @Consumes(MediaType.APPLICATION_JSON)
    public void creatorOrder(CreatorOrder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a setter-bound body.
     *
     * @param body the body
     */
    @POST
    @Path("/setterOrder")
    @Consumes(MediaType.APPLICATION_JSON)
    public void setterOrder(SetterOrder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a builder-bound body.
     *
     * @param body the body
     */
    @POST
    @Path("/builderOrder")
    @Consumes(MediaType.APPLICATION_JSON)
    public void builderOrder(BuilderOrder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a body constrained by field annotations.
     *
     * @param body the body
     */
    @POST
    @Path("/metadataFloor")
    @Consumes(MediaType.APPLICATION_JSON)
    public void metadataFloor(ConstrainedOrder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a body with aliased properties.
     *
     * @param body the body
     */
    @POST
    @Path("/aliases")
    @Consumes(MediaType.APPLICATION_JSON)
    public void aliases(AliasedOrder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a self-referencing graph.
     *
     * @param body the body
     */
    @POST
    @Path("/recursiveGraph")
    @Consumes(MediaType.APPLICATION_JSON)
    public void recursiveGraph(TreeNode body) {
        // Nothing is stored.
    }

    /**
     * Accepts two mutually referencing types.
     *
     * @param body the body
     */
    @POST
    @Path("/mutualReferences")
    @Consumes(MediaType.APPLICATION_JSON)
    public void mutualReferences(LinkedParts body) {
        // Nothing is stored.
    }

    /**
     * Accepts a body using one nested type twice; {@link #sharedNestedSecond} takes the same type.
     *
     * @param body the body
     */
    @POST
    @Path("/sharedNestedFirst")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sharedNestedFirst(Route body) {
        // Nothing is stored.
    }

    /**
     * Accepts the same body type as {@link #sharedNestedFirst}.
     *
     * @param body the body
     */
    @POST
    @Path("/sharedNestedSecond")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sharedNestedSecond(Route body) {
        // Nothing is stored.
    }

    /**
     * Accepts {@code fixture.corpus.a.Item}.
     *
     * @param body the body
     */
    @POST
    @Path("/sameNameA")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sameNameA(dev.vertique.rest.openapi.docs.fixture.corpus.a.Item body) {
        // Nothing is stored.
    }

    /**
     * Accepts {@code fixture.corpus.b.Item}, which has the same simple name as {@link #sameNameA}'s body.
     *
     * @param body the body
     */
    @POST
    @Path("/sameNameB")
    @Consumes(MediaType.APPLICATION_JSON)
    public void sameNameB(dev.vertique.rest.openapi.docs.fixture.corpus.b.Item body) {
        // Nothing is stored.
    }

    /**
     * Accepts map-valued members.
     *
     * @param body the body
     */
    @POST
    @Path("/mapValues")
    @Consumes(MediaType.APPLICATION_JSON)
    public void mapValues(MapValues body) {
        // Nothing is stored.
    }

    /**
     * Accepts a self-referencing map at a named member.
     *
     * @param body the body
     */
    @POST
    @Path("/recursiveMap")
    @Consumes(MediaType.APPLICATION_JSON)
    public void recursiveMap(RecursiveMapHolder body) {
        // Nothing is stored.
    }

    /**
     * Accepts a member closed at the member level beside an open one.
     *
     * @param body the body
     */
    @POST
    @Path("/memberClosure")
    @Consumes(MediaType.APPLICATION_JSON)
    public void memberClosure(ClosureHolder body) {
        // Nothing is stored.
    }

    /**
     * Accepts {@code Optional}-valued extras.
     *
     * @param body the body
     */
    @POST
    @Path("/optionalExtras")
    @Consumes(MediaType.APPLICATION_JSON)
    public void optionalExtras(OptionalExtras body) {
        // Nothing is stored.
    }

    /**
     * Accepts nullable decimals under the {@code vertique-strict} profile.
     *
     * @param body the body
     */
    @POST
    @Path("/strictAmounts")
    @Consumes(MediaType.APPLICATION_JSON)
    @JsonProfile("vertique-strict")
    public void strictAmounts(Amounts body) {
        // Nothing is stored.
    }

    /**
     * Accepts the same body type as {@link #strictAmounts} under the default profile.
     *
     * @param body the body
     */
    @POST
    @Path("/defaultAmounts")
    @Consumes(MediaType.APPLICATION_JSON)
    public void defaultAmounts(Amounts body) {
        // Nothing is stored.
    }

    /**
     * Accepts one query parameter, whose schema the test replaces.
     *
     * @param code the parameter
     */
    @POST
    @Path("/parameterRefs")
    @Consumes(MediaType.APPLICATION_JSON)
    public void parameterRefs(@QueryParam(CODE) String code) {
        // Nothing is stored.
    }
}
