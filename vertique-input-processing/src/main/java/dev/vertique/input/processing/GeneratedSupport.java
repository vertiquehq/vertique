// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitizer;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Static helpers used by codegen-emitted {@link GeneratedInputProcessor} classes via
 * {@code import static}. Centralizes string-value chain application, list-of-string traversal,
 * and list-of-object dispatch so generated source stays compact (one switch arm per field) and
 * the chain-composition semantics match the reflective walker exactly.
 *
 * <p>Public visibility is required because generated processors live in application DTO packages
 * (e.g. {@code com.example.dto}) rather than {@code dev.vertique.input.processing}; package-
 * private helpers would not be reachable.
 *
 * <p>This is a stable SPI: the helper signatures must not change in a way that would break
 * already-emitted {@code _InputProcessor} class bytecode produced by older codegen versions.
 * Hand-written callers are supported but unusual — most usage is from generated source. New
 * helpers may be added; existing helpers may not be renamed or have their parameter list
 * altered without a corresponding major version bump of {@code vertique-input-processing}.
 */
public final class GeneratedSupport {

    private GeneratedSupport() {}

    // --- String value processing ---

    /**
     * Applies the effective canonicalizer + sanitizer chain to a single string-typed field value,
     * building the {@link InputValueContext} from compile-time-resolved per-field constants. The
     * effective chain is composed in the same order as {@code DefaultInputObjectProcessor}:
     * inherited (from {@code ctx}) + object-level + field-level, with sticky skip flags applied
     * per layer.
     *
     * <p>If {@code value} is not a {@link String} (e.g. a JSON number deserialized to {@code Long}
     * for a {@code String} field — which Jackson would later reject), it is returned unchanged
     * here, mirroring the reflective walker's behavior at
     * {@code DefaultInputObjectProcessor.processMap}.
     *
     * @param value             the candidate field value; only {@link String} values are processed
     * @param ctx               the accumulated traversal context (ancestor chains + skip flags)
     * @param objectCanon       object-level canonicalizer chain on the owner type
     * @param objectSanit       object-level sanitizer chain on the owner type
     * @param objectSkipCanon   whether the owner type declares {@code @SkipCanonicalization}
     * @param objectSkipSanit   whether the owner type declares {@code @SkipSanitization}
     * @param fieldCanon        field-level canonicalizer chain
     * @param fieldSanit        field-level sanitizer chain
     * @param fieldSkipCanon    whether the field declares {@code @SkipCanonicalization}
     * @param fieldSkipSanit    whether the field declares {@code @SkipSanitization}
     * @param resolver          chain application contract
     * @param location          request origin
     * @param fieldPath         dot-separated property path for the {@link InputValueContext}
     * @param logicalName       field name for the {@link InputValueContext}
     * @param ownerType         owner type for the {@link InputValueContext}
     * @return the processed value, or the input unchanged when not a string
     */
    public static Object applyString(
            Object value,
            InputTraversalContext ctx,
            List<Class<? extends Canonicalizer>> objectCanon,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipCanon,
            boolean objectSkipSanit,
            List<Class<? extends Canonicalizer>> fieldCanon,
            List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipCanon,
            boolean fieldSkipSanit,
            ChainResolver resolver,
            InputLocation location,
            String fieldPath,
            String logicalName,
            Class<?> ownerType) {

        if (!(value instanceof String s)) {
            return value;
        }

        List<Class<? extends Canonicalizer>> canonChain = effectiveCanonChain(
                ctx, ownerType, logicalName, objectCanon, objectSkipCanon, fieldCanon, fieldSkipCanon);
        List<Class<? extends Sanitizer>> sanitChain = effectiveSanitChain(
                ctx, ownerType, logicalName, objectSanit, objectSkipSanit, fieldSanit, fieldSkipSanit);

        InputValueContext valueCtx = new InputValueContext(location, fieldPath, logicalName, ownerType);
        return resolver.apply(s, canonChain, sanitChain, valueCtx);
    }

