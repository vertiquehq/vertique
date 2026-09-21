// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.mcp.server;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.mcp.lifecycle.McpRequestLifecycleObserver;
import dev.vertique.mcp.lifecycle.McpRequestObservation;
import dev.vertique.mcp.lifecycle.McpRequestTerminalEvent;
import dev.vertique.mcp.lifecycle.McpRequestTerminalObservation;
import dev.vertique.mcp.server.runtime.McpToolRuntime;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactory;
import dev.vertique.mcp.server.runtime.McpToolRuntimeFactoryTestSupport;
import dev.vertique.mcp.tool.McpAccessMode;
import dev.vertique.mcp.tool.McpCancellationSignal;
import dev.vertique.mcp.tool.McpInputRejectionException;
import dev.vertique.mcp.tool.McpPreparedToolCall;
import dev.vertique.mcp.tool.McpToolAccess;
import dev.vertique.mcp.tool.McpToolAnnotations;
import dev.vertique.mcp.tool.McpToolDescriptor;
import dev.vertique.mcp.tool.McpToolInvoker;
import dev.vertique.mcp.tool.McpToolResult;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.rest.security.DefaultSecurityClaimMapper;
import dev.vertique.rest.security.IdentityResolutionMiddleware;
import dev.vertique.rest.security.SecurityPolicyEnforcer;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.resolver.SecurityIdentityResolutionContext;
import dev.vertique.security.resolver.SecurityIdentityResolver;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import jakarta.annotation.Nullable;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;
import org.hibernate.validator.HibernateValidator;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

/**
 * Framework wiring and argument shapes for {@link McpToolInputShapesIT} (T005 TP-003 to TP-005).
 *
 * <p>Every tool here is composed through the real {@link McpToolRuntimeFactory}, so its published
 * input schema is what JSON-005's {@code forInputProfile} generator produces for the argument type and
 * what {@code McpSchemaHardener} then hardens — never a hand-authored document. Each tool takes one
 * parameter, published as {@code payload}, whose type is the shape under test: the shape's own object
 * is therefore a <em>non-root</em> schema, which is exactly the position FR-014's closure rule governs
 * (the root carrier stays closed unconditionally).
 *
 * <p>The shape types mirror T004's corpus fixtures and T007's and T008's generator fixtures; they are
 * restated here because those live in other modules' test sources.
 *
 * <p>Nothing here decides Given values or asserts outcomes — that stays in the test class.
 */
final class McpToolInputShapesITFixture {

    static final String LOOPBACK = "127.0.0.1";

    // --- Tool names, one per shape ---

    static final String PRIVATE_DATE_TOOL = "shapes.privateDate";
    static final String BUILDER_TOOL = "shapes.builder";
    static final String GETTER_ONLY_LIST_TOOL = "shapes.getterOnlyList";
    static final String GETTER_ONLY_MAP_TOOL = "shapes.getterOnlyMap";
    static final String GETTER_ONLY_COLLECTION_NO_BACKING_FIELD_TOOL = "shapes.getterOnlyCollectionNoBackingField";
    static final String BG1_TOOL = "shapes.bg1LombokBuilderNoGetter";
    static final String NESTED_PRIVATE_DATE_TOOL = "shapes.nestedPrivateDate";
    static final String ANY_SETTER_NAMED_TOOL = "shapes.anySetterWithNamedProperties";
    static final String ANY_SETTER_ONLY_TOOL = "shapes.anySetterOnly";
    static final String OBJECT_ANY_SETTER_TOOL = "shapes.objectValuedAnySetter";
    static final String CLOSED_ANY_SETTER_TOOL = "shapes.classLevelFalseAnySetter";
    static final String DTO_ANY_SETTER_TOOL = "shapes.dtoValuedAnySetter";
    static final String MAP_SUBCLASS_TOOL = "shapes.mapSubclassWithAnAnySetter";
    static final String ALIASED_QUANTITY_TOOL = "shapes.aliasedQuantity";
    static final String ALIASED_QUANTITY_STRICT_TOOL = "shapes.aliasedQuantityStrict";
    static final String ALIASED_ENUM_TOOL = "shapes.aliasedEnum";
    static final String RESERVED_NAMES_TOOL = "shapes.reservedNames";
    static final String SHARED_ALIAS_TOOL = "shapes.sharedAlias";
    static final String SETTER_ONLY_TOOL = "shapes.setterOnlyName";
    static final String HIDDEN_ALIAS_TOOL = "shapes.hiddenAliasedField";
    static final String SPELLING_NAMES_HIDDEN_ANY_TOOL = "shapes.spellingNamesHiddenMemberAnySetter";
    static final String SPELLING_NAMES_HIDDEN_CLOSED_TOOL = "shapes.spellingNamesHiddenMemberClosed";
    static final String CASE_INSENSITIVE_TOOL = "shapes.caseInsensitive";
    static final String CASE_INSENSITIVE_CLOSED_TOOL = "shapes.caseInsensitiveClosed";

