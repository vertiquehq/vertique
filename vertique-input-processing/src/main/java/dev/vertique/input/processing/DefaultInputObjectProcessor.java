// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.input.processing.InputPolicyMetadata.FieldPolicyMetadata;
import jakarta.annotation.Nullable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final Function<Class<? extends Canonicalizer>, Canonicalizer> canonicalizerResolver;
    private final Function<Class<? extends Sanitizer>, Sanitizer> sanitizerResolver;
    private final ChainResolver chainResolver;
    private final GeneratedInputProcessorDispatcher dispatcher;

    /**
     * Creates a new processor with the given dependencies.
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
        this.canonicalizerResolver = canonicalizerResolver;
        this.sanitizerResolver = sanitizerResolver;
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
    public Object processInput(Object input, Type targetType, EffectiveInputPolicies policies, InputLocation location) {
        if (input == null) {
            return null;
        }

        Class<?> targetClass = extractClass(targetType);
        if (targetClass == null) {
            return input;
        }

        InputTraversalContext ctx = InputTraversalContext.fromPolicies(policies);

        if (input instanceof Map<?, ?> map) {
            Optional<GeneratedInputProcessor<Object>> generated = dispatcher.resolve(asObjectClass(targetClass));
            if (generated.isPresent()) {
                return generated.get().process(input, policies, location, chainResolver, dispatcher, null, "");
            }
            InputPolicyMetadata metadata = metadataResolver.resolve(targetClass);
            return processMap(map, metadata, ctx, policies, location, "", targetClass);
        }
        if (input instanceof List<?> list) {
            // For parameterized collection types (e.g. List<MyDto>) and arrays (MyDto[]), resolve element type
            // metadata.
            // The runtime fast-path consults the dispatcher for the ELEMENT class — codegen never emits a List<X> or
            // X[] processor; it emits X_InputProcessor and the iteration is handled here.
            Class<?> elementClass = extractElementType(targetType);
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
     * @param rootCtx   the root traversal context for non-trivial invocation-level chains
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
                // Pass null parent so the generated processor seeds with fromPolicies(policies);
                // rootCtx for top-level entries is equivalent to fromPolicies(policies).
                // Pass "[N]" as parentPath so field paths inside the processor read as "[N].fieldName".
                String elementPath = "[" + index + "]";
                result.add(
                        generated.process(element, policies, location, chainResolver, dispatcher, null, elementPath));
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

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            String fieldPath = pathPrefix.isEmpty() ? key : pathPrefix + "." + key;

            if (value == null) {
                result.put(key, null);
                continue;
            }

            FieldPolicyMetadata fieldMeta = metadata.fields().get(key);

            if (value instanceof String s) {
                result.put(key, processStringValue(s, metadata, fieldMeta, ctx, location, fieldPath, key, ownerType));
            } else if (value instanceof Map<?, ?> nestedMap) {
                result.put(
                        key,
                        processNestedMap(
                                nestedMap, metadata, fieldMeta, ctx, policies, location, fieldPath, ownerType));
            } else if (value instanceof List<?> list) {
                result.put(
                        key,
                        processNestedList(list, metadata, fieldMeta, ctx, policies, location, fieldPath, ownerType));
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
            if (fieldMeta.nestedMetadata() != null && fieldMeta.collectionElementType() != null) {
                // List<NestedObject> — process each element as a map with nested metadata
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
        return compose(
                ctx.inheritedCanonicalizerChain(),
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
        return compose(
                ctx.inheritedSanitizerChain(),
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
            result = canonicalizerResolver.apply(cls).canonicalize(result, valueCtx);
        }
        for (Class<? extends Sanitizer> cls : sanitChain) {
            result = sanitizerResolver.apply(cls).sanitize(result, valueCtx);
        }
        return result;
    }

    // --- Type extraction ---

    /**
     * Extracts the raw {@link Class} from a {@link Type}, handling both plain classes and
     * parameterized types.
     *
     * @param type the type to extract from
     * @return the raw class, or {@code null} if extraction is not possible
     */
    private static Class<?> extractClass(Type type) {
        if (type instanceof Class<?> cls) return cls;
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> cls) return cls;
        return null;
    }

    /**
     * Extracts the element type from a parameterized collection type or array type.
     *
     * <p>For example, given {@code List<MyDto>} returns {@code MyDto.class};
     * given {@code MyDto[]} returns {@code MyDto.class}.
     *
     * @param type the collection or array type
     * @return the element class, or {@code null} if not determinable
     */
    private static Class<?> extractElementType(Type type) {
        if (type instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> cls) return cls;
        }
        if (type instanceof Class<?> cls && cls.isArray()) return cls.getComponentType();
        return null;
    }

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

    // --- List concatenation helper ---

    /**
     * Composes inherited + object + field chains, reusing the {@code inherited} reference
     * unchanged when both {@code object} and {@code field} are empty — eliminating an
     * {@code ArrayList} allocation per nested DTO/string field on the common no-extra-layer
     * hot path.
     *
     * @param inherited accumulated chain from ancestors; never {@code null}
     * @param object    object-level chain from the current type; never {@code null}
     * @param field     field-level chain; never {@code null} (callers pass {@code List.of()})
     * @param <T>       chain element type
     * @return a composed list, or {@code inherited} unchanged when no new entries are added
     */
    private static <T> List<T> compose(List<T> inherited, List<T> object, List<T> field) {
        if (object.isEmpty() && field.isEmpty()) {
            return inherited;
        }
        List<T> result = new ArrayList<>(inherited.size() + object.size() + field.size());
        result.addAll(inherited);
        result.addAll(object);
        result.addAll(field);
        return result;
    }
}