    /**
     * Applies the effective canonicalizer + sanitizer chain to each {@link String} element of a
     * collection-of-strings field. The chain is computed once for the field and reused across
     * elements, mirroring {@code DefaultInputObjectProcessor.processCollectionOfStrings}.
     *
     * <p>If {@code value} is not a {@link List}, it is returned unchanged. Non-string elements
     * within the list are passed through unchanged.
     *
     * @param value          the candidate field value; only {@link List} values are processed
     * @param ctx            the accumulated traversal context
     * @param objectCanon    object-level canonicalizer chain on the owner type
     * @param objectSanit    object-level sanitizer chain on the owner type
     * @param objectSkipCanon whether the owner type declares {@code @SkipCanonicalization}
     * @param objectSkipSanit whether the owner type declares {@code @SkipSanitization}
     * @param fieldCanon     field-level canonicalizer chain
     * @param fieldSanit     field-level sanitizer chain
     * @param fieldSkipCanon whether the field declares {@code @SkipCanonicalization}
     * @param fieldSkipSanit whether the field declares {@code @SkipSanitization}
     * @param resolver       chain application contract
     * @param location       request origin
     * @param fieldPath      dot-separated property path for the collection field
     * @param ownerType      the owner type for {@link InputValueContext} (the DTO containing
     *                       the collection field, mirroring the reflective walker's behavior)
     * @return a new list with each string element processed; non-list inputs returned unchanged
     */
    public static Object applyStringCollection(
            Object value,
            InputTraversalContext ctx,
            List<Class<? extends Canonicalizer>> objectCanon,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipCanon,
            boolean objectSkipSanit,
            List<Class<? extends Canonicalizer>> fieldCanon,
            List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipCanon,
            boolean fieldSkipSanit,
            ChainResolver resolver,
            InputLocation location,
            String fieldPath,
            Class<?> ownerType) {

        if (!(value instanceof List<?> list)) {
            return value;
        }

        // The collection-of-strings arm is a leaf: the emitter never routes a descend through it, so
        // its (ownerType, field) site can never already be on the descent path and there is nothing
        // for a field-site key to recognize. Passing no name keeps this helper's frozen signature.
        List<Class<? extends Canonicalizer>> canonChain =
                effectiveCanonChain(ctx, ownerType, null, objectCanon, objectSkipCanon, fieldCanon, fieldSkipCanon);
        List<Class<? extends Sanitizer>> sanitChain =
                effectiveSanitChain(ctx, ownerType, null, objectSanit, objectSkipSanit, fieldSanit, fieldSkipSanit);

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String elementPath = fieldPath + "[" + index + "]";
            if (element instanceof String s) {
                InputValueContext valueCtx = new InputValueContext(location, elementPath, elementPath, ownerType);
                result.add(resolver.apply(s, canonChain, sanitChain, valueCtx));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    /**
     * Iterates a collection-of-objects field and dispatches each {@link java.util.Map} element
     * through {@link GeneratedInputProcessorDispatcher#dispatchNested}, reusing the supplied
     * {@code descendedCtx} (already descended once for the collection field) for every element.
     * Non-map elements are passed through unchanged.
     *
     * <p>Mirrors {@code DefaultInputObjectProcessor.processListOfObjects}. The owner type
     * supplied as {@code elementOwnerType} must be the collection element type (not the
     * enclosing parent DTO), so {@link InputValueContext#ownerType} matches the reflective path.
     *
     * @param value             the candidate field value; only {@link List} values are processed
     * @param nestedType        the collection element class
     * @param policies          invocation-level effective policies (passed through to nested dispatch)
     * @param location          request origin (passed through)
     * @param resolver          chain application contract (passed through)
     * @param dispatcher        the dispatcher used for nested-type lookup
     * @param descendedCtx      the traversal context already descended for the collection field
     * @param fieldPath         dot-separated property path for the collection field
     * @param elementOwnerType  the element class — used as {@code ownerType} for nested dispatch
     * @return a new list with each map element processed; non-list inputs returned unchanged
     */
    public static Object dispatchObjectCollection(
            Object value,
            Class<?> nestedType,
            EffectiveInputPolicies policies,
            InputLocation location,
            ChainResolver resolver,
            GeneratedInputProcessorDispatcher dispatcher,
            InputTraversalContext descendedCtx,
            String fieldPath,
            Class<?> elementOwnerType) {

        if (!(value instanceof List<?> list)) {
            return value;
        }

        List<Object> result = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String elementPath = fieldPath + "[" + index + "]";
            if (element instanceof java.util.Map<?, ?>) {
                result.add(dispatcher.dispatchNested(
                        element,
                        nestedType,
                        policies,
                        location,
                        resolver,
                        descendedCtx,
                        elementPath,
                        elementOwnerType));
            } else {
                result.add(element);
            }
            index++;
        }
        return result;
    }

    /**
     * Composes a child path from a parent path and a key. Used by generated processors to build
     * dot-separated property paths threaded through {@link InputValueContext}.
     *
     * @param parentPath the dot-separated path of the field this DTO is nested under, or
     *                   {@code ""} when invoked at the top level
     * @param key        the local field name
     * @return {@code key} when {@code parentPath} is empty, otherwise {@code parentPath + "." + key}
     */
    public static String childPath(String parentPath, String key) {
        return parentPath.isEmpty() ? key : parentPath + "." + key;
    }

    /**
     * Handles the {@code default} switch arm of a generated {@code process(...)} method, AND the
     * dispatch for annotated {@code OTHER}-kind fields (e.g. {@code Object misc}) whose runtime
     * value-shape is determined dynamically. Composes the effective chain from inherited
     * (invocation + ancestor) + object-level + field-level layers — mirroring the reflective walker's
     * behavior in
     * {@code DefaultInputObjectProcessor.processMap} for entries whose
     * {@link InputPolicyMetadata.FieldPolicyMetadata} is {@code null} or whose field-level data
     * is present but the value shape doesn't match.
     *
     * <p>For nested map/list values, the dispatcher's
     * {@link GeneratedInputProcessorDispatcher#walkUnknown(Object, InputTraversalContext, InputLocation, String, Class) walkUnknown}
     * resumes the reflective walker with {@link InputPolicyMetadata#EMPTY} on a child context
     * that already has the object/field layers descended into it (so inherited chains continue
     * to flow into the unknown subtree). Non-string scalars pass through unchanged.
     *
     * <p><strong>Owner-type semantics — shape-dependent.</strong> The reflective walker uses
     * different {@code ownerType} values for different value shapes on the same key:
     * <ul>
     *   <li><b>String / list-element values</b>: {@code processStringValue} /
     *       {@code processNestedList} use the <em>enclosing DTO class</em> as
     *       {@link InputValueContext#ownerType()}.</li>
     *   <li><b>Nested map values</b>: {@code processNestedMap} routes through
     *       {@code dispatchNested(map, fieldMeta.fieldType(), ..., fieldMeta.fieldType())} for
     *       annotated fields whose declared type is class-resolvable, propagating that
     *       <em>declared field type</em> as ownerType for descendants of the nested map.</li>
     * </ul>
     * The two {@code ownerType} parameters below let the emitter pass the enclosing DTO class
     * as {@code parentOwnerType} (for strings and list elements) and the field's declared type
     * as {@code nestedMapOwnerType} (for nested map values). The default arm passes the same
     * value for both (the enclosing DTO).
     *
     * @param value              the entry value (may be {@code null})
     * @param ctx                the accumulated traversal context
     * @param objectCanon        object-level canonicalizer chain on the owner type
     * @param objectSanit        object-level sanitizer chain on the owner type
     * @param objectSkipCanon    whether the owner type declares {@code @SkipCanonicalization}
     * @param objectSkipSanit    whether the owner type declares {@code @SkipSanitization}
     * @param fieldCanon         field-level canonicalizer chain (use {@code List.of()} for default arm)
     * @param fieldSanit         field-level sanitizer chain (use {@code List.of()} for default arm)
     * @param fieldSkipCanon     field-level skip-canonicalization flag
     * @param fieldSkipSanit     field-level skip-sanitization flag
     * @param resolver           chain application contract
     * @param location           request origin
     * @param fieldPath          dot-separated property path (already composed with the parent path)
     * @param logicalName        the local field name (for the {@link InputValueContext})
     * @param parentOwnerType    owner type for string and list-element values (the enclosing DTO)
     * @param nestedMapOwnerType owner type for nested-map values (the declared field type for
     *                           annotated {@code OTHER} fields, otherwise the enclosing DTO)
     * @param dispatcher         the dispatcher (used to resume reflective traversal for nested values)
     * @return the processed value, or unchanged when not a string / map / list
     */
    public static Object applyDefault(
            Object value,
            InputTraversalContext ctx,
            List<Class<? extends Canonicalizer>> objectCanon,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipCanon,
            boolean objectSkipSanit,
            List<Class<? extends Canonicalizer>> fieldCanon,
            List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipCanon,
            boolean fieldSkipSanit,
            ChainResolver resolver,
            InputLocation location,
            String fieldPath,
            String logicalName,
            Class<?> parentOwnerType,
            Class<?> nestedMapOwnerType,
            GeneratedInputProcessorDispatcher dispatcher) {

        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            // The object-level chains belong to the ENCLOSING DTO, so parentOwnerType is their
            // provenance key even on an annotated OTHER-kind field, whose nestedMapOwnerType is the
            // field's declared type. The field-level chains are declared on that same DTO, so their
            // site is parentOwnerType paired with logicalName.
            List<Class<? extends Canonicalizer>> canonChain = effectiveCanonChain(
                    ctx, parentOwnerType, logicalName, objectCanon, objectSkipCanon, fieldCanon, fieldSkipCanon);
            List<Class<? extends Sanitizer>> sanitChain = effectiveSanitChain(
                    ctx, parentOwnerType, logicalName, objectSanit, objectSkipSanit, fieldSanit, fieldSkipSanit);
            // Strings use the enclosing DTO as ownerType — matches reflective processStringValue.
            InputValueContext valueCtx = new InputValueContext(location, fieldPath, logicalName, parentOwnerType);
            return resolver.apply(s, canonChain, sanitChain, valueCtx);
        }
        if (value instanceof java.util.Map<?, ?>) {
            // Nested map: descend the ctx then walk reflectively. ownerType becomes the declared
            // field type, mirroring processNestedMap → dispatchNested(..., fieldMeta.fieldType());
            // the descend is still keyed on the enclosing DTO and this field, which is where OBJ_*
            // and the field chains were declared.
            InputTraversalContext childCtx = ctx.descend(
                    parentOwnerType,
                    logicalName,
                    objectCanon,
                    objectSanit,
                    objectSkipCanon,
                    objectSkipSanit,
                    fieldCanon,
                    fieldSanit,
                    fieldSkipCanon,
                    fieldSkipSanit);
            return dispatcher.walkUnknown(value, childCtx, location, fieldPath, nestedMapOwnerType);
        }
        if (value instanceof List<?>) {
            // Unknown list: descend the ctx then walk reflectively. List elements use the
            // ENCLOSING DTO as ownerType — matches reflective processNestedList, which walks via
            // walkUnknown(list, childCtx, ..., ownerType=parent) and recurses each element with
            // the same parent ownerType.
            InputTraversalContext childCtx = ctx.descend(
                    parentOwnerType,
                    logicalName,
                    objectCanon,
                    objectSanit,
                    objectSkipCanon,
                    objectSkipSanit,
                    fieldCanon,
                    fieldSanit,
                    fieldSkipCanon,
                    fieldSkipSanit);
            return dispatcher.walkUnknown(value, childCtx, location, fieldPath, parentOwnerType);
        }
        return value;
    }

