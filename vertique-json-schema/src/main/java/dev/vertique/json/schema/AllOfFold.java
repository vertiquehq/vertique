// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folds an unconsolidated object {@code allOf} into one flat {@code properties} set, wherever
 * folding is <strong>equivalence-preserving</strong> under Draft 2020-12 conjunction semantics.
 *
 * <p><strong>Why this exists.</strong> Victools leaves an object {@code allOf} unconsolidated when
 * two of its parts declare the <em>same</em> property with different schemas — a polymorphic subtype
 * whose discriminator ({@code kind}, say) is declared once as {@code const} on the discriminator
 * part and once as a plain typed property on the base part — and the {@code @JsonUnwrapped}
 * definition provider leaves a single-part {@code allOf} beside the holder's own already-flattened
 * {@code properties}. Neither shape is wrong by itself: {@code allOf} is conjunction, and a
 * conforming instance must already satisfy every part simultaneously. But
 * {@code McpSchemaHardener.closeRecursively} (in {@code vertique-mcp-server}) closes a
 * <em>non-root</em> object schema with {@code additionalProperties: false} exactly when it carries a
 * non-empty {@code properties} member, no {@code $ref}, and no {@code additionalProperties} of its
 * own — the shape a plain, unconsolidated {@code allOf} part takes whenever it declares at least one
 * property. (A plain part with <em>empty</em> {@code properties} is folded too, and is not
 * necessarily a no-op: the hardener's non-empty-{@code properties} guard would never have targeted it
 * in the first place, but the part can still carry its own {@code required} entries or an explicit
 * {@code type: "object"}, and folding it still copies those onto the target even though no property
 * moves.) Closing one non-empty part alone turns every property published
 * only on a <em>sibling</em> part into a rejected "additional property" of that part, so a valid body
 * the binder and the REST gate both accept is rejected at the MCP tool-input boundary. Folding the
 * {@code allOf} into one flat {@code properties} set before any consumer's own hardening pass runs
 * removes the shape that closure guard targets.
 *
 * <p><strong>Input direction only.</strong> This pass is installed by
 * {@link AnnotationJsonSchemaGenerator#generateCanonical(java.lang.reflect.Type)} only when the
 * generator was constructed by {@link AnnotationJsonSchemaGenerator#forInputProfile}: it is the
 * direction whose published schema gates an MCP tool's own argument boundary, and the REST gate a
 * document also feeds is indifferent to whether an {@code allOf} is folded or left unconsolidated —
 * both describe the identical conjunction. Folding is therefore a normalization of the input
 * direction's own published document, never a semantic change to what either direction's schema
 * accepts.
 *
 * <h2>What "plain part" and "fold" mean here</h2>
 *
 * <p>A <strong>plain part</strong> is an object schema whose keys are a subset of {@code type}
 * (present only as the literal {@code "object"}, or absent), {@code properties}, {@code required},
 * {@code title}, and {@code description}. Excluding every other Draft 2020-12 object-schema keyword
 * from that set is deliberate and is what makes a fold safe to attempt at all: a part carrying
 * {@code $ref}, {@code additionalProperties}, {@code patternProperties}, {@code
 * unevaluatedProperties}, {@code propertyNames}, {@code dependentRequired}, {@code
 * dependentSchemas}, {@code if}, {@code not}, {@code oneOf}, {@code anyOf}, {@code allOf}, {@code
 * minProperties}, {@code maxProperties}, {@code const}, {@code enum}, or a {@code type} other than
 * {@code "object"} is never folded — each of those keywords can make the part's contribution depend
 * on more than "this key, if present, must satisfy this schema; these keys must be present", which
 * is the only shape the merge below reasons about.
 *
 * <p>Folding a plain part {@code P} into a <strong>target</strong> {@code T} (another part of the
 * same {@code allOf}, or the schema {@code S} that carries the {@code allOf}) applies the
 * conjunction {@code T ∧ P} directly to {@code T}, per Draft 2020-12 semantics:
 *
 * <ul>
 *   <li><strong>{@code type}.</strong> A plain part's {@code type}, if present, is always the literal
 *       {@code "object"} (see below) — an implicit constraint that the pre-fold conjunction already
 *       enforced unconditionally. When {@code P} carries {@code type: "object"} and {@code T} declares
 *       no {@code type} of its own, {@code T} gains {@code type: "object"}: dropping the part instead
 *       of copying its constraint would let a scalar, array, or {@code null} instance the pre-fold
 *       conjunction rejected pass the post-fold document. When {@code T} already declares
 *       {@code type: "object"}, copying is a no-op. When {@code T} declares any other {@code type} —
 *       an array such as {@code ["object", "null"]}, or any single type other than {@code "object"} —
 *       the fold is <strong>refused</strong> instead: dropping {@code P} would silently change
 *       whether {@code T}'s own admitted non-object values (most sharply, {@code null}) still satisfy
 *       the conjunction, and the fold cannot show that copying {@code "object"} over a
 *       multi-valued {@code type} is equivalent. This makes the fold order-independent: whichever
 *       part of an {@code allOf} ends up chosen as the target, the merged result always ends with
 *       {@code type: "object"} when any folded part carried it, regardless of which part was folded
 *       into which.
 *   <li><strong>A property {@code p} both declare.</strong> {@code T.properties[p]} becomes
 *       {@code {"allOf": [T.properties[p], P.properties[p]]}} — unless the two schemas are already
 *       equal, in which case one is kept unchanged. This is exactly what conjunction already meant:
 *       before the fold, an instance had to satisfy {@code T} and {@code P} simultaneously, so a
 *       present key {@code p} already had to satisfy both {@code T.properties[p]} and {@code
 *       P.properties[p]}; the merge only writes that requirement down explicitly in one place.
 *   <li><strong>A property {@code p} only {@code P} declares, and {@code T} constrains no key it
 *       does not name.</strong> When {@code T} has none of {@code additionalProperties}, {@code
 *       patternProperties}, {@code unevaluatedProperties}, or {@code propertyNames} — or its {@code
 *       additionalProperties} is exactly the literal {@code true} — {@code T} places no constraint at
 *       all on a key it does not list (Draft 2020-12's own default for an absent {@code
 *       additionalProperties} is "unconstrained"). Adding {@code p: P.properties[p]} to {@code
 *       T.properties} therefore changes nothing {@code T} required before; it only writes down what
 *       {@code P} already required.
 *   <li><strong>A property {@code p} only {@code P} declares, and {@code T.additionalProperties} is
 *       an object schema {@code A}.</strong> Before the fold, a present key outside {@code
 *       T.properties} already had to satisfy {@code A} (via {@code T}'s own {@code
 *       additionalProperties}) and {@code P.properties[p]} (via {@code P}). The fold writes {@code
 *       T.properties[p] = {"allOf": [A, P.properties[p]]}} — {@code A} is copied rather than moved,
 *       because {@code T}'s own {@code additionalProperties} keeps applying to every key neither part
 *       named, and must keep meaning what it meant before.
 *   <li><strong>Refused otherwise.</strong> When {@code T.additionalProperties} is the literal
 *       {@code false}, or {@code T} carries {@code patternProperties}, {@code
 *       unevaluatedProperties}, or {@code propertyNames}, and {@code P} names a key {@code T} does
 *       not, {@code P} is <strong>not folded</strong>: {@code T}'s existing keyword may already
 *       reject that key outright ({@code additionalProperties: false}), constrain it by a pattern
 *       this pass does not evaluate ({@code patternProperties}), or interact with evaluation this
 *       pass cannot reason about ({@code unevaluatedProperties}, {@code propertyNames}). Silently
 *       adding the key would risk changing what the conjunction accepts, so the {@code allOf} is left
 *       exactly as generated instead.
 *   <li><strong>{@code required}.</strong> {@code T.required} becomes the union of {@code
 *       T.required} and {@code P.required}, preserving order and de-duplicating — the same set of
 *       keys the conjunction already demanded be present.
 *   <li><strong>{@code title} / {@code description}.</strong> {@code T} keeps its own value when it
 *       has one; otherwise {@code P}'s value, if any, is copied. Metadata, not a constraint, so no
 *       conjunction reasoning applies.
 * </ul>
 *
 * <p>After folding, {@code P} is removed from the {@code allOf} array. An emptied array removes the
 * {@code allOf} keyword entirely — an {@code allOf} with no members is vacuously true, so dropping it
 * changes nothing an instance must satisfy. When exactly one part remains and {@code S} (the schema
 * that carried the array) declares no keyword other than {@code allOf}, that sole remaining part's
 * keywords are hoisted directly onto {@code S} and {@code allOf} is removed: {@code S ∧ part} and
 * {@code part} alone mean the same thing once {@code S} itself imposes nothing else, and hoisting is
 * what turns a discriminated-union branch such as {@code allOf: [part]} into the flat object schema
 * the {@code McpSchemaHardener} closure guard no longer has a plain nested part to target.
 *
 * <h2>Choosing the target</h2>
 *
 * <p>For a schema {@code S} carrying an {@code allOf} array, the target is {@code S} itself when
 * {@code S} already declares {@code properties} (it is already the flattened holder — the shape
 * {@code @JsonUnwrapped} leaves beside its child's own {@code allOf} part) or when {@code S}
 * explicitly declares {@code type: "object"} and none of {@code additionalProperties}, {@code
 * patternProperties}, {@code unevaluatedProperties}, or {@code propertyNames} of its own — an
 * otherwise-empty object-schema shell with room to receive properties directly and nothing of its own
 * that a folded-in key could conflict with. A schema that declares neither — the bare {@code {"allOf":
 * [...]}} wrapper Victools emits for one branch of a polymorphic {@code anyOf} — is not yet
 * recognizable as an object schema in its own right, so it is never chosen as the target; instead the
 * target is the first sibling part of the same {@code allOf} that is itself recognizable as an object
 * schema (declares {@code properties}, or an explicit {@code type: "object"}), whether or not that
 * part also happens to be plain. Every other plain sibling is then folded into it.
 *
 * <h2>{@code $ref} safety</h2>
 *
 * <p>Before any folding, every local {@code $ref} value in the whole document (a JSON Pointer of the
 * form {@code "#/..."}) is collected once, from the document as originally generated. A schema
 * {@code S}'s own fold is refused outright — {@code S}'s {@code allOf} is left exactly as generated,
 * for this pass — when either: a collected pointer targets a node inside {@code S}'s own {@code
 * allOf} array (any part, at any depth), since removing or renumbering array members would leave that
 * pointer dangling or silently retarget it to a different node; or a collected pointer targets a node
 * inside the chosen target's own {@code properties} (whether the target is {@code S} itself or a
 * sibling part), since a fold that wraps an existing shared key in a fresh {@code
 * {"allOf": [...]}} rewrites exactly the node such a pointer resolves to. Neither check tries to
 * predict which specific part or key a fold would actually touch; refusing the whole schema's fold
 * whenever any local {@code $ref} reaches into the region a fold could rewrite is the only way to
 * guarantee no local {@code $ref} is ever rewritten or left unresolved. This pass never rewrites a
 * {@code $ref} value itself, in any case.
 *
 * <h2>Where this runs</h2>
 *
 * <p>The whole document is walked through {@link SchemaPositions#visitSchemaHeads}, the same
 * traversal {@link NumericDomainKeywordFilter} and {@link DisjointTypeDetector} share: every object
 * schema head reachable through {@code properties}, {@code additionalProperties}, {@code
 * patternProperties}, {@code items}, {@code prefixItems}, {@code anyOf}/{@code oneOf}/{@code
 * allOf}/{@code not} members, {@code $defs}, and {@code if}/{@code then}/{@code else}, parent before
 * children, never descending into literal data. Because the traversal visits a parent before its
 * children, a single pass is not enough on its own: a part with its own nested {@code allOf} is never
 * plain on the pass that visits its parent, even though folding that nested {@code allOf} — which
 * happens later in the very same pass, when the traversal reaches the part itself — can leave it
 * plain by the time the pass finishes. Folding the whole document is therefore repeated, each
 * repetition a fresh full pass over the current document, until one repetition folds nothing at all,
 * bounded at {@value #MAX_FOLD_ROUNDS} repetitions regardless. A part that becomes plain only once its
 * own nested {@code allOf} is folded is picked up by its ancestor's target on the following
 * repetition, rather than being left sitting in an {@code allOf} array unmerged.
 *
 * <h2>What this pass still leaves for the hardener</h2>
 *
 * <p>This pass is an equivalence-preserving normalization, not a guarantee that every {@code allOf}
 * disappears. {@code McpSchemaHardener.closeRecursively} still needs its own part-by-part closure
 * guard for at least three shapes this pass deliberately leaves alone: a plain discriminator part
 * sitting beside a sibling part that carries its own {@code $ref} (a {@code $ref}-carrying part is
 * never itself folded away, but it <em>can</em> be a fold target when it also declares its own
 * {@code properties} or an explicit {@code type: "object"} — folding into it is sound, since the fold
 * never touches the {@code $ref} keyword itself; only a bare {@code $ref}-only part, with neither of
 * its own, is neither a target nor ever folded, so the plain discriminator sibling has nowhere
 * equivalence-preserving to go); a part carrying an annotation-only keyword such as {@code
 * default} or {@code examples} (excluded from {@link #PLAIN_PART_KEYS}, so it is never classified as
 * plain, whether or not its data happens to look like a schema); and a plain part whose fold was
 * refused — by the {@code type} rule, the {@code $ref} safety rule, or an {@link
 * #isFoldFeasible(ObjectNode, ObjectNode) infeasible} new key — or that had no eligible target to
 * begin with. Each such part keeps exactly the shape the hardener's non-root closure guard targets,
 * so the hardener's own per-part closure remains the last line of defense for it.
 */
final class AllOfFold {

    /** The only keys a part may carry and still be considered for folding. */
    private static final Set<String> PLAIN_PART_KEYS = Set.of("type", "properties", "required", "title", "description");

    /**
     * The keywords whose presence on a target blocks adding a brand-new property key without further
     * reasoning: each interacts with keys a schema does not explicitly list under {@code properties}.
     */
    private static final Set<String> PROPERTY_SET_CONSTRAINTS =
            Set.of("additionalProperties", "patternProperties", "unevaluatedProperties", "propertyNames");

    /**
     * The bound on how many whole-document passes {@link #fold(JsonNode)} repeats to reach a fixed
     * point. See the class Javadoc, "Where this runs", for why more than one pass is ever needed.
     */
    private static final int MAX_FOLD_ROUNDS = 8;

    /** Sentinel {@link #selectTargetIndex} return value meaning no eligible target exists. */
    private static final int NO_TARGET = -2;

    /** Sentinel {@link #selectTargetIndex} return value meaning the schema head itself is the target. */
    private static final int SCHEMA_IS_TARGET = -1;

    private AllOfFold() {}

    /**
     * Folds every equivalence-preserving unconsolidated object {@code allOf} in {@code document},
     * mutating it in place.
     *
     * @param document the freshly post-processed input-direction document
     */
    static void fold(JsonNode document) {
        Set<String> localRefPointers = collectLocalRefPointers(document);
        for (int round = 0; round < MAX_FOLD_ROUNDS; round++) {
            boolean[] changedThisRound = {false};
            SchemaPositions.visitSchemaHeads(document, (schema, path) -> {
                if (schema instanceof ObjectNode objectSchema && foldAt(objectSchema, path, localRefPointers)) {
                    changedThisRound[0] = true;
                }
            });
            if (!changedThisRound[0]) {
                return;
            }
        }
    }

    /**
     * Collects every local {@code $ref} pointer value ({@code "#/..."}) anywhere in {@code document},
     * regardless of position, so a fold can refuse to touch a region a pointer reaches into. See the
     * class Javadoc, "{@code $ref} safety".
     *
     * @param document the whole, not-yet-folded document
     * @return every distinct local {@code $ref} value found, in encounter order
     */
    private static Set<String> collectLocalRefPointers(JsonNode document) {
        Set<String> pointers = new LinkedHashSet<>();
        collectLocalRefPointers(document, pointers);
        return pointers;
    }

    private static void collectLocalRefPointers(JsonNode node, Set<String> pointers) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith("#/")) {
                pointers.add(ref.asText());
            }
            for (JsonNode child : node) {
                collectLocalRefPointers(child, pointers);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectLocalRefPointers(child, pointers);
            }
        }
    }

    /**
     * Folds the {@code allOf} array carried by one schema head, when it carries one at all and doing
     * so is safe.
     *
     * @param schema           the schema head being visited; mutated in place
     * @param path             {@code schema}'s JSON-pointer-style path within the document
     * @param localRefPointers every local {@code $ref} pointer collected from the document, up front
     * @return {@code true} when at least one part was folded, hoisted, or the {@code allOf} keyword
     *         was removed as a result; {@code false} when nothing changed
     */
    private static boolean foldAt(ObjectNode schema, String path, Set<String> localRefPointers) {
        JsonNode allOfNode = schema.get("allOf");
        if (allOfNode == null || !allOfNode.isArray()) {
            return false;
        }
        String allOfPath = path + "/allOf";
        if (pointsInto(localRefPointers, allOfPath)) {
            return false;
        }
        ArrayNode allOfArray = (ArrayNode) allOfNode;
        int targetIndex = selectTargetIndex(schema, allOfArray);
        if (targetIndex == NO_TARGET) {
            return false;
        }
        boolean targetIsSchema = targetIndex == SCHEMA_IS_TARGET;
        JsonNode targetNode = targetIsSchema ? schema : allOfArray.get(targetIndex);
        if (!targetNode.isObject()) {
            return false;
        }
        ObjectNode target = (ObjectNode) targetNode;
        String targetPath = targetIsSchema ? path : allOfPath + "/" + targetIndex;
        if (pointsInto(localRefPointers, targetPath + "/properties")) {
            return false;
        }

        List<JsonNode> remaining = new ArrayList<>();
        boolean foldedAny = false;
        for (JsonNode part : allOfArray) {
            if (part == target) {
                remaining.add(part);
                continue;
            }
            if (part.isObject() && isPlainFoldablePart(part) && tryFold(target, (ObjectNode) part)) {
                foldedAny = true;
                continue;
            }
            remaining.add(part);
        }

        boolean rewriteChanged = rewriteAllOf(schema, remaining, targetIsSchema);
        return foldedAny || rewriteChanged;
    }

    /**
     * Whether any pointer in {@code localRefPointers} targets {@code prefixPath} itself or a node
     * nested under it.
     *
     * @param localRefPointers every local {@code $ref} pointer collected from the document
     * @param prefixPath       the JSON-pointer-style path of the region a fold would rewrite
     * @return {@code true} when a pointer reaches into that region
     */
    private static boolean pointsInto(Set<String> localRefPointers, String prefixPath) {
        for (String pointer : localRefPointers) {
            if (pointer.equals(prefixPath) || pointer.startsWith(prefixPath + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chooses the node every plain sibling part folds into.
     *
     * @param schema     the schema head carrying the {@code allOf}
     * @param allOfArray {@code schema}'s {@code allOf} array
     * @return {@link #SCHEMA_IS_TARGET} when {@code schema} itself is the target, the index within
     *         {@code allOfArray} of the chosen sibling part, or {@link #NO_TARGET} when neither
     *         {@code schema} nor any sibling part is recognizable as an object schema
     */
    private static int selectTargetIndex(ObjectNode schema, ArrayNode allOfArray) {
        if (hasObjectProperties(schema) || isEmptyObjectShell(schema)) {
            return SCHEMA_IS_TARGET;
        }
        for (int index = 0; index < allOfArray.size(); index++) {
            JsonNode part = allOfArray.get(index);
            if (part.isObject() && isObjectSchemaHead(part)) {
                return index;
            }
        }
        return NO_TARGET;
    }

    /**
     * Folds {@code part} into {@code target}, applying every change only after confirming every
     * property {@code part} adds can be folded without risking a change in what the conjunction
     * accepts, and that {@code part}'s implicit or explicit {@code type} can be preserved.
     *
     * @param target the node receiving {@code part}'s contribution; mutated in place on success
     * @param part   the plain part being folded away
     * @return {@code true} when the fold was applied; {@code false} when it was refused
     */
    private static boolean tryFold(ObjectNode target, ObjectNode part) {
        if (!isTypeFoldFeasible(target, part)) {
            return false;
        }
        JsonNode partProperties = part.get("properties");
        ObjectNode partPropertiesObject =
                partProperties != null && partProperties.isObject() ? (ObjectNode) partProperties : null;
        if (partPropertiesObject != null && !isFoldFeasible(target, partPropertiesObject)) {
            return false;
        }
        if (partPropertiesObject != null) {
            applyProperties(target, partPropertiesObject);
        }
        unionRequired(target, part.get("required"));
        copyIfAbsent(target, part, "title");
        copyIfAbsent(target, part, "description");
        applyTypeIfNeeded(target, part);
        return true;
    }

    /**
     * Whether folding {@code part} can preserve whatever {@code type} constraint it carries. See the
     * class Javadoc's {@code type} rule.
     *
     * @param target the prospective target, unmodified
     * @param part   the plain part being folded
     * @return {@code false} only when {@code part} explicitly requires {@code type: "object"} and
     *         {@code target} already declares some other, non-{@code "object"} {@code type}
     */
    private static boolean isTypeFoldFeasible(ObjectNode target, ObjectNode part) {
        if (!hasExplicitObjectType(part)) {
            return true;
        }
        JsonNode targetType = target.get("type");
        return targetType == null || (targetType.isTextual() && "object".equals(targetType.asText()));
    }

    /**
     * Copies {@code part}'s {@code type: "object"} onto {@code target} when {@code part} carries it
     * explicitly and {@code target} declares no {@code type} of its own. Called only once {@link
     * #isTypeFoldFeasible} has already accepted the fold.
     *
     * @param target the node being folded into; mutated in place
     * @param part   the folding part
     */
    private static void applyTypeIfNeeded(ObjectNode target, ObjectNode part) {
        if (hasExplicitObjectType(part) && target.get("type") == null) {
            target.put("type", "object");
        }
    }

    /**
     * Whether {@code part} explicitly carries {@code type: "object"} (as opposed to leaving
     * {@code type} absent).
     *
     * @param part the {@code allOf} member being classified
     * @return {@code true} when {@code part} declares {@code type: "object"} literally
     */
    private static boolean hasExplicitObjectType(ObjectNode part) {
        JsonNode type = part.get("type");
        return type != null && type.isTextual() && "object".equals(type.asText());
    }

    /**
     * Whether every property {@code partProperties} declares can be folded into {@code target}
     * without risking a change in what the conjunction accepts.
     *
     * @param target          the prospective target, unmodified
     * @param partProperties  the folding part's {@code properties} object
     * @return {@code true} when every key is either already present on {@code target} (folded via
     *         per-key {@code allOf}) or safe to add fresh
     */
    private static boolean isFoldFeasible(ObjectNode target, ObjectNode partProperties) {
        JsonNode targetProperties = target.get("properties");
        Iterator<String> propertyNames = partProperties.fieldNames();
        while (propertyNames.hasNext()) {
            String property = propertyNames.next();
            boolean targetHasProperty =
                    targetProperties != null && targetProperties.isObject() && targetProperties.has(property);
            if (!targetHasProperty && !canAddNewProperty(target)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code target} can safely receive a property key it does not already name.
     *
     * @param target the prospective target
     * @return {@code true} when nothing on {@code target} constrains a key it does not list, or its
     *         {@code additionalProperties} is an object schema the new key can be conjoined with
     */
    private static boolean canAddNewProperty(ObjectNode target) {
        if (target.has("patternProperties") || target.has("unevaluatedProperties") || target.has("propertyNames")) {
            return false;
        }
        JsonNode additionalProperties = target.get("additionalProperties");
        if (additionalProperties == null) {
            return true;
        }
        if (additionalProperties.isBoolean()) {
            return additionalProperties.asBoolean();
        }
        return additionalProperties.isObject();
    }

    /**
     * Applies every property {@code partProperties} declares onto {@code target}, per the merge rules
     * documented on the class. Called only once {@link #isFoldFeasible} has accepted the whole set.
     *
     * @param target         the node receiving the properties; mutated in place
     * @param partProperties the folding part's {@code properties} object
     */
    private static void applyProperties(ObjectNode target, ObjectNode partProperties) {
        ObjectNode targetProperties = ensureProperties(target);
        for (Map.Entry<String, JsonNode> entry : partProperties.properties()) {
            String property = entry.getKey();
            JsonNode partSchema = entry.getValue();
            JsonNode existing = targetProperties.get(property);
            if (existing != null) {
                if (!existing.equals(partSchema)) {
                    targetProperties.set(property, wrapAllOf(existing, partSchema));
                }
                continue;
            }
            JsonNode additionalProperties = target.get("additionalProperties");
            if (additionalProperties != null && additionalProperties.isObject()) {
                targetProperties.set(property, wrapAllOf(additionalProperties.deepCopy(), partSchema));
            } else {
                targetProperties.set(property, partSchema);
            }
        }
    }

    /**
     * Returns {@code target}'s {@code properties} object, creating an empty one when absent.
     *
     * @param target the node being folded into
     * @return the (possibly newly created) {@code properties} object, live
     */
    private static ObjectNode ensureProperties(ObjectNode target) {
        JsonNode properties = target.get("properties");
        if (properties != null && properties.isObject()) {
            return (ObjectNode) properties;
        }
        return target.putObject("properties");
    }

    /**
     * Wraps two schemas for the same key in one {@code allOf}, the conjunction Draft 2020-12 already
     * required of a key both parts declared.
     *
     * @param first  the first branch
     * @param second the second branch
     * @return a fresh {@code {"allOf": [first, second]}} node
     */
    private static ObjectNode wrapAllOf(JsonNode first, JsonNode second) {
        ObjectNode wrapper = JsonNodeFactory.instance.objectNode();
        ArrayNode branches = wrapper.putArray("allOf");
        branches.add(first);
        branches.add(second);
        return wrapper;
    }

    /**
     * Unions {@code partRequired} into {@code target}'s own {@code required} list, preserving order
     * and dropping duplicates.
     *
     * @param target       the node being folded into; mutated in place
     * @param partRequired the folding part's {@code required} array, or {@code null}/non-array when
     *                     it declares none
     */
    private static void unionRequired(ObjectNode target, JsonNode partRequired) {
        if (partRequired == null || !partRequired.isArray() || partRequired.isEmpty()) {
            return;
        }
        JsonNode targetRequired = target.get("required");
        Set<String> seen = new LinkedHashSet<>();
        if (targetRequired != null && targetRequired.isArray()) {
            for (JsonNode name : targetRequired) {
                seen.add(name.asText());
            }
        }
        for (JsonNode name : partRequired) {
            seen.add(name.asText());
        }
        ArrayNode merged = JsonNodeFactory.instance.arrayNode();
        seen.forEach(merged::add);
        target.set("required", merged);
    }

    /**
     * Copies {@code part}'s value for {@code key} onto {@code target} when {@code target} declares
     * none of its own.
     *
     * @param target the node being folded into; mutated in place
     * @param part   the folding part
     * @param key    {@code "title"} or {@code "description"}
     */
    private static void copyIfAbsent(ObjectNode target, ObjectNode part, String key) {
        if (target.has(key)) {
            return;
        }
        JsonNode value = part.get(key);
        if (value != null) {
            target.set(key, value);
        }
    }

    /**
     * Rewrites {@code schema}'s {@code allOf} once every foldable sibling part has been folded (or
     * left in place, when its fold was refused).
     *
     * @param schema         the schema head carrying the {@code allOf}; mutated in place
     * @param remaining      the parts that still belong in the array, in original relative order
     * @param targetIsSchema whether {@code schema} itself was the fold target
     * @return {@code true} when this call itself removed the {@code allOf} keyword or hoisted a sole
     *         remaining part onto {@code schema}; {@code false} when the array was simply rewritten
     *         back with the same members (which happens when no fold occurred and no hoist applies)
     */
    private static boolean rewriteAllOf(ObjectNode schema, List<JsonNode> remaining, boolean targetIsSchema) {
        if (remaining.isEmpty()) {
            boolean hadAllOf = schema.has("allOf");
            schema.remove("allOf");
            return hadAllOf;
        }
        // Hoisting is sound only when the sole remaining part is a sibling the fold already merged
        // everything else into (targetIsSchema means schema itself already holds the merged content,
        // so a leftover sibling here could only be one whose fold was refused, and must not be
        // silently absorbed), and only when schema itself contributes nothing beyond the allOf being
        // rewritten — otherwise schema ∧ part is not the same schema as part alone.
        if (remaining.size() == 1 && !targetIsSchema && schema.size() == 1) {
            ObjectNode sole = (ObjectNode) remaining.get(0);
            schema.remove("allOf");
            schema.setAll(sole);
            return true;
        }
        ArrayNode rewritten = JsonNodeFactory.instance.arrayNode();
        remaining.forEach(rewritten::add);
        schema.set("allOf", rewritten);
        return false;
    }

    /**
     * Whether {@code part} is a plain part: an object schema whose keys are all drawn from {@link
     * #PLAIN_PART_KEYS}, whose {@code type}, if present, is exactly {@code "object"}. See the class
     * Javadoc for why every other object-schema keyword excludes a part from folding.
     *
     * @param part the {@code allOf} member being classified
     * @return {@code true} when {@code part} may be folded
     */
    private static boolean isPlainFoldablePart(JsonNode part) {
        if (!part.isObject()) {
            return false;
        }
        Iterator<String> keys = part.fieldNames();
        while (keys.hasNext()) {
            if (!PLAIN_PART_KEYS.contains(keys.next())) {
                return false;
            }
        }
        return isExplicitObjectTypeOrAbsent((ObjectNode) part);
    }

    private static boolean isExplicitObjectTypeOrAbsent(ObjectNode part) {
        JsonNode type = part.get("type");
        return type == null || (type.isTextual() && "object".equals(type.asText()));
    }

    /**
     * Whether {@code node} declares a non-empty {@code type: "object"} shell with nothing else that
     * would constrain which properties may be folded in directly.
     *
     * @param node the candidate self-target
     * @return {@code true} when {@code node} is safe to fold plain siblings into directly
     */
    private static boolean isEmptyObjectShell(ObjectNode node) {
        JsonNode type = node.get("type");
        if (type == null || !type.isTextual() || !"object".equals(type.asText())) {
            return false;
        }
        for (String blocker : PROPERTY_SET_CONSTRAINTS) {
            if (node.has(blocker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether {@code node} declares a non-empty {@code properties} object of its own.
     *
     * @param node the node to check
     * @return {@code true} when {@code node} already has a {@code properties} object
     */
    private static boolean hasObjectProperties(ObjectNode node) {
        JsonNode properties = node.get("properties");
        return properties != null && properties.isObject();
    }

    /**
     * Whether {@code node} is recognizable, on its own, as an object schema: it declares {@code
     * properties} or an explicit {@code type: "object"}.
     *
     * @param node the {@code allOf} member being checked as a possible target
     * @return {@code true} when {@code node} qualifies
     */
    private static boolean isObjectSchemaHead(JsonNode node) {
        if (node.has("properties") && node.get("properties").isObject()) {
            return true;
        }
        JsonNode type = node.get("type");
        return type != null && type.isTextual() && "object".equals(type.asText());
    }
}
