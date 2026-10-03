// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.openapi.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Test-source stand-in for the real {@code dev.vertique.rest.openapi.docs.ApiDocs} annotation,
 * built at the docs module's own fully qualified name so it stands in for "the docs artifact on
 * the classpath, its module not in the component" (FR-037, AR3-009): {@code
 * ApiDocsModuleInstalled.ANNOTATION_NAME} must equal {@link Class#getName()} of this type. rest-jaxrs
 * does not depend on {@code vertique-rest-openapi-docs}; this test-only class fills that gap so
 * FR-037's classpath-presence check has something real to reflect on.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface ApiDocs {}