    /** The profile T005 TP-005's strict row selects; every other tool takes the resolver's tail. */
    static final String STRICT_PROFILE = "vertique-strict";

    private final HttpServer server;
    private final int port;
    private final Map<String, CountingToolInvoker<?>> toolsByName;
    private final List<McpRequestTerminalEvent> terminals = new CopyOnWriteArrayList<>();

    private McpToolInputShapesITFixture(Vertx vertx) throws Exception {
        McpServerConfig config = McpServerConfig.builder()
                .enabled(true)
                .serverName("vertique-test")
                .serverVersion("1.0")
                .build();
        McpToolRuntimeFactory factory = McpToolRuntimeFactoryTestSupport.factory();

        Map<String, CountingToolInvoker<?>> tools = new LinkedHashMap<>();
        register(tools, factory, PRIVATE_DATE_TOOL, PrivateDatePayload.class, null);
        register(tools, factory, BUILDER_TOOL, BuilderPayload.class, null);
        register(tools, factory, GETTER_ONLY_LIST_TOOL, GetterOnlyListPayload.class, null);
        register(tools, factory, GETTER_ONLY_MAP_TOOL, GetterOnlyMapPayload.class, null);
        register(
                tools,
                factory,
                GETTER_ONLY_COLLECTION_NO_BACKING_FIELD_TOOL,
                GetterOnlyCollectionNoBackingFieldPayload.class,
                null);
        // BG1: registered through a separate, validator-backed factory — every other tool above stays
        // on the validator-less factory, so this is the sole validator-present row.
        Validator bg1Validator = Validation.byProvider(HibernateValidator.class)
                .configure()
                .messageInterpolator(new ParameterMessageInterpolator())
                .buildValidatorFactory()
                .getValidator();
        register(
                tools,
                McpToolRuntimeFactoryTestSupport.factoryWithValidator(bg1Validator),
                BG1_TOOL,
                Bg1Payload.class,
                null);
        register(tools, factory, NESTED_PRIVATE_DATE_TOOL, NestedPrivateDatePayload.class, null);
        register(tools, factory, ANY_SETTER_NAMED_TOOL, AnySetterNamedPayload.class, null);
        register(tools, factory, ANY_SETTER_ONLY_TOOL, AnySetterOnlyPayload.class, null);
        register(tools, factory, OBJECT_ANY_SETTER_TOOL, ObjectAnySetterPayload.class, null);
        register(tools, factory, CLOSED_ANY_SETTER_TOOL, ClosedAnySetterPayload.class, null);
        register(tools, factory, DTO_ANY_SETTER_TOOL, DtoAnySetterPayload.class, null);
        register(tools, factory, MAP_SUBCLASS_TOOL, MapSubclassPayload.class, null);
        register(tools, factory, ALIASED_QUANTITY_TOOL, AliasedQuantityPayload.class, null);
        register(tools, factory, ALIASED_QUANTITY_STRICT_TOOL, AliasedQuantityPayload.class, STRICT_PROFILE);
        register(tools, factory, ALIASED_ENUM_TOOL, AliasedEnumPayload.class, null);
        register(tools, factory, RESERVED_NAMES_TOOL, ReservedNamesPayload.class, null);
        register(tools, factory, SHARED_ALIAS_TOOL, SharedAliasPayload.class, null);
        register(tools, factory, SETTER_ONLY_TOOL, SetterOnlyPayload.class, null);
        register(tools, factory, HIDDEN_ALIAS_TOOL, HiddenAliasPayload.class, null);
        register(tools, factory, SPELLING_NAMES_HIDDEN_ANY_TOOL, SpellingNamesHiddenMemberAnyPayload.class, null);
        register(tools, factory, SPELLING_NAMES_HIDDEN_CLOSED_TOOL, SpellingNamesHiddenMemberClosedPayload.class, null);
        register(tools, factory, CASE_INSENSITIVE_TOOL, CaseInsensitivePayload.class, null);
        register(tools, factory, CASE_INSENSITIVE_CLOSED_TOOL, CaseInsensitiveClosedPayload.class, null);
        this.toolsByName = Map.copyOf(tools);

        McpToolRegistry registry = McpToolRegistry.build(Set.copyOf(tools.values()));
        RecordingSecurityRuntime securityRuntime = new RecordingSecurityRuntime();
        McpPolicyEnforcer policyEnforcer = new McpPolicyEnforcer(new SecurityPolicyEnforcer(
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                new SecurityEventEmitter(Set.of()),
                NO_OP_CONTEXT_HOLDER,
                securityRuntime,
                Optional.empty()));
        HttpConfig httpConfig = HttpConfig.builder().idleTimeoutSeconds(60).build();

        McpRouterMount mount = new McpRouterMount(
                config,
                new McpServerConfigValidator(),
                new McpRequestDispatcher(
                        config,
                        securityRuntime,
                        Set.of(recordingObserver()),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        httpConfig,
                        registry,
                        policyEnforcer,
                        NO_OP_CONTEXT_HOLDER,
                        new CorrelationContextFactory(Optional.empty())),
                Set.of(),
                identityResolution(securityRuntime),
                httpConfig,
                registry);
        Router router = Router.router(vertx);
        router.route().handler(new RequestContextLifecycle());
        router.route(config.mountPath()).subRouter(await(mount.createRouter(vertx)));
        this.server = await(vertx.createHttpServer().requestHandler(router).listen(0, LOOPBACK));
        this.port = server.actualPort();
    }

