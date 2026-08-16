// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.json;

/**
 * A self-contained JSON Schema fragment carried by a JSON mapper profile.
 *
 * <p>Skeleton: behavior is added in the green step of this slice.
 */
public final class JsonSchemaFragment {

    private final String canonicalJson;

    private JsonSchemaFragment(String canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    /**
     * Parses a schema fragment.
     *
     * @param schemaJson the fragment JSON
     * @return the parsed fragment
     */
    public static JsonSchemaFragment parse(String schemaJson) {
        return new JsonSchemaFragment(schemaJson);
    }

    /**
     * Returns the canonical compact JSON of this fragment.
     *
     * @return the canonical JSON
     */
    public String canonicalJson() {
        return canonicalJson;
    }
}