    // --- Chain composition (mirrors DefaultInputObjectProcessor.build*Chain) ---

    /**
     * Composes the effective canonicalizer chain for one value of {@code ownerType}.
     *
     * <p>{@code ownerType} and {@code fieldName} are the provenance keys for the two declaration
     * sites being offered: {@code ownerType} alone for {@code objectCanon} — the DTO whose
     * {@code @Canonicalize} produced it, which for every generated arm is the processor's own target
     * type — and the pair for {@code fieldCanon}, the property on that DTO. See
     * {@link InputTraversalContext#compose} for what they govern.
     *
     * @param ctx             the accumulated traversal context
     * @param ownerType       the type declaring {@code objectCanon} and owning {@code fieldName}
     * @param fieldName       the property declaring {@code fieldCanon}, or {@code null} when the
     *                        caller has no name to key on
     * @param objectCanon     object-level canonicalizer chain on the owner type
     * @param objectSkipCanon whether the owner type declares {@code @SkipCanonicalization}
     * @param fieldCanon      field-level canonicalizer chain
     * @param fieldSkipCanon  whether the field declares {@code @SkipCanonicalization}
     * @return the effective ordered chain, or an empty list when canonicalization is skipped
     */
    private static List<Class<? extends Canonicalizer>> effectiveCanonChain(
            InputTraversalContext ctx,
            Class<?> ownerType,
            @Nullable String fieldName,
            List<Class<? extends Canonicalizer>> objectCanon,
            boolean objectSkipCanon,
            List<Class<? extends Canonicalizer>> fieldCanon,
            boolean fieldSkipCanon) {

        if (ctx.inheritedSkipCanonicalization() || fieldSkipCanon) {
            return List.of();
        }
        if (objectSkipCanon && fieldCanon.isEmpty()) {
            return List.of();
        }
        return ctx.compose(ctx.inheritedCanonicalizerChain(), ownerType, fieldName, objectCanon, fieldCanon);
    }

