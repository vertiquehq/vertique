// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.aop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dev.vertique.aop.AspectProvider;
import dev.vertique.core.codegen.ReflectiveMethodMetadata;
import dev.vertique.ratelimit.RateLimiter;
import dev.vertique.ratelimit.RateLimiters;
import dev.vertique.ratelimit.spi.RateLimitAdapterSupport;
import jakarta.inject.Singleton;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wiring test for {@link RateLimitAopModule}: the module binds {@code AspectProvider<RateLimited>}
 * to {@link RateLimitedAspect}, constructed through the already-bound {@link RateLimiters}.
 */
class RateLimitAopModuleTest {

    private static final String POLICY = "wiring-policy";

    @Test
    @DisplayName("binds AspectProvider<RateLimited> to the RateLimitedAspect singleton")
    void bindsAspectProviderForRateLimited() {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        RateLimiters rateLimiters = mock(RateLimiters.class);
        when(rateLimiters.adapterSupport()).thenReturn(adapterSupport);

        TestComponent component = DaggerRateLimitAopModuleTest_TestComponent.builder()
                .testRuntimeModule(new TestRuntimeModule(rateLimiters))
                .build();

        AspectProvider<RateLimited> provider = component.rateLimitedAspect();

        assertThat(provider).isInstanceOf(RateLimitedAspect.class);
        assertThat(component.rateLimitedAspect()).isSameAs(provider);
    }

    @Test
    @DisplayName("bound aspect resolves the policy through the RateLimiters adapter support")
    void boundAspectDelegatesToRateLimitersAdapterSupport() throws NoSuchMethodException {
        RateLimitAdapterSupport adapterSupport = mock(RateLimitAdapterSupport.class);
        RateLimiters rateLimiters = mock(RateLimiters.class);
        when(rateLimiters.adapterSupport()).thenReturn(adapterSupport);
        when(adapterSupport.limiter(POLICY)).thenReturn(mock(RateLimiter.class));

        TestComponent component = DaggerRateLimitAopModuleTest_TestComponent.builder()
                .testRuntimeModule(new TestRuntimeModule(rateLimiters))
                .build();

        var method = Target.class.getDeclaredMethod("guarded");
        component
                .rateLimitedAspect()
                .interceptor(new ReflectiveMethodMetadata(method, List.of()), method.getAnnotation(RateLimited.class));

        verify(adapterSupport).limiter(POLICY);
    }

    @Singleton
    @Component(modules = {RateLimitAopModule.class, TestRuntimeModule.class})
    interface TestComponent {

        AspectProvider<RateLimited> rateLimitedAspect();
    }

    /** Supplies the {@link RateLimiters} runtime that {@code RateLimitCoreModule} provides in applications. */
    @Module
    static final class TestRuntimeModule {

        private final RateLimiters rateLimiters;

        TestRuntimeModule(RateLimiters rateLimiters) {
            this.rateLimiters = rateLimiters;
        }

        @Provides
        @Singleton
        RateLimiters rateLimiters() {
            return rateLimiters;
        }
    }

    /** Fixture carrying a {@link RateLimited} method. */
    static final class Target {
        @RateLimited(policy = POLICY)
        String guarded() {
            return "unused";
        }
    }
}
