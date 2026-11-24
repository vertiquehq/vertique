// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package jakarta.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Test-only stand-in for Jakarta {@code @Nullable}, which this build-time-only module does not have
 * on its classpath. {@link dev.vertique.openapi.RequestParamsExtension} matches the annotation by
 * fully-qualified class name, so this stub sits at that exact name.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
public @interface Nullable {}
