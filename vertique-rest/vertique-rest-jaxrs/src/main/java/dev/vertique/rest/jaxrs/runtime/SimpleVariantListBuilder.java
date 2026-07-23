// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs.runtime;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Variant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Package-private {@link Variant.VariantListBuilder} implementation.
 *
 * <p>Accumulates media types, languages, and encodings for the current dimension set. On
 * {@link #add()}, computes the Cartesian product of all current dimensions, appends the resulting
 * variants to an accumulator list, and resets the current dimensions for the next batch.
 * {@link #build()} implicitly calls {@code add()} if any dimensions are pending.
 *
 * @see SimpleRuntimeDelegate
 */
class SimpleVariantListBuilder extends Variant.VariantListBuilder {

    private final List<Variant> variants = new ArrayList<>();
    private final List<MediaType> mediaTypes = new ArrayList<>();
    private final List<Locale> languages = new ArrayList<>();
    private final List<String> encodings = new ArrayList<>();

    @Override
    public Variant.VariantListBuilder mediaTypes(MediaType... mediaTypes) {
        this.mediaTypes.clear();
        if (mediaTypes != null) {
            this.mediaTypes.addAll(Arrays.asList(mediaTypes));
        }
        return this;
    }

    @Override
    public Variant.VariantListBuilder languages(Locale... languages) {
        this.languages.clear();
        if (languages != null) {
            this.languages.addAll(Arrays.asList(languages));
        }
        return this;
    }

    @Override
    public Variant.VariantListBuilder encodings(String... encodings) {
        this.encodings.clear();
        if (encodings != null) {
            this.encodings.addAll(Arrays.asList(encodings));
        }
        return this;
    }

    @Override
    public Variant.VariantListBuilder add() {
        if (mediaTypes.isEmpty() && languages.isEmpty() && encodings.isEmpty()) {
            throw new IllegalStateException(
                    "At least one media type, language, or encoding must be set before calling add()");
        }

        // Cartesian product — empty dimensions treated as a single null entry
        List<MediaType> mts = mediaTypes.isEmpty() ? nullList() : mediaTypes;
        List<Locale> lngs = languages.isEmpty() ? nullList() : languages;
        List<String> encs = encodings.isEmpty() ? nullList() : encodings;

        for (MediaType mt : mts) {
            for (Locale lng : lngs) {
                for (String enc : encs) {
                    variants.add(new Variant(mt, lng, enc));
                }
            }
        }

        mediaTypes.clear();
        languages.clear();
        encodings.clear();
        return this;
    }

    @Override
    public List<Variant> build() {
        if (!mediaTypes.isEmpty() || !languages.isEmpty() || !encodings.isEmpty()) {
            add();
        }
        List<Variant> result = new ArrayList<>(variants);
        variants.clear();
        return result;
    }

    /** Single-element list containing {@code null}, used for empty Cartesian dimensions. */
    @SuppressWarnings("unchecked")
    private static final List<?> NULL_LIST = Collections.singletonList(null);

    @SuppressWarnings("unchecked")
    private static <T> List<T> nullList() {
        return (List<T>) NULL_LIST;
    }
}
