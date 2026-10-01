// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs.fixture.disclosure.unit;

import dev.vertique.rest.jaxrs.publication.CapturedSchemas;
import dev.vertique.rest.jaxrs.publication.InputBinding;
import dev.vertique.rest.jaxrs.publication.InputBinding.Origin;
import dev.vertique.rest.jaxrs.publication.MountPublication;
import dev.vertique.rest.jaxrs.publication.OperationDetail;
import dev.vertique.rest.jaxrs.publication.OperationPublication;
import dev.vertique.rest.jaxrs.routing.ParamLocation;
import dev.vertique.rest.openapi.docs.fixture.input.Publications;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rewrites a built synthetic publication's inventory with the facts {@link Publications} does not
 * set: the {@code hidden} flag of any binding, the body binding's real Java type, explicit {@code
 * schemaEnforced} values, the mount's strategy id, an operation's gate flag, and the captured body
 * and its provenance. {@link Publications} itself is unchanged; its built publication is only read.
 *
 * <pre>{@code
 * Publications.Built built = DisclosurePublications.from(Publications.mount("/api/orders/*")
 *                 .application("orders", UnitDocumentedApi.class)
 *                 .operation("GET", "/items/{id}", "getItemZx")
 *                     .param(ParamLocation.PATH, "id", Requiredness.REQUIRED)
 *                     .param(ParamLocation.QUERY, "debugZx", Requiredness.UNKNOWN)
 *                 .operation("POST", "/items", "createItemZx")
 *                     .consumes("application/json")
 *                     .body(GeneratedBodies.describe(NotesZx.class))
 *                 .build())
 *         .hidden("getItemZx", ParamLocation.QUERY, "debugZx")
 *         .bodyType("createItemZx", NotesZx.class)
 *         .build();
 * }</pre>
 *
 * <p>Selecting bindings: by location and name ({@link #hidden(String, ParamLocation, String)}),
 * which must match exactly one binding of the operation, whatever its origin (a parameter or a
 * composite field); by inventory index ({@link #hiddenAt}), for an operation with two bindings of
 * one location and name; or the operation's body ({@link #hiddenBody}).
 *
 * <p>What {@link Publications} already covers, and needs no rewrite: a generated body with its
 * manifest ({@code body(GeneratedBodies.describe(type))}); a body captured with any JSON and a {@code
 * RedactionManifest} or no provenance ({@code bodySchema(JsonObject, manifest-or-null)}), which gives
 * the manifest-free, replaced, and edited cases; a body binding without a captured schema; the
 * per-operation gate and profile, and the mount's strategy, with consistent {@code schemaEnforced}
 * flags. This class adds a provenance that is not a {@code RedactionManifest} ({@link
 * #bodyProvenance}, for example a {@code String}), and the flags above.
 *
 * <p>Overrides of the strategy ({@link #strategy}) and of the gate flag ({@link #gateInstalled})
 * change only that flag: no binding's {@code schemaEnforced} is recomputed. Prefer {@link
 * Publications}' own {@code strategy} and {@code gateInstalled} for consistent flags, and use these
 * only to build a deliberately inconsistent publication or together with {@link #schemaEnforced}.
 *
 * <p>Every rewritten binding keeps all its other components; every operation keeps its order,
 * route, policy, profile, response shape, and captured parameter schemas (the very objects, not
 * copies). The descriptor facts of {@link Publications.Built} (consumed media types and named file
 * parts) are carried over unchanged. An unknown operation id or an unmatched binding throws {@link
 * IllegalStateException} at the call that names it.
 */
public final class DisclosurePublications {

    /** Marks a provenance override that sets no provenance at all. */
    private static final Object NO_PROVENANCE = new Object();

    private final Publications.Built source;
    private @Nullable String strategyId;
    private final Map<String, OperationEdits> edits = new LinkedHashMap<>();

    private DisclosurePublications(Publications.Built source) {
        this.source = Objects.requireNonNull(source, "source");
        for (OperationPublication operation : source.publication().operations()) {
            requireDetail(operation);
            edits.put(operation.operationId(), new OperationEdits(operation));
        }
    }

    /**
     * Starts rewriting a built publication.
     *
     * @param built the publication built by {@link Publications}
     * @return a new rewriter; the built publication is not changed
     */
    public static DisclosurePublications from(Publications.Built built) {
        return new DisclosurePublications(built);
    }

    /**
     * Flags the one binding of an operation with the given location and name hidden.
     *
     * @param operationId the operation id
     * @param location    the binding's location
     * @param name        the binding's name
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or not exactly one binding matches
     */
    public DisclosurePublications hidden(String operationId, ParamLocation location, String name) {
        OperationEdits operation = operation(operationId);
        operation.hidden.add(operation.indexOf(location, name));
        return this;
    }

    /**
     * Flags the binding at an inventory index hidden.
     *
     * @param operationId the operation id
     * @param index       the binding's index in the operation's inventory, in call order of the
     *     {@link Publications} builder
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or the index is out of range
     */
    public DisclosurePublications hiddenAt(String operationId, int index) {
        OperationEdits operation = operation(operationId);
        operation.hidden.add(operation.checkIndex(index));
        return this;
    }

    /**
     * Flags the operation's body binding hidden.
     *
     * @param operationId the operation id
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public DisclosurePublications hiddenBody(String operationId) {
        OperationEdits operation = operation(operationId);
        operation.hidden.add(operation.bodyIndex());
        return this;
    }

    /**
     * Sets the Java type of the operation's body binding; {@link Publications} sets {@code
     * Object.class}.
     *
     * @param operationId the operation id
     * @param type        the body type, for example the DTO whose schema was captured
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public DisclosurePublications bodyType(String operationId, Type type) {
        OperationEdits operation = operation(operationId);
        operation.types.put(operation.bodyIndex(), Objects.requireNonNull(type, "type"));
        return this;
    }

    /**
     * Sets the {@code schemaEnforced} flag of the one binding with the given location and name.
     *
     * @param operationId the operation id
     * @param location    the binding's location
     * @param name        the binding's name
     * @param enforced    the flag
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or not exactly one binding matches
     */
    public DisclosurePublications schemaEnforced(
            String operationId, ParamLocation location, String name, boolean enforced) {
        OperationEdits operation = operation(operationId);
        operation.enforced.put(operation.indexOf(location, name), enforced);
        return this;
    }

    /**
     * Sets the {@code schemaEnforced} flag of the binding at an inventory index.
     *
     * @param operationId the operation id
     * @param index       the binding's index in the operation's inventory
     * @param enforced    the flag
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or the index is out of range
     */
    public DisclosurePublications schemaEnforcedAt(String operationId, int index, boolean enforced) {
        OperationEdits operation = operation(operationId);
        operation.enforced.put(operation.checkIndex(index), enforced);
        return this;
    }

    /**
     * Sets the {@code schemaEnforced} flag of the operation's body binding.
     *
     * @param operationId the operation id
     * @param enforced    the flag
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public DisclosurePublications bodySchemaEnforced(String operationId, boolean enforced) {
        OperationEdits operation = operation(operationId);
        operation.enforced.put(operation.bodyIndex(), enforced);
        return this;
    }

    /**
     * Sets the mount's strategy id; no binding's {@code schemaEnforced} is recomputed.
     *
     * @param id the strategy id, for example {@code none} or a custom id
     * @return this rewriter
     */
    public DisclosurePublications strategy(String id) {
        this.strategyId = Objects.requireNonNull(id, "id");
        return this;
    }

    /**
     * Sets whether the operation's gate was installed; no binding's {@code schemaEnforced} is
     * recomputed.
     *
     * @param operationId the operation id
     * @param installed   the flag
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown
     */
    public DisclosurePublications gateInstalled(String operationId, boolean installed) {
        operation(operationId).gateInstalled = installed;
        return this;
    }

    /**
     * Replaces the operation's captured body schema, keeping the provenance unless {@link
     * #bodyProvenance} also replaces it. The object is captured by reference, not copied.
     *
     * @param operationId the operation id
     * @param body        the captured body schema
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown or has no body binding
     */
    public DisclosurePublications capturedBody(String operationId, JsonObject body) {
        OperationEdits operation = operation(operationId);
        operation.bodyIndex();
        operation.body = Objects.requireNonNull(body, "body");
        return this;
    }

    /**
     * Replaces the provenance of the operation's captured body schema.
     *
     * @param operationId the operation id
     * @param provenance  the provenance: {@code null} for none, a {@code RedactionManifest}, or any
     *     other object such as a {@code String}
     * @return this rewriter
     * @throws IllegalStateException if the operation is unknown, has no body binding, or has no
     *     captured body schema when {@code provenance} is non-null
     */
    public DisclosurePublications bodyProvenance(String operationId, @Nullable Object provenance) {
        OperationEdits operation = operation(operationId);
        operation.bodyIndex();
        operation.provenance = provenance == null ? NO_PROVENANCE : provenance;
        return this;
    }

    /**
     * Builds the rewritten publication.
     *
     * @return a new built publication with the same descriptor facts
     * @throws IllegalStateException if a non-null provenance is set on an operation without a captured
     *     body schema
     */
    public Publications.Built build() {
        MountPublication mount = source.publication();
        List<OperationPublication> operations = new ArrayList<>();
        for (OperationPublication operation : mount.operations()) {
            operations.add(edits.get(operation.operationId()).rewrite());
        }
        MountPublication rewritten = new MountPublication(
                mount.mountPath(),
                mount.mountId(),
                mount.applicationName(),
                mount.declaringType(),
                strategyId == null ? mount.strategyId() : strategyId,
                operations);
        return new Publications.Built(rewritten, source.consumes(), source.namedFileParts());
    }

    private OperationEdits operation(String operationId) {
        OperationEdits operation = edits.get(Objects.requireNonNull(operationId, "operationId"));
        if (operation == null) {
            throw new IllegalStateException(
                    "the publication has no operation '" + operationId + "'; it has " + edits.keySet());
        }
        return operation;
    }

    private static void requireDetail(OperationPublication operation) {
        if (operation.detail() == null) {
            throw new IllegalStateException("operation '" + operation.operationId() + "' carries no detail");
        }
    }

    /** The pending edits of one operation. */
    private static final class OperationEdits {

        private final OperationPublication operation;
        private final OperationDetail detail;
        private final Set<Integer> hidden = new LinkedHashSet<>();
        private final Map<Integer, Type> types = new HashMap<>();
        private final Map<Integer, Boolean> enforced = new HashMap<>();
        private @Nullable Boolean gateInstalled;
        private @Nullable JsonObject body;
        private @Nullable Object provenance;

        private OperationEdits(OperationPublication operation) {
            this.operation = operation;
            this.detail = Objects.requireNonNull(operation.detail(), "detail");
        }

        private int indexOf(ParamLocation location, String name) {
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(name, "name");
            List<Integer> matches = new ArrayList<>();
            List<InputBinding> inputs = detail.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                InputBinding binding = inputs.get(i);
                if (binding.location() == location && name.equals(binding.name())) {
                    matches.add(i);
                }
            }
            if (matches.size() != 1) {
                throw new IllegalStateException("operation '" + operation.operationId() + "' has " + matches.size()
                        + " bindings at " + location + " '" + name + "'; select one by index");
            }
            return matches.get(0);
        }

        private int checkIndex(int index) {
            if (index < 0 || index >= detail.inputs().size()) {
                throw new IllegalStateException("operation '" + operation.operationId() + "' has no binding at index "
                        + index + "; it has " + detail.inputs().size());
            }
            return index;
        }

        private int bodyIndex() {
            List<InputBinding> inputs = detail.inputs();
            for (int i = 0; i < inputs.size(); i++) {
                if (inputs.get(i).origin() == Origin.BODY) {
                    return i;
                }
            }
            throw new IllegalStateException("operation '" + operation.operationId() + "' has no body binding");
        }

        private OperationPublication rewrite() {
            List<InputBinding> inputs = new ArrayList<>();
            List<InputBinding> original = detail.inputs();
            for (int i = 0; i < original.size(); i++) {
                InputBinding binding = original.get(i);
                inputs.add(new InputBinding(
                        binding.origin(),
                        binding.location(),
                        binding.name(),
                        types.getOrDefault(i, binding.type()),
                        binding.defaultValue(),
                        binding.requiredness(),
                        hidden.contains(i) || binding.hidden(),
                        enforced.getOrDefault(i, binding.schemaEnforced()),
                        binding.annotations(),
                        binding.methodParameterIndex(),
                        binding.compositeType()));
            }
            CapturedSchemas schemas = detail.schemas();
            JsonObject capturedBody = body == null ? schemas.body() : body;
            Object capturedProvenance;
            if (provenance == null) {
                capturedProvenance = schemas.bodyProvenance();
            } else if (provenance == NO_PROVENANCE) {
                capturedProvenance = null;
            } else {
                capturedProvenance = provenance;
            }
            if (capturedBody == null && capturedProvenance != null) {
                throw new IllegalStateException("operation '" + operation.operationId()
                        + "' captures no body schema, so its body cannot carry a provenance");
            }
            CapturedSchemas rewrittenSchemas =
                    new CapturedSchemas(capturedBody, capturedProvenance, schemas.parameters());
            OperationDetail rewrittenDetail = new OperationDetail(
                    detail.descriptor(),
                    detail.profileId(),
                    rewrittenSchemas,
                    gateInstalled == null ? detail.gateInstalled() : gateInstalled,
                    inputs,
                    detail.response());
            return new OperationPublication(
                    operation.operationId(),
                    operation.httpMethod(),
                    operation.jaxRsPathTemplate(),
                    operation.vertxRouteValue(),
                    operation.vertxRouteIsRegex(),
                    operation.effectivePolicy(),
                    operation.securityRequirementSets(),
                    operation.requiresAction(),
                    rewrittenDetail);
        }
    }
}
