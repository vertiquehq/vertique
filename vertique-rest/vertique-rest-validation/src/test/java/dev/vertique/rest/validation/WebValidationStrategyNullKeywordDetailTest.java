// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.core.ValidationErrorDetail;
import io.vertx.json.schema.OutputUnit;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * W4 (spike/deserializer-driven-schema round 4 ruling): {@code WebValidationStrategy.GateHandler
 * #collectFailures}'s multi-error loop skips an error only when {@code extractKeywordFor} yields
 * {@code null} <em>and</em> {@link SchemaErrorKeywords#isStructural} (via {@code STRUCTURAL_KEYWORDS})
 * says the keyword location is structural. When the keyword location is absent (or empty) rather than
 * structural, {@code extractKeywordFor} still yields {@code null}, but the error is not skipped: it
 * falls through to {@code safeDetail(null, {}, error.getError())}, whose first branch requires a
 * non-null keyword and so never applies, landing on the fallback
 * {@code rawMessage != null ? rawMessage : ...} — returning vertx-json-schema's own raw message
 * unfiltered. That message is the exact class of leak FR-018/#598 removed for every other keyword: it
 * may echo the submitted request value (the class Javadoc's own example, {@code "500 is greater than
 * 100"}).
 *
 * <p>Every real error this project has observed from vertx-json-schema 5.1.6's Basic output format
 * carries a non-empty keyword location, so this null-keyword-but-non-structural shape is not
 * straightforwardly reproducible end-to-end through a real schema and a real HTTP round trip within this
 * proof's time budget. Per the round's own instruction, this is a synthetic unit proof instead: a
 * hand-built {@link OutputUnit} whose keyword location is {@code null} (so {@code extractKeywordFor}
 * yields {@code null}) and whose instance location does not collide with any structural keyword, fed
 * directly to {@code collectFailures} through reflection (the method is {@code private static} on the
 * package-private nested {@code GateHandler} class, with no other test seam reaching it).
 *
 * <p>The owner ruling records the fix direction (the null-keyword, non-structural branch must still
 * produce a value-free detail) but this class only authors the proof, never the production change.
 */
class WebValidationStrategyNullKeywordDetailTest {

    private static final String CLIENT_VALUE = "999-a-client-submitted-value-that-must-never-echo";

    @Test
    @DisplayName("W4: an error with an absent (non-structural) keyword location must produce a value-free"
            + " detail, never vertx-json-schema's own raw message")
    void nullKeywordNonStructuralErrorMustProduceAValueFreeDetail() throws Exception {
        OutputUnit leaf = new OutputUnit()
                .setValid(false)
                .setKeywordLocation(null) // extractKeywordFor(...) yields null: no keyword resolved
                .setInstanceLocation("#/amount") // "amount" is not a structural keyword either
                .setError("the value " + CLIENT_VALUE + " does not satisfy an unresolvable constraint");
        OutputUnit result = new OutputUnit().setValid(false).setErrors(List.of(leaf));

        List<ValidationErrorDetail> failures = new ArrayList<>();
        invokeCollectFailures(result, "body", null, null, failures, false);

        assertEquals(1, failures.size(), "exactly one detail must be produced for the one reported error");
        ValidationErrorDetail detail = failures.get(0);

        assertFalse(
                detail.detail().contains(CLIENT_VALUE),
                "W4 DECISIVE: the detail must never echo vertx-json-schema's raw message, which may itself"
                        + " echo the client-submitted value; detail: " + detail.detail());
        assertNull(
                detail.type(),
                "no keyword was resolved for this synthetic error, so the detail must carry none either");
    }

    /**
     * Invokes {@code WebValidationStrategy$GateHandler#collectFailures} through reflection: it is
     * {@code private static} on a package-private nested class, with no public or package-private test
     * seam that reaches it directly.
     */
    private static void invokeCollectFailures(
            OutputUnit result,
            String location,
            String fallbackPath,
            io.vertx.core.json.JsonObject schema,
            List<ValidationErrorDetail> failures,
            boolean failFast)
            throws Exception {
        Class<?> gateHandlerClass = Class.forName("dev.vertique.rest.validation.WebValidationStrategy$GateHandler");
        Method collectFailures = gateHandlerClass.getDeclaredMethod(
                "collectFailures",
                OutputUnit.class,
                String.class,
                String.class,
                io.vertx.core.json.JsonObject.class,
                List.class,
                boolean.class);
        collectFailures.setAccessible(true);
        collectFailures.invoke(null, result, location, fallbackPath, schema, failures, failFast);
    }
}
