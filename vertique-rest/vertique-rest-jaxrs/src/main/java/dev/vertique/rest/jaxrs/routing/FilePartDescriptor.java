// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.routing;

import jakarta.annotation.Nullable;
import java.util.List;
import java.util.Locale;

/**
 * Immutable public validation-strategy view of one file-bearing resource parameter, not the
 * physical uploaded file. A {@code null} {@link #partName()} marks an aggregate. A descriptor is
 * built for every file-typed parameter, including unconstrained {@code EntityPart} parameters;
 * constraints are populated only from {@code @FilePart} on {@code FileUpload}-typed parameters.
 *
 * <p><b>Valid by construction:</b> {@link JaxRsOperationDescriptor} is publicly implementable, so
 * this constructor enforces the same media-type grammar as annotation-driven descriptors and
 * prevents custom implementations from injecting definitions the annotation registrar never saw.
 *
 * @param partName     the case-sensitive part name, or {@code null} for an aggregate
 * @param allowedTypes lowercase-canonical allowed declared media types; may be empty
 * @param maxSizeBytes maximum part size in bytes, or {@code -1} when uncapped
 */
public record FilePartDescriptor(@Nullable String partName, List<String> allowedTypes, long maxSizeBytes) {

    /** Enforces the descriptor grammar and defensively copies mutable input. */
    public FilePartDescriptor {
        if (partName != null && partName.isBlank()) {
            throw new IllegalArgumentException("partName must be null (aggregate) or non-blank");
        }
        allowedTypes = List.copyOf(allowedTypes).stream()
                .map(FilePartDescriptor::canonicalizeMediaTypeToken)
                .toList();
        if (maxSizeBytes == 0 || maxSizeBytes < -1) {
            throw new IllegalArgumentException("maxSizeBytes must be -1 or > 0");
        }
    }

    /**
     * Reports whether this descriptor carries at least one size or media-type constraint.
     *
     * @return {@code true} when the descriptor is constrained
     */
    public boolean constrained() {
        return !allowedTypes.isEmpty() || maxSizeBytes >= 0;
    }

    /**
     * Validates and lowercase-canonicalizes one exact RFC 7230 media-type token entry.
     *
     * @param entry the declared allowed type
     * @return the lowercase-canonical entry
     * @throws IllegalArgumentException when the entry breaches the frozen grammar
     */
    private static String canonicalizeMediaTypeToken(String entry) {
        int slash = entry.indexOf('/');
        if (slash <= 0 || slash != entry.lastIndexOf('/') || slash == entry.length() - 1) {
            throw invalidAllowedType(entry);
        }

        String type = entry.substring(0, slash);
        String subtype = entry.substring(slash + 1);
        if (!isToken(type)
                || !isToken(subtype)
                || type.indexOf('*') >= 0
                || (subtype.indexOf('*') >= 0 && !subtype.equals("*"))) {
            throw invalidAllowedType(entry);
        }
        return entry.toLowerCase(Locale.ROOT);
    }

    private static boolean isToken(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!(ch >= 'a' && ch <= 'z')
                    && !(ch >= 'A' && ch <= 'Z')
                    && !(ch >= '0' && ch <= '9')
                    && "!#$%&'*+-.^_`|~".indexOf(ch) < 0) {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static IllegalArgumentException invalidAllowedType(String entry) {
        return new IllegalArgumentException("Invalid allowed media type: " + entry);
    }
}
