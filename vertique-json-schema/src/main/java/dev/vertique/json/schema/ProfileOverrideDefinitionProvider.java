// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.CustomDefinition;
import com.github.victools.jsonschema.generator.CustomDefinitionProviderV2;
import com.github.victools.jsonschema.generator.SchemaGenerationContext;
import com.github.victools.jsonschema.generator.SchemaKeyword;

/**
 * The Victools general-type definition provider that applies a profile's declared schema-type
 * overrides.
 *
 * <p>It matches {@link ResolvedType#getErasedType()} by <strong>exact class</strong> against the
 * direction-filtered declarations captured in {@link ValidatedProfile} — no assignability — so it
 * fires wherever that class is resolved: the mapped root type, a nested property, or a collection
 * element such as the element of a resolved {@code List<BigDecimal>}.
 *
 * <p>The returned definition wraps the profile fragment in a single-branch {@code allOf} rather than
 * returning the fragment's keywords directly. That wrapper is what makes composition safe: keywords
 * a member contributes (Swagger schema metadata, applicable Jakarta constraints) land as siblings of
 * the {@code allOf} instead of colliding with — and silently replacing — the fragment's own keys. The
 * definition is registered as a {@code STANDARD} definition excluding type attributes, so the
 * fragment, not Victools' derived attributes, is the authoritative baseline for the class.
 *
 * <p>Victools' {@code ALLOF_CLEANUP_AT_THE_END} normalization is deliberately retained, so the final
 * document may legally flatten this wrapper wherever the merge is lossless. What is normative is the
 * conjunction, not the literal shape.
 *
 * <p>The provider is stateless apart from the immutable declarations it was built with, and it
 * builds a fresh node tree per call, so it never hands the same mutable tree to two generations.
 */
final class ProfileOverrideDefinitionProvider implements CustomDefinitionProviderV2 {

    /** The validated, direction-filtered override declarations. */
    private final ValidatedProfile profile;

    /**
     * Creates a provider over one direction's validated declarations.
     *
     * @param profile the validated, direction-filtered profile view
     */
    ProfileOverrideDefinitionProvider(ValidatedProfile profile) {
        this.profile = profile;
    }

    /**
     * Supplies the profile fragment as the authoritative definition for an overridden class.
     *
     * @param resolvedType the type Victools is about to define
     * @param context      the active generation context, supplying the node factory and keyword names
     * @return the wrapped fragment definition, or {@code null} to leave the type to Victools
     * @throws JsonSchemaGenerationException if a declared fragment cannot be re-read as JSON
     */
    @Override
    public CustomDefinition provideCustomSchemaDefinition(ResolvedType resolvedType, SchemaGenerationContext context) {
        if (resolvedType == null) {
            return null;
        }
        String canonicalFragment = profile.fragmentFor(resolvedType.getErasedType());
        if (canonicalFragment == null) {
            return null;
        }

        JsonNode fragment;
        try {
            fragment = NeutralJson.read(canonicalFragment);
        } catch (JsonProcessingException malformed) {
            // Unreachable in practice: JsonSchemaFragment only ever holds text it parsed itself.
            throw Diagnostics.failure(
                    "the declared schema override fragment for "
                            + Diagnostics.typeIdentity(resolvedType.getErasedType()) + " is not readable JSON",
                    malformed);
        }

        ArrayNode conjunction = context.getGeneratorConfig().createArrayNode();
        conjunction.add(fragment);
        ObjectNode definition = context.getGeneratorConfig().createObjectNode();
        definition.set(context.getKeyword(SchemaKeyword.TAG_ALLOF), conjunction);

        return new CustomDefinition(
                definition, CustomDefinition.DefinitionType.STANDARD, CustomDefinition.AttributeInclusion.NO);
    }
}
