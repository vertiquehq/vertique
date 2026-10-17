// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the public and protected surface of this module against removal and in-place change.
 *
 * <p>The recorded listing {@code public-signatures.txt} holds one normalized generic signature per
 * line. The test fails when any recorded line is absent from the listing built from the module's
 * compiled classes; additions are allowed. Run with {@code -Dvertique.signatures.record=true} to
 * rewrite the recorded listing from the current classes instead of comparing.
 */
class PublicSignatureFixtureTest {

    private static final String RECORD_PROPERTY = "vertique.signatures.record";

    @Test
    @DisplayName("every recorded public signature is still present")
    void recordedPublicSignaturesArePresent() {
        SignatureListing.Result current = SignatureListing.scan();

        if (Boolean.getBoolean(RECORD_PROPERTY)) {
            assertThat(current.lines()).as("current listing").isNotEmpty();
            SignatureListing.record(current.lines());
            return;
        }

        String content = SignatureListing.readRecorded();
        List<String> recorded = SignatureListing.lines(content);

        assertThat(recorded).as("recorded listing").isNotEmpty();
        assertThat(content).as("recorded listing line endings").doesNotContain("\r");
        assertThat(recorded).as("recorded listing blank lines").doesNotContain("");
        assertThat(recorded).as("recorded listing is sorted").isSorted();
        assertThat(recorded).as("recorded listing has no duplicates").doesNotHaveDuplicates();
        assertThat(generatedMentions(recorded, current.generatedClassNames()))
                .as("recorded lines naming generated classes")
                .isEmpty();

        Set<String> present = new HashSet<>(current.lines());
        List<String> missing = new ArrayList<>();
        for (String line : recorded) {
            if (!present.contains(line)) {
                missing.add(line);
            }
        }
        assertThat(missing)
                .as(
                        "%d recorded signature(s) are absent from the current listing; "
                                + "removals and in-place changes are not allowed",
                        missing.size())
                .isEmpty();
    }

    private static List<String> generatedMentions(List<String> recorded, Set<String> generated) {
        List<String> offending = new ArrayList<>();
        for (String name : generated) {
            Pattern mention = Pattern.compile("(?<![\\p{L}\\p{N}_$.])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_$])");
            for (String line : recorded) {
                if (mention.matcher(line).find()) {
                    offending.add(line);
                }
            }
        }
        return offending;
    }
}
