// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.rest.jaxrs.application.ApiDocsInstalled;
import dev.vertique.rest.openapi.docs.fixture.PublicApi;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the cross-module contract that lets rest-jaxrs recognize {@link ApiDocs} without depending on
 * this module: rest-jaxrs's not-installed notice and the annotation processor's compile checks look
 * the annotation up by the name {@link ApiDocsInstalled#ANNOTATION_NAME}, so a rename or move of
 * {@link ApiDocs}, or a retention that hides it from reflection, would silently stop them.
 */
class ApiDocsNameContractTest {

    /** The fully qualified name both modules agree on, written out by hand. */
    private static final String EXPECTED_ANNOTATION_NAME = "dev.vertique.rest.openapi.docs.ApiDocs";

    @Test
    @DisplayName("ApiDocs keeps the name rest-jaxrs looks it up by and stays visible to reflection on a type")
    void annotationNameEqualsTheRestJaxrsConstant() {
        // Given: the annotation and the constant rest-jaxrs recognizes it by
        String annotationName = ApiDocs.class.getName();
        Retention retention = ApiDocs.class.getAnnotation(Retention.class);
        Target target = ApiDocs.class.getAnnotation(Target.class);

        // When: reflection reads the annotation from a documented declaring interface
        ApiDocs onDeclaringInterface = PublicApi.class.getAnnotation(ApiDocs.class);

        // Then: the name matches the cross-module constant
        assertAll(
                "rest-jaxrs recognizes @ApiDocs by the name ApiDocsInstalled.ANNOTATION_NAME",
                () -> assertEquals(
                        ApiDocsInstalled.ANNOTATION_NAME,
                        annotationName,
                        "ApiDocs's name must equal rest-jaxrs's ApiDocsInstalled.ANNOTATION_NAME"),
                () -> assertEquals(
                        EXPECTED_ANNOTATION_NAME,
                        annotationName,
                        "ApiDocs must stay at the name rest-jaxrs and the annotation processor look up"));

        // Then: it is runtime-retained, targets types only, and is found on the declaring interface
        assertAll(
                "rest-jaxrs finds @ApiDocs by reflection on a declaring interface",
                () -> assertNotNull(retention, "ApiDocs declares its retention"),
                () -> assertEquals(
                        RetentionPolicy.RUNTIME,
                        retention.value(),
                        "ApiDocs must be runtime-retained for rest-jaxrs's reflective lookup"),
                () -> assertNotNull(target, "ApiDocs declares its target"),
                () -> assertArrayEquals(
                        new ElementType[] {ElementType.TYPE},
                        target.value(),
                        "ApiDocs must target types only, the declaring interfaces rest-jaxrs reads"),
                () -> assertNotNull(
                        onDeclaringInterface,
                        "reflection on a declaring interface must find ApiDocs for rest-jaxrs's lookup"));
    }
}
