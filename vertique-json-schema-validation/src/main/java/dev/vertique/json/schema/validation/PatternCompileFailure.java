// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.json.schema.validation;

/**
 * One regular expression a schema declares that the regex engine cannot compile, located by JSON pointer.
 *
 * <p>The record never carries the pattern text: the pattern is untrusted schema content, and the engine's own
 * exception message quotes it in full. Only the engine's description of the defect and the index it reports are
 * kept, so the value is safe to log or to place in a diagnostic.
 *
 * <p>INTERNAL framework seam: this type is shared by framework modules and is not an application contract.
 *
 * @param pointer the JSON pointer of the position that declares the pattern. A failing {@code patternProperties}
 *     key is reported at the pointer of the {@code patternProperties} object itself, and a position beneath such a
 *     key is reported with the key's zero-based document-order ordinal in brackets, for example
 *     {@code /properties/m/patternProperties/[key-0]/pattern}, so no key text appears in a pointer
 * @param description the regex engine's description of the defect, without the pattern
 * @param index the index within the pattern at which the engine reports the defect, or {@code -1} when the engine
 *     reports none
 */
public record PatternCompileFailure(String pointer, String description, int index) {}