    /**
     * Composes and starts one real MCP mount carrying every shape tool.
     *
     * @param vertx the Vert.x instance owning the server
     * @return the started fixture
     * @throws Exception if composition or listening fails
     */
    static McpToolInputShapesITFixture start(Vertx vertx) throws Exception {
        return new McpToolInputShapesITFixture(vertx);
    }

    HttpServer server() {
        return server;
    }

    int port() {
        return port;
    }

    /**
     * Returns the counting invoker published under {@code toolName}.
     *
     * @param toolName the published tool name
     * @return the invoker, never {@code null}
     */
    CountingToolInvoker<?> tool(String toolName) {
        CountingToolInvoker<?> tool = toolsByName.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("no fixture tool named '" + toolName + "'");
        }
        return tool;
    }

    /** Every terminal event this fixture's dispatcher has published so far, in publish order. */
    List<McpRequestTerminalEvent> terminals() {
        return terminals;
    }

    private <I> void register(
            Map<String, CountingToolInvoker<?>> tools,
            McpToolRuntimeFactory factory,
            String name,
            Class<I> carrierType,
            @Nullable String profileId) {
        McpToolRuntime<I> runtime = factory.create(
                name,
                null,
                "T005 shape fixture tool " + name + ".",
                new McpToolAnnotations(true, false, true, false),
                carrierType,
                null,
                List.of(),
                profileId == null ? null : JsonProfileId.of(profileId),
                new McpToolAccess(McpAccessMode.PERMIT_ALL, List.of(), null));
        tools.put(name, new CountingToolInvoker<>(runtime));
    }

    private McpRequestLifecycleObserver recordingObserver() {
        return startedAt -> new McpRequestObservation() {
            @Override
            public void onTerminal(McpRequestTerminalObservation observation) {
                terminals.add(observation.event());
            }
        };
    }

    private static IdentityResolutionMiddleware identityResolution(SecurityRuntime securityRuntime) {
        return new IdentityResolutionMiddleware(
                Set.of(new AnonymousOnlyIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                securityRuntime,
                NO_OP_CONTEXT_HOLDER);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Stands in for a generated invoker: it materializes the arguments through the tool's own effective
     * profile mapper (the generated fixed input boundary's stage 3) and counts every genuine
     * invocation, so a test can prove a rejection happened before the handler ran.
     *
     * <p>A materialization failure is reported as {@link McpInputRejectionException}, which the
     * dispatcher classifies as {@code INPUT_PROCESSING} — never {@code INPUT_VALIDATION}. That is what
     * keeps a binder rejection distinguishable from the stage-1 schema rejection every T005 proof
     * asserts.
     *
     * @param <I> the input-carrier record type
     */
    static final class CountingToolInvoker<I> implements McpToolInvoker {

        private final McpToolRuntime<I> runtime;
        private final AtomicInteger prepareCallCount = new AtomicInteger();
        private final AtomicInteger invocationCount = new AtomicInteger();
        private final AtomicReference<I> lastPayload = new AtomicReference<>();

        CountingToolInvoker(McpToolRuntime<I> runtime) {
            this.runtime = runtime;
        }

        @Override
        public McpToolDescriptor descriptor() {
            return runtime.descriptor();
        }

        @Override
        public McpPreparedToolCall prepare(Map<String, Object> arguments, McpCancellationSignal cancellation) {
            prepareCallCount.incrementAndGet();
            I materialized;
            try {
                materialized = runtime.materializeArguments(arguments);
            } catch (RuntimeException materializationFailure) {
                throw new McpInputRejectionException(
                        "Invalid tool arguments: materialization failed", materializationFailure);
            }
            return new McpPreparedToolCall() {
                @Override
                public Map<String, Object> normalizedArguments() {
                    return Map.copyOf(arguments);
                }

                @Override
                public Future<McpToolResult<?>> invoke() {
                    invocationCount.incrementAndGet();
                    lastPayload.set(materialized);
                    return Future.succeededFuture(McpToolResult.text("ok"));
                }
            };
        }

        /** The published, hardened input schema of this tool. */
        String inputSchema() {
            return runtime.descriptor().inputSchema();
        }

        int prepareCallCount() {
            return prepareCallCount.get();
        }

        int invocationCount() {
            return invocationCount.get();
        }

        /** The carrier the last genuine invocation received, or {@code null} if none ran. */
        @Nullable
        I lastPayload() {
            return lastPayload.get();
        }
    }

    // --- Security wiring (anonymous-only, no configured scheme) ---

    private static final ContextHolder NO_OP_CONTEXT_HOLDER = new ContextHolder() {
        @Override
        public <T> Optional<T> current(Class<T> type) {
            return Optional.empty();
        }

        @Override
        public <T extends ContextValue> Scope bind(Class<T> type, T value) {
            return () -> {};
        }
    };

    private static final class RecordingSecurityRuntime implements SecurityRuntime {
        private volatile SecurityContext bound;

        @Override
        public SecurityContext current() {
            return bound;
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext context) {
            bound = context;
            return () -> {};
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext context, boolean secure) {
            return null;
        }
    }

    private record AnonymousOnlyIdentityResolver() implements SecurityIdentityResolver {
        @Override
        public Future<Optional<SecurityIdentity>> resolve(SecurityIdentityResolutionContext context) {
            return Future.succeededFuture(Optional.of(SecurityIdentity.anonymous()));
        }
    }

    // --- Carriers: the generated shape, one parameter published as "payload" ---

    record PrivateDatePayload(@JsonProperty("payload") PrivateDatePropertyDto argument0) {}

    record BuilderPayload(@JsonProperty("payload") LombokBuilderDto argument0) {}

    record GetterOnlyListPayload(@JsonProperty("payload") GetterOnlyListDto argument0) {}

    record GetterOnlyMapPayload(@JsonProperty("payload") GetterOnlyMapDto argument0) {}

    record GetterOnlyCollectionNoBackingFieldPayload(
            @JsonProperty("payload") GetterOnlyCollectionNoBackingFieldDto argument0) {}

    record Bg1Payload(@JsonProperty("payload") Bg1Dto argument0) {}

    record NestedPrivateDatePayload(@JsonProperty("payload") NestedPrivateDateDto argument0) {}

    record AnySetterNamedPayload(@JsonProperty("payload") FieldAnySetterOverStrings argument0) {}

    record AnySetterOnlyPayload(@JsonProperty("payload") AnySetterOnlyOverIntegers argument0) {}

    record ObjectAnySetterPayload(@JsonProperty("payload") AnySetterOverObjects argument0) {}

    record ClosedAnySetterPayload(@JsonProperty("payload") ClosedAnySetterType argument0) {}

    record DtoAnySetterPayload(@JsonProperty("payload") AnySetterOverDtos argument0) {}

    record MapSubclassPayload(@JsonProperty("payload") MapSubclassWithAnAnySetter argument0) {}

    record AliasedQuantityPayload(@JsonProperty("payload") AliasedAnySetterType argument0) {}

    record AliasedEnumPayload(@JsonProperty("payload") AliasedEnumAnySetterType argument0) {}

    record ReservedNamesPayload(@JsonProperty("payload") ReservedNamesAnySetterType argument0) {}

    record SharedAliasPayload(@JsonProperty("payload") ContestedSpellingWithAnySetter argument0) {}

    record SetterOnlyPayload(@JsonProperty("payload") SetterOnlyNameAnySetterType argument0) {}

    record HiddenAliasPayload(@JsonProperty("payload") HiddenAliasedFieldAnySetterType argument0) {}

    record SpellingNamesHiddenMemberAnyPayload(
            @JsonProperty("payload") SpellingNamesAHiddenPropertyOnAnySetterType argument0) {}

    record SpellingNamesHiddenMemberClosedPayload(
            @JsonProperty("payload") SpellingNamesAHiddenProperty argument0) {}

    record CaseInsensitivePayload(@JsonProperty("payload") CaseInsensitiveAnySetterType argument0) {}

    record CaseInsensitiveClosedPayload(
            @JsonProperty("payload") CaseInsensitiveClosedType argument0) {}

    // --- TP-003 shapes: the five AC-013.1 input-discovery shapes, as T004's corpus defines them ---

    /** A private field Jackson fills through reflection, reachable only through a getter. */
    static final class PrivateDatePropertyDto {

        private LocalDate due;

        /**
         * Returns the due date.
         *
         * @return the due date
         */
        public LocalDate getDue() {
            return due;
        }
    }

    /** A Lombok builder type whose getters the real annotation processor generates. */
    @Builder
    @Jacksonized
    @Getter
    static class LombokBuilderDto {

        /** The string property a well-typed value is sent to. */
        private String name;

        /** The integer property the numeric string {@code "2"} is posted to. */
        private Integer quantity;
    }

    /** A getter-only {@code List<String>} with a backing field, so Jackson fills it. */
    static final class GetterOnlyListDto {

        private List<String> tags;

        /**
         * Returns the tags.
         *
         * @return the tags
         */
        public List<String> getTags() {
            return tags;
        }
    }

    /** A getter-only {@code Map<String, String>} with a backing field, so Jackson fills it. */
    static final class GetterOnlyMapDto {

        private Map<String, String> labels;

        /**
         * Returns the labels.
         *
         * @return the labels
         */
        public Map<String, String> getLabels() {
            return labels;
        }
    }

    /**
     * H4: a getter-only {@code List<Integer>} with no backing field named {@code items} at all — its
     * only storage is {@link #internal}, an unrelated field name Jackson populates in place through
     * the getter (no setter is declared).
     */
    static final class GetterOnlyCollectionNoBackingFieldDto {

        private final List<Integer> internal = new ArrayList<>();

        /**
         * Returns the live, mutable backing list.
         *
         * @return the items
         */
        public List<Integer> getItems() {
            return internal;
        }
    }

    /**
     * BG1: a Lombok {@code @Builder @Jacksonized} type with a constrained private field and
     * deliberately no getter. Jackson's own introspection does not see {@code name} as a property at
     * all without a public accessor, so the validator-less floor's builder-constraint borrow finds
     * nothing to borrow; Bean Validation is unaffected, since it reads the constrained field directly
     * by Java name. Registered only through the validator-backed factory ({@link #BG1_TOOL}).
     */
    @Builder
    @Jacksonized
    static final class Bg1Dto {

        @Size(max = 5)
        private final String name;
    }

    /** A type holding {@link PrivateDatePropertyDto}, so the nested position is covered too. */
    static final class NestedPrivateDateDto {

        private PrivateDatePropertyDto detail;

        /**
         * Returns the nested detail.
         *
         * @return the nested detail
         */
        public PrivateDatePropertyDto getDetail() {
            return detail;
        }
    }

    // --- TP-004 shapes: any-setter types ---

    /** A named property beside a field-level any-setter over {@code String} values. */
    static final class FieldAnySetterOverStrings {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A type that is only an any-setter, over {@code Integer} values: it publishes no property. */
    static final class AnySetterOnlyOverIntegers {

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Integer> extras = new LinkedHashMap<>();
    }

    /** A named property beside an any-setter whose value type is unconstrained. */
    static final class AnySetterOverObjects {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** An any-setter type the application declared closed at class level. */
    @Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
    static final class ClosedAnySetterType {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** A named property beside an any-setter whose value type is a plain DTO (design proof v2, V07). */
    static final class AnySetterOverDtos {

        /** An ordinary property. */
        public String name;

        /** The any-setter's backing storage, whose value type has properties of its own. */
        @JsonAnySetter
        public Map<String, ExtraDetail> extras = new LinkedHashMap<>();
    }

    /** The plain DTO used as an any-setter's value type. */
    static final class ExtraDetail {

        /** The DTO's only property. */
        public String name;
    }

    /** A {@code HashMap} subclass that also declares an any-setter Jackson never routes a key to. */
    static class MapSubclassWithAnAnySetter extends HashMap<String, String> {

        private static final long serialVersionUID = 1L;

        /** A named field beside the map's own entries. */
        public String name;

        /**
         * The any-setter Jackson ignores on a map-like type.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void put2(String key, Integer value) {
            // Never invoked: Jackson binds this type as a map.
        }
    }

    // --- TP-005 shapes: aliases, reserved names, and storage names ---

    /** An any-setter type with one aliased, constrained property and unconstrained extras. */
    static final class AliasedAnySetterType {

        /** The aliased, constrained property. */
        @JsonAlias("qty")
        @Max(10)
        public Integer quantity;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /** An any-setter type with an aliased enum property carrying a default constant. */
    static final class AliasedEnumAnySetterType {

        /** The aliased enum property. */
        @JsonAlias("r")
        public AccessRole role;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An enum whose unknown values the built-in profiles bind to its default constant. */
    enum AccessRole {
        /** The one published constant beside the default. */
        USER,

        /** The fallback an unknown spelling binds to under the built-in profiles. */
        @JsonEnumDefaultValue
        UNKNOWN
    }

    /**
     * An any-setter type carrying a read-only name, an ignored name, and a method
     * {@code @JsonAnyGetter} over the private storage field {@code extras} (design proof v4, SG1).
     */
    static final class ReservedNamesAnySetterType {

        /** An ordinary property. */
        public String name;

        /** Never bound by name and never published: a client key named {@code id} lands in the map. */
        @JsonIgnore
        public String id;

        /** Server-assigned: never accepted on input, never published. */
        @JsonProperty(access = JsonProperty.Access.READ_ONLY)
        public String role;

        /** The storage the any-getter returns and Jackson fills through it. */
        private final Map<String, String> extras = new LinkedHashMap<>();

        /**
         * Collects every extra key.
         *
         * @param key   the extra key
         * @param value the extra value
         */
        @JsonAnySetter
        public void putExtra(String key, String value) {
            extras.put(key, value);
        }

        /**
         * Returns the collected extras.
         *
         * @return the extras
         */
        @JsonAnyGetter
        public Map<String, String> getExtras() {
            return extras;
        }
    }

    /**
     * A class-level case-insensitively bound any-setter type with a reserved name (Change 3): {@code
     * name} is published under both its canonical spelling and an ASCII case-folded
     * {@code patternProperties} pattern, and {@code secretKey} is reserved by a folded pattern too, so
     * a key under another casing must still route to the right place — accepted for {@code name},
     * rejected for {@code secretKey} — while the MCP hardener's root closure still rejects a key that
     * matches neither.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveAnySetterType {

        /** Published under "name" and every ASCII casing of it. */
        @Size(max = 3)
        public String name;

        /** Never bound by name, never published: reserved under every ASCII casing of "secretKey". */
        @JsonIgnore
        public String secretKey;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, Object> extras = new LinkedHashMap<>();
    }

    /**
     * A case-insensitively bound, otherwise closed (no any-setter) type — the decisive shape for the
     * MCP hardener requirement: since it declares no {@code additionalProperties} of its own, the
     * hardener closes it with {@code additionalProperties: false}, and the requirement is that a key
     * matching {@code patternProperties} must still be accepted through that closure while a key
     * matching neither {@code properties} nor {@code patternProperties} is rejected.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
    static final class CaseInsensitiveClosedType {

        /** Published under "name" and every ASCII casing of it. */
        @Size(max = 3)
        public String name;
    }

    /** An any-setter type declaring one alias spelling on two properties (design proof v7, DA1/DA2). */
    static final class ContestedSpellingWithAnySetter {

        /** The unconstrained claimant of the contested spelling. */
        @JsonAlias("x")
        public String a;

        /** The constrained claimant of the same spelling. */
        @JsonAlias("x")
        @Size(max = 3)
        public String b;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /** An any-setter type whose {@code admin} is bound only by a setter (design proof v7, SO1). */
    static final class SetterOnlyNameAnySetterType {

        /** An ordinary property. */
        public String name;

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

    /** An any-setter type with a hidden, aliased, constrained field (design proof v9, AH1). */
    static final class HiddenAliasedFieldAnySetterType {

        /** An ordinary property. */
        public String name;

        /** Never published, still bound by Jackson under both spellings. */
        @Schema(hidden = true)
        @Max(10)
        @JsonAlias("lvl")
        public Integer level;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * T010 CO-007's measured shape: a published property whose alias spelling is already the name of a
     * member the document never publishes, on an any-setter type.
     *
     * <p>The two bounds differ so a document publishing the spelling is visible as the wrong number:
     * {@code secret} published with {@code level}'s schema admits {@code 99}, which the member's own
     * {@code @Max(3)} forbids.
     */
    static final class SpellingNamesAHiddenPropertyOnAnySetterType {

        /** The aliasing property, published, whose spelling is the hidden member's own name. */
        @Max(10)
        @JsonAlias("secret")
        public Integer level;

        /** Hidden from the document, still bound by Jackson under its own name. */
        @Schema(hidden = true)
        @Max(3)
        public Integer secret;

        /** The any-setter's backing storage. */
        @JsonAnySetter
        public Map<String, String> extras = new LinkedHashMap<>();
    }

    /**
     * The same shape with no any-setter, whose key the hardener's closed object refused in 0.2.0: this
     * tool is the regression row rather than a missed tightening.
     */
    static final class SpellingNamesAHiddenProperty {

        /** The aliasing property, published, whose spelling is the hidden member's own name. */
        @Max(10)
        @JsonAlias("secret")
        public Integer level;

        /** Hidden from the document, still bound by Jackson under its own name. */
        @Schema(hidden = true)
        @Max(3)
        public Integer secret;
    }
}
