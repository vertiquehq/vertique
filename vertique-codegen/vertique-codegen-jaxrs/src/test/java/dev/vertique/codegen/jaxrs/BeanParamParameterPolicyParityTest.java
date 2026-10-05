// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.jaxrs;

import dev.vertique.codegen.jaxrs.stubs.PolicyLiteralAssertions;
import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Codegen parity for issue #533: a {@code @Sanitize}/{@code @Canonicalize} on the
 * {@code @BeanParam} parameter itself must reach the generated plan as {@code POL{i}} and be
 * passed to {@code materializeBean}, not discarded in favour of a bare route baseline.
 */
class BeanParamParameterPolicyParityTest {

    @Test
    @DisplayName("@BeanParam @Sanitize(B) emits POL0 with [B] and passes POL0 to materializeBean")
    void beanParamParameterSanitize_emitsPolConstantAndUsesIt() {
        var result = ProcessorTestHarness.run(
                new JaxRsPipelineProcessor(),
                SourceFiles.inline("dev.vertique.test.bpp.PlainBean", """
                        package dev.vertique.test.bpp;

                        import jakarta.ws.rs.QueryParam;

                        public class PlainBean {
                            @QueryParam("page")
                            public String page;
                        }
                        """),
                SourceFiles.inline("dev.vertique.test.bpp.BeanParamSanitizedResource", """
                        package dev.vertique.test.bpp;

                        import dev.vertique.core.sanitization.Sanitize;
                        import dev.vertique.input.processing.testkit.B;
                        import jakarta.annotation.security.PermitAll;
                        import jakarta.ws.rs.BeanParam;
                        import jakarta.ws.rs.GET;
                        import jakarta.ws.rs.Path;

                        @Path("/bpp")
                        @PermitAll
                        public class BeanParamSanitizedResource {
                            public BeanParamSanitizedResource() {}

                            @GET
                            public String search(@BeanParam @Sanitize(B.class) PlainBean bean) {
                                return bean.page;
                            }
                        }
                        """));

        result.assertSuccess();
        String planFqn = "dev.vertique.test.bpp.BeanParamSanitizedResource_search_0_ExecutionPlan";
        result.assertGeneratedSourceContains(
                planFqn, PolicyLiteralAssertions.effectiveInputPolicies(List.of(), List.of("B")));
        result.assertGeneratedSourceContains(planFqn, "materializeBean(");
        result.assertGeneratedSourceContains(planFqn, "POL0,");
        result.assertGeneratedSourceDoesNotContain(planFqn, "ROUTE_POL");
    }
}