    /**
     * Sanitization twin of {@link #effectiveCanonChain}.
     *
     * @param ctx             the accumulated traversal context
     * @param ownerType       the type declaring {@code objectSanit} and owning {@code fieldName}
     * @param fieldName       the property declaring {@code fieldSanit}, or {@code null} when the
     *                        caller has no name to key on
     * @param objectSanit     object-level sanitizer chain on the owner type
     * @param objectSkipSanit whether the owner type declares {@code @SkipSanitization}
     * @param fieldSanit      field-level sanitizer chain
     * @param fieldSkipSanit  whether the field declares {@code @SkipSanitization}
     * @return the effective ordered chain, or an empty list when sanitization is skipped
     */
    private static List<Class<? extends Sanitizer>> effectiveSanitChain(
            InputTraversalContext ctx,
            Class<?> ownerType,
            @Nullable String fieldName,
            List<Class<? extends Sanitizer>> objectSanit,
            boolean objectSkipSanit,
            List<Class<? extends Sanitizer>> fieldSanit,
            boolean fieldSkipSanit) {

        if (ctx.inheritedSkipSanitization() || fieldSkipSanit) {
            return List.of();
        }
        if (objectSkipSanit && fieldSanit.isEmpty()) {
            return List.of();
        }
        return ctx.compose(ctx.inheritedSanitizerChain(), ownerType, fieldName, objectSanit, fieldSanit);
    }
}
