// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.test;

import dagger.Module;
import dagger.Provides;
import dev.vertique.rest.core.security.SecurityPolicyValidator;
import jakarta.annotation.Nullable;

/**
 * Supplies the {@code null} {@link SecurityPolicyValidator} stand-in an <b>unsecured</b> fixture graph
 * needs, as a module a consumer includes <em>explicitly</em> alongside {@link RestTestFixtureModule}.
 *
 * <p>{@code JaxRsRouterMount.Factory} consumes {@link SecurityPolicyValidator} as {@code @Nullable},
 * and neither {@code RestModule} nor {@code RestCoreModule} binds it, so every graph must supply
 * something. A test graph with no security wiring supplies {@code null} here — the same stand-in an
 * unauthenticated application declares in its own {@code AppModule}. Startup policy validation is then
 * simply skipped.
 *
 * <h2>Why this is a separate module</h2>
 *
 * <p>The binding below uses the <b>unqualified</b> {@code SecurityPolicyValidator} Dagger key
 * ({@link Nullable} is a documentation annotation, not a {@code @Qualifier}), and
 * {@code AuthModule} in {@code vertique-rest-security} declares a {@code @Provides} for that
 * <em>same</em> key. A component including both therefore fails annotation processing with a duplicate
 * binding — <b>and that is precisely the point of keeping this separate</b>. Folding the stand-in into
 * {@link RestTestFixtureModule} would make the fixture module itself incompatible with real security
 * wiring; splitting it out lets a consumer pick exactly one supplier of the key:
 *
 * <ul>
 *   <li><b>No security</b> — include {@code RestTestFixtureModule} <em>and</em> this module. Policy
 *       validation is skipped.
 *   <li><b>Real security</b> — include {@code RestTestFixtureModule} and {@code AuthModule}, and
 *       <em>not</em> this module. The graph then gets {@code DefaultSecurityPolicyValidator}, so the
 *       fixture mount performs the framework's real startup policy checks — including the
 *       {@code @PermitAll}-with-a-declared-{@code @SecurityRequirement} conflict check, which a
 *       {@code null} validator silently skips.
 * </ul>
 *
 * <p>Including this module <em>together with</em> {@code AuthModule} is a compile-time error, not a
 * runtime surprise: the consumer is told at annotation-processing time that two suppliers claim the
 * same key, and deletes whichever one it did not mean to have.
 *
 * @see RestTestFixtureModule
 */
@Module
public abstract class RestTestNoSecurityModule {

    /**
     * Provides a {@code null} {@link SecurityPolicyValidator}, which the mount factory accepts as its
     * {@code @Nullable} collaborator and reads as "skip startup policy validation".
     *
     * @return always {@code null}
     */
    @Provides
    @Nullable
    static SecurityPolicyValidator securityPolicyValidator() {
        return null;
    }
}
