// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputFieldNameResolver;
import dev.vertique.core.sanitization.InputFieldNameResolver.PromotedField;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Default implementation of {@link InputObjectProcessor} that traverses intermediate
 * map/list structures and applies canonicalization and sanitization to string values.
 *
 * <p>Operates on {@code Map<String, Object>} (JSON objects) and {@code List<Object>}
 * (JSON arrays). String values are processed through the effective chain composed from
 * invocation-level, object-level, and field-level annotations.
 *
 * <p>Processing order for each string value:
 * <ol>
 *   <li>Invocation-level canonicalizers (from the caller's effective policies)</li>
 *   <li>Accumulated ancestor canonicalizers (from enclosing DTO types and fields)</li>
 *   <li>Object-level canonicalizers (from DTO type annotation)</li>
 *   <li>Field-level canonicalizers (from field annotation)</li>
 *   <li>Invocation-level sanitizers</li>
 *   <li>Accumulated ancestor sanitizers</li>
 *   <li>Object-level sanitizers</li>
 *   <li>Field-level sanitizers</li>
 * </ol>
 *
 * <p>Skip semantics:
 * <ul>
 *   <li>If a field has {@code @SkipCanonicalization}, all canonicalization is suppressed for
 *       that field — invocation, object, and field-level chains are all skipped.</li>
 *   <li>If the owner type has {@code @SkipCanonicalization} and the field does not declare its
 *       own {@code @Canonicalize}, canonicalization is suppressed for that field.</li>
 *   <li>Ancestor skip flags are <em>sticky</em>: once an ancestor sets {@code @SkipCanonicalization}
 *       or {@code @SkipSanitization}, that suppression is inherited by all descendants regardless
 *       of their own annotations.</li>
 *   <li>Same logic applies to sanitization.</li>
 * </ul>
 *
 * <p>This implementation never mutates the input structure; it returns a new map or list.
 *
 * <p><strong>Metadata is resolved per type, at descent.</strong> {@link InputPolicyMetadata}
 * describes only its own type's fields; when the walker descends into a nested field it resolves
 * that field's declared type through {@link InputPolicyMetadataResolver}'s per-type cache. The
 * walk therefore terminates on the finite intermediate data rather than on a type-graph budget:
 * a self-referential type resolves once and applies at every level, direct and mutual recursion
 * behave identically, and a policy declared behind any number of policy-free <em>descendable</em>
 * links still runs.
 *
 * <p>Not every link is descendable. Descent follows a field's <em>declared</em> type, and
 * {@link InputPolicyMetadataResolver} treats scalar leaves, {@link Object} and {@link Map} as
 * carrying no statically known property set. A policy declared <em>beyond</em> such a link is
 * therefore not found however shallow the graph: a chain on {@code Inner}'s field is invisible
 * across a {@code Map<String, Inner>} or {@code Object} field, and a subtype's own chains are
 * invisible because the runtime type is never consulted. Values behind those links still receive
 * the chains they inherit — they simply contribute no declared metadata of their own. See
 * {@link InputPolicyMetadataResolver} for the full statement of the boundary.
 *
 * <p><strong>Type classification is shared with the metadata resolver.</strong> The entry-point
 * target type and its element type are classified by {@link TypeClassifier}, the same rules
 * {@link InputPolicyMetadataResolver} applies to declared fields, so a wildcard, a type variable or
 * an {@code Optional} layer reduces to the same class wherever it appears. A collection and an
 * array of the same element type are therefore processed identically — both are a JSON array on the
 * wire and carry one element schema.
 *
 * <p><strong>Target-type guard.</strong> A target type that reduces to no class at all — a
 * {@code GenericArrayType} such as {@code List<Inner>[]}, or a non-JDK {@link Type} implementation —
 * cannot be processed. When the invocation-level policies are non-empty the caller has declared
 * processing that provably cannot run, so {@link #processInput} throws {@link IllegalStateException}
 * rather than silently returning the input. With empty invocation-level policies the input is
 * returned unchanged: whether the type graph declares policies of its own is not answerable without
 * the classification that just failed.
 *
 * <p><strong>Field names are projected from the wire before every metadata lookup.</strong> The
 * intermediate is keyed by whatever the codec published while {@link InputPolicyMetadata} is keyed
 * by Java property names, so each key is resolved through the traversal's
 * {@link InputFieldNameResolver} (carried on {@link InputTraversalContext}) before its
 * {@link FieldPolicyMetadata} is looked up. The emitted map keeps the wire key unchanged — the
 * projection decides which declared policies apply, never what the codec will bind. The
 * {@link InputValueContext} a policy observes follows the same split: {@code path} is the wire path,
 * while {@code logicalName} is the Java property name once a property matched and the wire name
 * otherwise. List elements keep the element path in both components.
 *
 * <p><strong>Processor resolution is cached per engine instance.</strong> The caller-supplied
 * resolver functions handed to {@link InputObjectProcessor#createDefault} are consulted once per
 * canonicalizer / sanitizer class instead of once per string value, and a resolution that fails is
 * cached and rethrown so a large payload cannot re-run a failing lookup per value. The engine makes
 * no assumption about whether those functions cache anything themselves. Retention is bounded by
 * the engine instance, which is component-scoped and dies with its Dagger component.
 *
 * <p><strong>Generated-processor fast path.</strong> The processor self-bootstraps a
 * {@link GeneratedInputProcessorDispatcher} in its constructor and consults it before walking
 * reflectively. When a {@code {DTO}_InputProcessor} class exists on the consuming type's
 * classloader (emitted by {@code vertique-codegen-sanitization}), traversal is delegated to that
 * generated processor; nested-type recursion is also routed through the dispatcher so generated
 * processors are picked up at any depth. The dispatcher's reflective continuation resumes this
 * walker with a preserved {@link InputTraversalContext}, keeping accumulated chains and sticky
 * skip flags consistent across the codegen↔reflection boundary.
 */
class DefaultInputObjectProcessor implements InputObjectProcessor {

    private final InputPolicyMetadataResolver metadataResolver;
    private final ResolutionCache<Canonicalizer> canonicalizers;
    private final ResolutionCache<Sanitizer> sanitizers;
    private final ChainResolver chainResolver;
    private final GeneratedInputProcessorDispatcher dispatcher;

    /**
     * Element classes resolved for top-level list/array target types, keyed by the declared
     * {@link Type}.
     *
     * <p>{@link TypeClassifier#elementType} resolves the {@code Collection<E>} supertype binding by
     * walking the declared type's supertypes and substituting type arguments at each hop, which would
     * otherwise run once per request. The answer depends only on the declared type, so it is computed
     * once. The value is an {@link Optional} because "no element schema" is a legitimate answer — for
     * a raw collection, a container element or an unresolvable binding — and recomputing that answer
     * costs the same walk as any other.
     *
     * <p>Retention is bounded by the set of distinct declared target types the caller hands in, which
     * is fixed at registration, and by the engine instance, which is component-scoped and dies with
     * its Dagger component.
     */
    private final ConcurrentMap<Type, Optional<Class<?>>> topLevelElementTypes = new ConcurrentHashMap<>();

    /**
     * Creates a new processor with the given dependencies.
     *
     * <p>Both resolver functions are wrapped in a per-engine {@link ResolutionCache}, so each is
     * consulted at most once per processor class for the life of this engine.
     *
     * @param metadataResolver      resolves (and caches) annotation metadata for target types
     * @param canonicalizerResolver factory that produces canonicalizer instances by class
     * @param sanitizerResolver     factory that produces sanitizer instances by class
     */
    DefaultInputObjectProcessor(
            InputPolicyMetadataResolver metadataResolver,
            Function<Class<? extends Canonicalizer>, Canonicalizer> canonicalizerResolver,
            Function<Class<? extends Sanitizer>, Sanitizer> sanitizerResolver) {
        this.metadataResolver = metadataResolver;
        this.canonicalizers = new ResolutionCache<>(canonicalizerResolver);
        this.sanitizers = new ResolutionCache<>(sanitizerResolver);
        this.chainResolver = this::applyChainsForResolver;
        this.dispatcher =
                new GeneratedInputProcessorDispatcher(new GeneratedInputProcessorDispatcher.ReflectiveContinuation() {
                    @Override
                    public Object continueAt(
                            Object intermediate,
                            Class<?> targetType,
                            InputTraversalContext ctx,
                            InputLocation location,
                            String fieldPath,
                            Class<?> ownerType) {
                        return DefaultInputObjectProcessor.this.continueAt(
                                intermediate, targetType, ctx, location, fieldPath, ownerType);
                    }

                    @Override
                    public Object walkUnknown(
                            Object intermediate,
                            InputTraversalContext ctx,
                            InputLocation location,
                            String fieldPath,
                            Class<?> ownerType) {
                        return DefaultInputObjectProcessor.this.walkUnknown(
                                intermediate, ctx, location, fieldPath, ownerType);
                    }
                });
    }

    @Override
    public void precomputeFieldNameResolution(@Nullable Type declaredType, InputFieldNameResolver resolver) {
        OwnerTypeWalk.prepare(declaredType, resolver, metadataResolver, dispatcher);
    }

    @Override
    public Object processInput(
            Object input,
            Type targetType,
            EffectiveInputPolicies policies,
            InputLocation location,
            InputFieldNameResolver nameResolver) {
        if (input == null) {
            return null;
        }

        Class<?> targetClass = TypeClassifier.classify(targetType);
        if (targetClass == null) {
            if (!policies.isEmpty()) {
                throw new IllegalStateException("Input processing was declared for " + location
                        + " but the target type " + targetType.getTypeName()
                        + " reduces to no class, so the declared canonicalizers and sanitizers cannot be "
                        + "applied. Declare a target type this engine can classify — a class, a "
                        + "parameterized type, a bounded type variable or wildcard, an Optional of any of "
                        + "those, or an array of them.");
            }
            return input;
        }

        InputTraversalContext ctx = InputTraversalContext.fromPolicies(policies, nameResolver);

        if (input instanceof Map<?, ?> map) {
            Optional<GeneratedInputProcessor<Object>> generated = dispatcher.resolve(asObjectClass(targetClass));
            if (generated.isPresent()) {
                // Pass the REAL root context, never null: a generated processor handed a null parent
                // re-seeds with IDENTITY naming, and its per-field switch would then stop matching a
                // renamed wire key — silently disabling the declared policies on exactly the path
                // REST takes when codegen is active.
                return generated.get().process(input, policies, location, chainResolver, dispatcher, ctx, "");
            }
            InputPolicyMetadata metadata = metadataResolver.resolve(targetClass);
            return processMap(map, metadata, ctx, policies, location, "", targetClass);
        }
        if (input instanceof List<?> list) {
            // For parameterized collection types (e.g. List<MyDto>) and arrays (MyDto[]), resolve element type
            // metadata.
            // The runtime fast-path consults the dispatcher for the ELEMENT class — codegen never emits a List<X> or
            // X[] processor; it emits X_InputProcessor and the iteration is handled here.
            Class<?> elementClass = topLevelElementTypes
                    .computeIfAbsent(targetType, t -> Optional.ofNullable(TypeClassifier.elementType(t)))
                    .orElse(null);
            if (elementClass != null) {
                Optional<GeneratedInputProcessor<Object>> generated = dispatcher.resolve(asObjectClass(elementClass));
                if (generated.isPresent()) {
                    return generatedListWalk(list, generated.get(), policies, location, ctx);
                }
            }
            InputPolicyMetadata elementMeta =
                    elementClass != null ? metadataResolver.resolve(elementClass) : InputPolicyMetadata.EMPTY;
            Class<?> elementOwner = elementClass != null ? elementClass : targetClass;
            return processList(list, elementMeta, ctx, policies, location, "", elementOwner);
        }
        if (input instanceof String s) {
            return applyChains(
                    s, ctx.inheritedCanonicalizerChain(), ctx.inheritedSanitizerChain(), location, "", "", targetClass);
        }
        return input;
    }

    /**
     * Iterates a top-level list/array input whose element type has a generated processor.
     * Each {@link Map} element is delegated to the generated processor; non-map elements are
     * passed through unchanged. The {@code parentPath} for each element is {@code "[N]"} so
     * that field paths inside the generated processor read as {@code "[N].fieldName"}.
     *
     * @param list      the intermediate list (also produced by JSON-array bodies for {@code E[]})
     * @param generated the generated processor for the element type
     * @param policies  invocation-level policies passed through to the processor
     * @param location  request origin
     * @param rootCtx   the root traversal context, handed to the generated processor as its parent
     *                  so the invocation-level chains <em>and</em> the name projection survive
     * @return a new list with each map element processed
     */
    private Object generatedListWalk(
            List<?> list,
            GeneratedInputProcessor<Object> generated,
            EffectiveInputPolicies policies,
            InputLocation location,
            InputTraversalContext rootCtx) {

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            if (element instanceof Map<?, ?>) {
                // Pass the REAL root context, never null — same reason as the top-level map entry
                // point: a null parent makes the generated processor re-seed IDENTITY naming and
                // silently stop matching renamed wire keys.
                // Pass "[N]" as parentPath so field paths inside the processor read as "[N].fieldName".
                String elementPath = "[" + index + "]";
                result.add(generated.process(
                        element, policies, location, chainResolver, dispatcher, rootCtx, elementPath));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    // --- Map traversal ---

    /**
     * Processes a map by applying chains to each string-valued entry and recursing into
     * nested maps and lists.
     *
     * <p>Each key is projected through {@code ctx}'s {@link InputFieldNameResolver} before its
     * per-field metadata is looked up, because the map is keyed by wire names and
     * {@link InputPolicyMetadata#fields()} by Java property names. The output map is keyed by the
     * original wire names.
     *
     * <p><strong>Schema-free traversal skips the projection entirely.</strong> When {@code metadata}
     * carries no declared fields — {@link InputPolicyMetadata#EMPTY}, or the resolved metadata of a
     * type with no statically known property set — no key sent to this map can ever match
     * {@link InputPolicyMetadata#fields()}, so the projection is provably a no-op. Calling it anyway
     * would be the only site in the engine that consults {@link InputFieldNameResolver} for an owner
     * that is not a field-name owner (a raw collection, a {@code Map}/{@code Object} target, a
     * wire/declared shape mismatch, an unknown subtree); {@code ownerType} still flows into every
     * {@link InputValueContext} produced for this map's values, so processing provenance is
     * unaffected. See {@link OwnerTypeWalk} for the matching change to what gets prepared.
     *
     * @param map        the input map (keys may be any type, values may be any type)
     * @param metadata   annotation metadata for the owner type
     * @param ctx        the accumulated traversal context (ancestor chains + skip flags)
     * @param policies   invocation-level effective policies (passed to dispatcher for nested types)
     * @param location   request origin location
     * @param pathPrefix dot-separated path prefix for nested fields
     * @param ownerType  the Java type that declared the fields in this map
     * @return a new map with transformed string values
     */
    private Map<String, Object> processMap(
            Map<?, ?> map,
            InputPolicyMetadata metadata,
            InputTraversalContext ctx,
            EffectiveInputPolicies policies,
            InputLocation location,
            String pathPrefix,
            Class<?> ownerType) {

        Map<String, Object> result = new LinkedHashMap<>(map.size());
        boolean schemaFree = metadata.fields().isEmpty();

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            String fieldPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;

            if (value == null) {
                result.put(key, null);
                continue;
            }

            // The intermediate is keyed by WIRE names; metadata.fields() is keyed by JAVA property
            // names. Project before the lookup, or a renamed field never matches its own policies.
            // The result map keeps the wire key — the projection selects metadata, it never renames
            // what the codec will bind. A schema-free owner has no field to ever match, so the
            // projection is skipped rather than run and discarded.
            FieldPolicyMetadata fieldMeta;
            String logicalName;
            // The type whose declared policies apply to this key, the metadata they live in, and the
            // context they compose against. All three change for a key the codec PROMOTED into this
            // object out of a nested member (Jackson's @JsonUnwrapped): its policies are declared on
            // the inner type, and the enclosing members' own chains and skip flags still apply.
            Class<?> declaringType = ownerType;
            InputPolicyMetadata valueMeta = metadata;
            InputTraversalContext valueCtx = ctx;
            if (schemaFree) {
                fieldMeta = null;
                logicalName = key;
            } else {
                String logicalKey = ctx.logicalFieldName(ownerType, key);
                fieldMeta = metadata.fields().get(logicalKey);
                if (fieldMeta == null) {
                    // Looked up by the WIRE key, never the projected name: a promoted key is not a
                    // name of this type, so the projection returns it unchanged and resolving it is
                    // the projection's own job (ADR-0247 Amendment 2).
                    PromotedField promoted = ctx.promotedField(ownerType, key);
                    if (promoted != null) {
                        InputPolicyMetadata levelMeta = metadata;
                        InputTraversalContext levelCtx = ctx;
                        boolean reachable = true;
                        for (String enclosing : promoted.enclosingPath()) {
                            FieldPolicyMetadata enclosingMeta =
                                    levelMeta.fields().get(enclosing);
                            if (enclosingMeta == null) {
                                reachable = false;
                                break;
                            }
                            levelCtx = levelCtx.descend(levelMeta, enclosingMeta);
                            levelMeta = metadataResolver.resolve(enclosingMeta.fieldType());
                        }
                        FieldPolicyMetadata promotedMeta =
                                reachable ? levelMeta.fields().get(promoted.fieldName()) : null;
                        if (promotedMeta != null) {
                            fieldMeta = promotedMeta;
                            declaringType = promoted.declaringType();
                            valueMeta = levelMeta;
                            valueCtx = levelCtx;
                            logicalKey = promoted.fieldName();
                        }
                    }
                }
                // logicalName is the JAVA property name once a property matched, the wire name
                // otherwise; path stays the wire path so a diagnostic points at what the caller sent.
                logicalName = fieldMeta != null ? logicalKey : key;
            }

            if (value instanceof String s) {
                result.put(
                        key,
                        processStringValue(
                                s, valueMeta, fieldMeta, valueCtx, location, fieldPath, logicalName, declaringType));
            } else if (value instanceof Map<?, ?> nestedMap) {
                result.put(
                        key,
                        processNestedMap(
                                nestedMap,
                                valueMeta,
                                fieldMeta,
                                valueCtx,
                                policies,
                                location,
                                fieldPath,
                                declaringType));
            } else if (value instanceof List<?> list) {
                result.put(
                        key,
                        processNestedList(
                                list, valueMeta, fieldMeta, valueCtx, policies, location, fieldPath, declaringType));
            } else {
                result.put(key, value);
            }
        }

        return result;
    }

    /**
     * Processes a nested map by determining the appropriate metadata and computing a child
     * traversal context, then routing through the dispatcher so a generated processor for the
     * nested type takes over when one is available.
     *
     * @param nestedMap  the nested map
     * @param parentMeta metadata for the parent type
     * @param fieldMeta  metadata for this specific field (may be {@code null})
     * @param ctx        the current traversal context
     * @param policies   invocation-level effective policies
     * @param location   request origin location
     * @param fieldPath  current dot-separated path
     * @param ownerType  the parent type
     * @return processed nested map
     */
    private Object processNestedMap(
            Map<?, ?> nestedMap,
            InputPolicyMetadata parentMeta,
            FieldPolicyMetadata fieldMeta,
            InputTraversalContext ctx,
            EffectiveInputPolicies policies,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {

        InputTraversalContext childCtx = ctx.descend(parentMeta, fieldMeta);
        if (fieldMeta == null || fieldMeta.fieldType() == null) {
            // Unknown nested type (extra Jackson key, Object field, Map<String, Object> field) —
            // walk reflectively with EMPTY metadata so only inherited invocation/type chains apply.
            // The dispatcher must NOT be consulted: passing the parent's class would either resolve
            // the parent's generated processor (re-applying the parent's per-field switch to the
            // child map) or fall through to continueAt with targetType = ownerType (re-applying
            // the parent's metadata).
            return processMap(
                    nestedMap,
                    InputPolicyMetadata.EMPTY,
                    childCtx,
                    EffectiveInputPolicies.NONE,
                    location,
                    fieldPath,
                    ownerType);
        }
        Class<?> nestedType = fieldMeta.fieldType();
        return dispatcher.dispatchNested(
                nestedMap, nestedType, policies, location, chainResolver, childCtx, fieldPath, nestedType);
    }

    /**
     * Processes a list by iterating its elements and applying appropriate processing to each.
     *
     * @param list       the list of elements
     * @param metadata   annotation metadata for the context type
     * @param ctx        the accumulated traversal context
     * @param policies   invocation-level effective policies
     * @param location   request origin location
     * @param pathPrefix current path prefix
     * @param ownerType  the Java type context
     * @return a new list with processed elements
     */
    private List<Object> processList(
            List<?> list,
            InputPolicyMetadata metadata,
            InputTraversalContext ctx,
            EffectiveInputPolicies policies,
            InputLocation location,
            String pathPrefix,
            Class<?> ownerType) {

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String elementPath = pathPrefix.isEmpty() ? "[" + index + "]" : pathPrefix + "[" + index + "]";
            if (element instanceof String s) {
                result.add(applyChains(
                        s,
                        ctx.inheritedCanonicalizerChain(),
                        ctx.inheritedSanitizerChain(),
                        location,
                        elementPath,
                        elementPath,
                        ownerType));
            } else if (element instanceof Map<?, ?> map) {
                result.add(dispatcher.dispatchNested(
                        map, ownerType, policies, location, chainResolver, ctx, elementPath, ownerType));
            } else if (element instanceof List<?> nested) {
                result.add(processList(nested, metadata, ctx, policies, location, elementPath, ownerType));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    /**
     * Processes a nested list field found inside a map entry.
     *
     * @param list       the list value
     * @param parentMeta metadata for the parent map's type
     * @param fieldMeta  metadata for the current field (may be {@code null})
     * @param ctx        the current traversal context
     * @param policies   invocation-level effective policies
     * @param location   request origin
     * @param fieldPath  current path
     * @param ownerType  parent owner type
     * @return processed list
     */
    private List<Object> processNestedList(
            List<?> list,
            InputPolicyMetadata parentMeta,
            FieldPolicyMetadata fieldMeta,
            InputTraversalContext ctx,
            EffectiveInputPolicies policies,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {

        if (fieldMeta != null) {
            if (fieldMeta.isCollectionOfStrings()) {
                // List<String> — apply the full chain (inherited + object + field) to each element
                return processCollectionOfStrings(list, parentMeta, fieldMeta, ctx, location, fieldPath, ownerType);
            }
            if (fieldMeta.collectionElementType() != null) {
                // List<NestedObject> — dispatch each element at the declared element type, whose
                // own metadata is resolved at descent.
                InputTraversalContext childCtx = ctx.descend(parentMeta, fieldMeta);
                return processListOfObjects(
                        list, childCtx, policies, location, fieldPath, fieldMeta.collectionElementType());
            }
        }
        // No field schema: walk the list reflectively with EMPTY metadata. Descend `ctx` once
        // with the parent's object-level layer (and the field-level layer when fieldMeta is
        // non-null but its shape isn't recognized) so the type chain still flows into the
        // unknown subtree — mirroring processNestedMap's descend at the same call shape and
        // GeneratedSupport.applyDefault's descend before dispatcher.walkUnknown(...). Use
        // walkUnknown rather than processList(EMPTY, …): processList's map-element branch
        // unconditionally calls dispatcher.dispatchNested(map, ownerType, …), which would
        // resolve the parent's generated processor and re-apply its schema to a child map.
        InputTraversalContext childCtx = ctx.descend(parentMeta, fieldMeta);
        Object walked = walkUnknown(list, childCtx, location, fieldPath, ownerType);
        @SuppressWarnings("unchecked")
        List<Object> result = (List<Object>) walked;
        return result;
    }

    /**
     * Processes a {@code Collection<String>} field by applying the full effective chain
     * (inherited + object + field) to each string element.
     *
     * @param list       the list of string elements
     * @param typeMeta   metadata for the owner type (object-level chains and skip flags)
     * @param fieldMeta  metadata for the collection field
     * @param ctx        the accumulated traversal context
     * @param location   request origin
     * @param fieldPath  current dot-separated path for the collection field
     * @param ownerType  the owner type
     * @return a new list with each string element processed through the full chain
     */
    private List<Object> processCollectionOfStrings(
            List<?> list,
            InputPolicyMetadata typeMeta,
            FieldPolicyMetadata fieldMeta,
            InputTraversalContext ctx,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {

        List<Class<? extends Canonicalizer>> canonChain = buildCanonicalizerChain(ctx, typeMeta, fieldMeta);
        List<Class<? extends Sanitizer>> sanitChain = buildSanitizerChain(ctx, typeMeta, fieldMeta);

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String elementPath = fieldPath + "[" + index + "]";
            if (element instanceof String s) {
                result.add(applyChains(s, canonChain, sanitChain, location, elementPath, elementPath, ownerType));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    /**
     * Processes a list of map elements, dispatching each {@link Map} element through the
     * dispatcher so generated processors for the element type are picked up.
     *
     * @param list        the list of elements (expected to be maps)
     * @param ctx         the child traversal context (already descended)
     * @param policies    invocation-level effective policies (passed through to dispatcher)
     * @param location    request origin
     * @param pathPrefix  current path prefix
     * @param elementType the element Java type
     * @return processed list
     */
    private List<Object> processListOfObjects(
            List<?> list,
            InputTraversalContext ctx,
            EffectiveInputPolicies policies,
            InputLocation location,
            String pathPrefix,
            Class<?> elementType) {

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String elementPath = pathPrefix + "[" + index + "]";
            if (element instanceof Map<?, ?> map) {
                result.add(dispatcher.dispatchNested(
                        map, elementType, policies, location, chainResolver, ctx, elementPath, elementType));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    // --- String value processing ---

    /**
     * Processes a single string value by composing and applying the effective canonicalizer
     * and sanitizer chains.
     *
     * @param value     the raw string value
     * @param typeMeta  metadata for the owner type (object-level chains and skip flags)
     * @param fieldMeta metadata for the specific field, or {@code null}
     * @param ctx       the accumulated traversal context (ancestor chains + skip flags)
     * @param location  request origin
     * @param path      dot-separated path for the {@link InputValueContext}
     * @param logName   logical field name for the {@link InputValueContext}
     * @param ownerType the owner type
     * @return the processed string value
     */
    private String processStringValue(
            String value,
            InputPolicyMetadata typeMeta,
            FieldPolicyMetadata fieldMeta,
            InputTraversalContext ctx,
            InputLocation location,
            String path,
            String logName,
            Class<?> ownerType) {

        List<Class<? extends Canonicalizer>> canonChain = buildCanonicalizerChain(ctx, typeMeta, fieldMeta);
        List<Class<? extends Sanitizer>> sanitChain = buildSanitizerChain(ctx, typeMeta, fieldMeta);

        return applyChains(value, canonChain, sanitChain, location, path, logName, ownerType);
    }

    // --- Reflective continuation (called by GeneratedInputProcessorDispatcher) ---

    /**
     * Resumes reflective traversal for a nested type that has no generated processor. Invoked
     * by {@link GeneratedInputProcessorDispatcher} when neither a manually registered nor a
     * classloader-resolved generated processor exists for the nested type, preserving the
     * accumulated {@link InputTraversalContext} from the caller.
     *
     * <p><strong>String fragments are chain-applied, not passed through.</strong> A generated
     * processor's nested-DTO arm dispatches whatever the wire produced for that field — which
     * is a plain string whenever the declared type is an opaque string-backed value type (for
     * example {@code java.net.URI}) that has no generated processor. Mirroring
     * {@link #walkUnknown}'s string branch here keeps the field's own chain (already folded into
     * {@code ctx} by the arm's {@code descend(...)}) effective instead of silently dropping it.
     * This is safe against double application: every reflective {@code dispatchNested} call site
     * ({@link #processNestedMap}, {@link #processList}, {@link #processListOfObjects}) passes
     * {@link Map} values, so the string branch is reachable only from generated arms where no
     * chain has been applied yet.
     *
     * @param intermediate the nested intermediate fragment
     * @param targetType   the nested type
     * @param ctx          the accumulated traversal context
     * @param location     request origin
     * @param fieldPath    dot-separated path so far, for diagnostic context
     * @param ownerType    owner type for {@link InputValueContext}
     * @return the processed intermediate
     */
    private Object continueAt(
            Object intermediate,
            Class<?> targetType,
            InputTraversalContext ctx,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {
        if (intermediate == null) {
            return null;
        }
        if (intermediate instanceof Map<?, ?> map) {
            InputPolicyMetadata metadata = metadataResolver.resolve(targetType);
            // Continuation is invoked with a context already descended for the nested field, so
            // policies are not consulted here for chain composition (only threaded for further
            // recursion). Reconstruct EffectiveInputPolicies.NONE for the secondary path —
            // invocation-level chains live entirely in `ctx.inheritedCanonicalizerChain()` /
            // `ctx.inheritedSanitizerChain()` already.
            return processMap(map, metadata, ctx, EffectiveInputPolicies.NONE, location, fieldPath, targetType);
        }
        if (intermediate instanceof List<?> list) {
            InputPolicyMetadata metadata = metadataResolver.resolve(targetType);
            return processList(list, metadata, ctx, EffectiveInputPolicies.NONE, location, fieldPath, targetType);
        }
        if (intermediate instanceof String s) {
            InputValueContext valueCtx = new InputValueContext(location, fieldPath, fieldPath, ownerType);
            return applyChainsForResolver(
                    s, ctx.inheritedCanonicalizerChain(), ctx.inheritedSanitizerChain(), valueCtx);
        }
        return intermediate;
    }

    /**
     * Walks an unknown-type intermediate with {@link InputPolicyMetadata#EMPTY} per-field
     * metadata, applying only the inherited chains already accumulated in {@code ctx}. Invoked
     * by {@link GeneratedInputProcessorDispatcher} for unknown nested keys — extra Jackson keys,
     * {@code Object}-typed fields, and {@code Map<String, Object>} fields — where no schema is
     * available and the parent's generated processor must not be re-applied.
     *
     * @param intermediate the fragment to process (map / list / string / other)
     * @param ctx          the accumulated traversal context (with inherited chains)
     * @param location     request origin
     * @param fieldPath    dot-separated path so far, for diagnostic context
     * @param ownerType    owner type for {@link dev.vertique.core.sanitization.InputValueContext}
     * @return the processed fragment, applying only inherited chains
     */
    private Object walkUnknown(
            Object intermediate,
            InputTraversalContext ctx,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {
        if (intermediate == null) {
            return null;
        }
        if (intermediate instanceof Map<?, ?> map) {
            return processMap(
                    map, InputPolicyMetadata.EMPTY, ctx, EffectiveInputPolicies.NONE, location, fieldPath, ownerType);
        }
        if (intermediate instanceof List<?> list) {
            // Walk the list element-by-element via walkUnknown so map elements never re-enter
            // dispatcher.dispatchNested with the parent class as nestedType — that would
            // resolve the parent's generated processor and re-apply its schema to a child map.
            // processList(EMPTY, ...) had that bug because its map-element branch
            // unconditionally calls dispatcher.dispatchNested(map, ownerType, ...).
            List<Object> result = new ArrayList<>(list.size());
            int index = 0;
            for (Object element : list) {
                String elementPath = fieldPath + "[" + index + "]";
                result.add(walkUnknown(element, ctx, location, elementPath, ownerType));
                index++;
            }
            return result;
        }
        if (intermediate instanceof String s) {
            InputValueContext valueCtx = new InputValueContext(location, fieldPath, fieldPath, ownerType);
            return applyChainsForResolver(
                    s, ctx.inheritedCanonicalizerChain(), ctx.inheritedSanitizerChain(), valueCtx);
        }
        return intermediate;
    }

    // --- Chain composition ---

    /**
     * Composes the effective canonicalizer chain for a field from the traversal context's
     * inherited chains, the object-level declarations, and field-level declarations, respecting
     * sticky skip flags.
     *
     * <p>Skip precedence (highest to lowest):
     * <ol>
     *   <li>Inherited skip from any ancestor ({@link InputTraversalContext#inheritedSkipCanonicalization()})</li>
     *   <li>Field-level {@code @SkipCanonicalization}</li>
     *   <li>Object-level {@code @SkipCanonicalization} (unless field has its own chain)</li>
     * </ol>
     *
     * @param ctx       the accumulated traversal context
     * @param typeMeta  object-level metadata
     * @param fieldMeta field-level metadata (may be {@code null})
     * @return the effective ordered chain, or an empty list if canonicalization is skipped
     */
    private static List<Class<? extends Canonicalizer>> buildCanonicalizerChain(
            InputTraversalContext ctx, InputPolicyMetadata typeMeta, @Nullable FieldPolicyMetadata fieldMeta) {

        if (ctx.inheritedSkipCanonicalization()) {
            return List.of();
        }
        if (fieldMeta != null && fieldMeta.skipCanonicalization()) {
            return List.of();
        }
        boolean fieldHasOwnChain =
                fieldMeta != null && !fieldMeta.canonicalizerChain().isEmpty();
        if (typeMeta.skipCanonicalization() && !fieldHasOwnChain) {
            return List.of();
        }
        return ctx.compose(
                ctx.inheritedCanonicalizerChain(),
                typeMeta.ownerType(),
                fieldMeta != null ? fieldMeta.fieldName() : null,
                typeMeta.objectCanonicalizerChain(),
                fieldMeta != null ? fieldMeta.canonicalizerChain() : List.of());
    }

    /**
     * Composes the effective sanitizer chain for a field from the traversal context's inherited
     * chains, the object-level declarations, and field-level declarations, respecting sticky
     * skip flags.
     *
     * @param ctx       the accumulated traversal context
     * @param typeMeta  object-level metadata
     * @param fieldMeta field-level metadata (may be {@code null})
     * @return the effective ordered chain, or an empty list if sanitization is skipped
     */
    private static List<Class<? extends Sanitizer>> buildSanitizerChain(
            InputTraversalContext ctx, InputPolicyMetadata typeMeta, @Nullable FieldPolicyMetadata fieldMeta) {

        if (ctx.inheritedSkipSanitization()) {
            return List.of();
        }
        if (fieldMeta != null && fieldMeta.skipSanitization()) {
            return List.of();
        }
        boolean fieldHasOwnChain =
                fieldMeta != null && !fieldMeta.sanitizerChain().isEmpty();
        if (typeMeta.skipSanitization() && !fieldHasOwnChain) {
            return List.of();
        }
        return ctx.compose(
                ctx.inheritedSanitizerChain(),
                typeMeta.ownerType(),
                fieldMeta != null ? fieldMeta.fieldName() : null,
                typeMeta.objectSanitizerChain(),
                fieldMeta != null ? fieldMeta.sanitizerChain() : List.of());
    }

    // --- Chain application ---

    /**
     * Applies canonicalizer and sanitizer chains to a single string value.
     *
     * @param value       the input string
     * @param canonChain  canonicalizer classes to apply in order
     * @param sanitChain  sanitizer classes to apply in order
     * @param location    request origin for the context record
     * @param path        dot-separated property path
     * @param logicalName field name for the context record
     * @param ownerType   owning type for the context record
     * @return the fully processed string
     */
    private String applyChains(
            String value,
            List<Class<? extends Canonicalizer>> canonChain,
            List<Class<? extends Sanitizer>> sanitChain,
            InputLocation location,
            String path,
            String logicalName,
            Class<?> ownerType) {

        InputValueContext inputCtx = new InputValueContext(location, path, logicalName, ownerType);
        return applyChainsForResolver(value, canonChain, sanitChain, inputCtx);
    }

    /**
     * Backing implementation for the {@link ChainResolver} contract exposed to generated
     * processors. Identical chain-application semantics to {@link #applyChains}, but takes a
     * pre-built {@link InputValueContext} (the generated code constructs its own).
     */
    private String applyChainsForResolver(
            String value,
            List<Class<? extends Canonicalizer>> canonChain,
            List<Class<? extends Sanitizer>> sanitChain,
            InputValueContext valueCtx) {

        String result = value;
        for (Class<? extends Canonicalizer> cls : canonChain) {
            result = canonicalizers.get(cls).canonicalize(result, valueCtx);
        }
        for (Class<? extends Sanitizer> cls : sanitChain) {
            result = sanitizers.get(cls).sanitize(result, valueCtx);
        }
        return result;
    }

    // --- Processor resolution cache ---

    /**
     * Memoizes one caller-supplied processor resolver function per engine instance, so the function
     * is consulted once per processor class rather than once per string value.
     *
     * <p>The cache is <strong>instance-owned</strong>: it is reachable only from the owning
     * {@link DefaultInputObjectProcessor} and becomes collectible with it. It deliberately does not
     * use a static or {@link ClassValue} cache — a static {@code Class}-keyed map is the retention
     * hazard {@link GeneratedInputProcessorDispatcher} avoids, and a per-engine map has no such
     * problem because its lifetime is already bounded by the component-scoped engine.
     *
     * <p><strong>Concurrency.</strong> The engine is shared across requests on several event-loop
     * threads. Lookups take the lock-free {@link ConcurrentHashMap#get} path and a miss computes the
     * value <em>outside</em> the map before publishing it with
     * {@link ConcurrentHashMap#putIfAbsent}, so no request thread ever waits on another while an
     * arbitrary caller-supplied function runs. {@code computeIfAbsent} is deliberately not used: it
     * would hold the bin lock across that function and would deadlock if a resolver ever re-entered
     * the cache. The trade-off is that two threads racing on a cold key may both invoke the
     * resolver; one result wins and the other is discarded. That is benign — canonicalizers and
     * sanitizers are stateless, and the existing resolver already allocates a fresh instance per
     * reflective resolution.
     *
     * @param <T> the processor kind — {@link Canonicalizer} or {@link Sanitizer}
     */
    private static final class ResolutionCache<T> {

        private final Function<Class<? extends T>, T> resolver;
        private final ConcurrentMap<Class<? extends T>, Resolution<T>> entries = new ConcurrentHashMap<>();

        /**
         * Creates a cache over the given caller-supplied resolver function.
         *
         * @param resolver the function that produces a processor instance from its class
         */
        ResolutionCache(Function<Class<? extends T>, T> resolver) {
            this.resolver = resolver;
        }

        /**
         * Returns the processor instance for the given class, resolving it through the wrapped
         * function on first use.
         *
         * @param type the processor class to resolve
         * @return the cached processor instance; never {@code null}
         * @throws RuntimeException the failure this class resolved to — the exception the resolver
         *                          raised, or an {@link IllegalStateException} naming the class when
         *                          the resolver returned {@code null}. Either is captured on the
         *                          first attempt and rethrown on every later one, so an unresolvable
         *                          processor still fails the request without re-running a lookup
         *                          already known to fail
         */
        T get(Class<? extends T> type) {
            Resolution<T> cached = entries.get(type);
            if (cached == null) {
                cached = resolve(type);
                Resolution<T> published = entries.putIfAbsent(type, cached);
                if (published != null) {
                    cached = published;
                }
            }
            return switch (cached) {
                case Resolution.Resolved<T> resolved -> resolved.instance();
                case Resolution.Failed<T> failed -> throw failed.error();
            };
        }

        /**
         * Invokes the resolver once, capturing either the instance or the failure it raised.
         *
         * <p>Only a {@link RuntimeException} is captured. An {@link Error} propagates uncached: it
         * signals a JVM-level problem (a failing static initializer, a linkage error) whose retry
         * semantics are not this cache's to decide.
         *
         * <p><strong>A null resolution is a failed resolution.</strong> Caching {@code null} as a
         * success would make every later value for that class die on a bare
         * {@link NullPointerException} that names no processor class, for the life of the engine —
         * the resolver having returned null is long out of the stack by then. Rejecting it here
         * turns an unbound processor into the same named, cached diagnostic a throwing resolver
         * produces.
         *
         * @param type the processor class to resolve
         * @return the resolution outcome to cache
         */
        private Resolution<T> resolve(Class<? extends T> type) {
            try {
                T instance = resolver.apply(type);
                if (instance == null) {
                    return new Resolution.Failed<>(new IllegalStateException(
                            "The configured resolver returned no instance for " + type.getName()
                                    + ". Every canonicalizer and sanitizer named by a declared chain must be "
                                    + "resolvable; bind it, or remove it from the chain that names it."));
                }
                return new Resolution.Resolved<>(instance);
            } catch (RuntimeException failure) {
                return new Resolution.Failed<>(failure);
            }
        }
    }

    /**
     * A cached processor-resolution outcome. Mirrors {@link GeneratedInputProcessorDispatcher}'s
     * lookup-result model: a failure is cached alongside a success so a broken binding costs one
     * lookup rather than one per value, and the captured exception is rethrown on every retrieval so
     * consumers still see the failure immediately.
     *
     * @param <T> the processor kind
     */
    private sealed interface Resolution<T> {

        /**
         * A successful resolution.
         *
         * @param instance the resolved processor instance
         * @param <T>      the processor kind
         */
        record Resolved<T>(T instance) implements Resolution<T> {}

        /**
         * A resolution that failed. The captured exception is rethrown on every retrieval.
         *
         * @param error the failure the resolver raised
         * @param <T>   the processor kind
         */
        record Failed<T>(RuntimeException error) implements Resolution<T> {}
    }

    // --- Type extraction ---

    /**
     * Erases a wildcard-captured class reference to {@code Class<Object>} so the dispatcher's
     * generic-typed {@code resolve(Class<T>)} can be called from sites that only have a
     * {@code Class<?>}. This is safe because the resolved processor is a
     * {@code GeneratedInputProcessor<Object>} from the dispatcher's perspective — its actual
     * target type is checked at runtime by the dispatcher's classloader lookup against the
     * given class name.
     */
    @SuppressWarnings("unchecked")
    private static Class<Object> asObjectClass(Class<?> c) {
        return (Class<Object>) c;
    }
}
