// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.definition.expression.cel;

import dev.cel.bundle.Cel;
import dev.cel.bundle.CelFactory;
import dev.cel.common.CelAbstractSyntaxTree;
import dev.cel.common.CelOptions;
import dev.cel.common.CelValidationException;
import dev.cel.common.CelVarDecl;
import dev.cel.common.exceptions.CelIterationLimitExceededException;
import dev.cel.common.types.MapType;
import dev.cel.common.types.SimpleType;
import dev.cel.parser.CelStandardMacro;
import dev.cel.parser.CelUnparserFactory;
import dev.cel.runtime.CelEvaluationException;
import dev.cel.runtime.CelRuntime;
import dev.cel.validator.CelValidatorFactory;
import dev.cel.validator.validators.AstDepthLimitValidator;
import dev.vertique.workflow.definition.expression.CompiledExpression;
import dev.vertique.workflow.definition.expression.ExpressionEnv;
import dev.vertique.workflow.definition.expression.ExpressionEvaluationException;
import dev.vertique.workflow.definition.expression.ExpressionFingerprint;
import dev.vertique.workflow.definition.expression.ExpressionParseException;
import dev.vertique.workflow.definition.expression.ExpressionProfile;
import dev.vertique.workflow.definition.expression.ExpressionType;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CEL (Common Expression Language) implementation of {@link ExpressionProfile}.
 *
 * <p><strong>Isolation contract:</strong> This is the ONLY class in the
 * {@code vertique-workflow-definition} module that imports {@code dev.cel.*} types. All other
 * classes program to the {@link ExpressionProfile} interface and the
 * {@link CompiledExpression} / {@link ExpressionEnv} value types.
 *
 * <p>A shared base {@link Cel} instance is built once at construction with hard safety bounds:
 * <ul>
 *   <li>Source length limit: {@value #MAX_EXPRESSION_CODE_POINT_SIZE} code points</li>
 *   <li>AST depth limit: {@value #MAX_AST_DEPTH} levels (enforced by
 *       {@link AstDepthLimitValidator})</li>
 *   <li>Comprehension iteration limit: {@value #MAX_COMPREHENSION_ITERATIONS} iterations</li>
 * </ul>
 *
 * <p>Standard macros ({@code has}, {@code all}, {@code exists}, {@code exists_one}) are enabled
 * because they are all safe predicate macros useful for routing expressions. No custom functions or
 * ambient state accessors are registered.
 *
 * <p>The {@link CelAbstractSyntaxTree} produced at compile time is stored as the opaque
 * {@link CompiledExpression#handle()} — callers MUST NOT downcast it.
 *
 * <p>The runtime program is created fresh per {@link #evaluate} call from the pre-compiled AST;
 * the program itself is lightweight and not cached since it is bound to the calling-thread execution.
 */
@Singleton
public class CelExpressionProfile implements ExpressionProfile {

    // --- Bounds constants ---

    /** Maximum allowed expression source length in Unicode code points. */
    static final int MAX_EXPRESSION_CODE_POINT_SIZE = 2000;

    /** Maximum allowed AST nesting depth, enforced post-check by {@link AstDepthLimitValidator}. */
    static final int MAX_AST_DEPTH = 30;

    /** Maximum number of iterations allowed in any comprehension (all, exists, etc.). */
    static final int MAX_COMPREHENSION_ITERATIONS = 1000;

    // --- Instance fields ---

    /** The base CEL instance shared across all compile and evaluate calls. */
    private final Cel cel;

    /** The unparser used to produce canonical expression strings from ASTs. */
    private final dev.cel.parser.CelUnparser unparser;

    /** The AST-depth validator applied after type-checking. */
    private final AstDepthLimitValidator depthValidator;

    /**
     * Constructs the CEL expression profile and initialises the shared CEL instance.
     *
     * <p>The base {@link Cel} is built with standard macros ({@code has}, {@code all},
     * {@code exists}, {@code exists_one}), size and iteration bounds, and no custom
     * function registrations. The {@link AstDepthLimitValidator} is also initialised here.
     *
     * <p>State→tree conversion for {@code evaluateBoolean} is the caller's responsibility (see
     * {@code DecisionRouteCompiler}), so this profile does not need a Jackson mapper.
     */
    @Inject
    public CelExpressionProfile() {
        CelOptions options = CelOptions.current()
                .maxExpressionCodePointSize(MAX_EXPRESSION_CODE_POINT_SIZE)
                .comprehensionMaxIterations(MAX_COMPREHENSION_ITERATIONS)
                .build();

        this.cel = CelFactory.standardCelBuilder()
                .setOptions(options)
                .setStandardMacros(
                        CelStandardMacro.HAS,
                        CelStandardMacro.ALL,
                        CelStandardMacro.EXISTS,
                        CelStandardMacro.EXISTS_ONE)
                .build();

        this.unparser = CelUnparserFactory.newUnparser();
        this.depthValidator = AstDepthLimitValidator.newInstance(MAX_AST_DEPTH);
    }

    // --- ExpressionProfile implementation ---

    /**
     * {@inheritDoc}
     *
     * <p>Implementation steps:
     * <ol>
     *   <li>Build a per-call {@link Cel} instance derived from the base, adding the {@code state}
     *       variable ({@code map<string, dyn>}) and one {@code bool} variable per named-condition
     *       id declared in {@code env}.</li>
     *   <li>Compile (parse + type-check) the source string. {@link CelValidationException} is
     *       caught and rethrown as {@link ExpressionParseException}.</li>
     *   <li>Apply the {@link AstDepthLimitValidator} to the checked AST. A validation error
     *       produces an {@link ExpressionParseException}.</li>
     *   <li>Derive the {@link ExpressionType} from the AST's checked result type.</li>
     *   <li>Canonicalise via {@link dev.cel.parser.CelUnparser}; fall back to the original source
     *       if the unparser throws.</li>
     *   <li>Wrap the AST in a {@link CompiledExpression} and return it.</li>
     * </ol>
     *
     * @param source the raw expression text; non-null
     * @param env    the compile-time environment; non-null
     * @return a compiled expression; never {@code null}
     * @throws ExpressionParseException on any parse, type-check, or bound violation
     */
    @Override
    public CompiledExpression compile(String source, ExpressionEnv env) throws ExpressionParseException {
        Cel envCel = buildEnvCel(env);

        // Parse + type-check
        var validationResult = envCel.compile(source);
        CelAbstractSyntaxTree ast;
        try {
            ast = validationResult.getAst();
        } catch (CelValidationException e) {
            throw new ExpressionParseException("Expression compile failed: " + e.getMessage(), source, e);
        }

        // Depth validation
        var depthValidationResult = CelValidatorFactory.standardCelValidatorBuilder(envCel)
                .addAstValidators(depthValidator)
                .build()
                .validate(ast);
        if (depthValidationResult.hasError()) {
            throw new ExpressionParseException(
                    "Expression exceeds maximum AST depth (" + MAX_AST_DEPTH + "): "
                            + depthValidationResult.getErrorString(),
                    source);
        }

        // Determine result type
        ExpressionType resultType = mapCelType(ast);

        // Canonicalise via unparser
        String canonicalForm;
        try {
            canonicalForm = unparser.unparse(ast);
        } catch (Exception e) {
            canonicalForm = source;
        }

        return new CompiledExpression(canonicalForm, resultType, ast);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Delegates to {@link ExpressionFingerprint#of(CompiledExpression)}.
     *
     * @param expr the compiled expression; non-null
     * @return the SHA-256 hex fingerprint; never {@code null}
     */
    @Override
    public String fingerprint(CompiledExpression expr) {
        return ExpressionFingerprint.of(expr);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@code handle} stored in {@code expr} is cast to {@link CelAbstractSyntaxTree} and
     * used to create a runtime program. The program is evaluated against the supplied
     * {@code evalEnv} map.
     *
     * @param expr    the compiled expression to evaluate; non-null
     * @param evalEnv the evaluation environment map; must include {@code "state"} and one boolean
     *                entry per named-condition id
     * @return the raw evaluation result
     * @throws ExpressionEvaluationException on runtime evaluation failure
     */
    @Override
    public Object evaluate(CompiledExpression expr, Map<String, Object> evalEnv) {
        CelAbstractSyntaxTree ast = (CelAbstractSyntaxTree) expr.handle();
        CelRuntime.Program program;
        try {
            program = cel.createProgram(ast);
        } catch (CelEvaluationException e) {
            throw new ExpressionEvaluationException(
                    "Failed to create program from compiled expression: " + e.getMessage(), e);
        }

        try {
            return program.eval(evalEnv);
        } catch (CelIterationLimitExceededException e) {
            throw new ExpressionEvaluationException(
                    "Expression evaluation exceeded comprehension iteration limit (" + MAX_COMPREHENSION_ITERATIONS
                            + ")",
                    e);
        } catch (CelEvaluationException e) {
            throw new ExpressionEvaluationException("Expression evaluation failed: " + e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param expr    the compiled expression; MUST have {@code resultType() == BOOLEAN}
     * @param evalEnv the evaluation environment map; non-null
     * @return the boolean result
     * @throws IllegalStateException         if {@code expr.resultType() != BOOLEAN}
     * @throws ExpressionEvaluationException on runtime evaluation failure
     */
    @Override
    public boolean evaluateBoolean(CompiledExpression expr, Map<String, Object> evalEnv) {
        if (expr.resultType() != ExpressionType.BOOLEAN) {
            throw new IllegalStateException("expression result type is " + expr.resultType() + ", expected BOOLEAN");
        }
        Object result = evaluate(expr, evalEnv);
        if (result instanceof Boolean b) {
            return b;
        }
        return false;
    }

    // --- Private helpers ---

    /**
     * Builds a per-call {@link Cel} derived from the base, adding:
     * <ul>
     *   <li>{@code state : map<string, dyn>} — the workflow state accessor.</li>
     *   <li>One {@code bool} variable per named-condition id — declared so the type-checker
     *       accepts references to named conditions; runtime values are supplied by callers
     *       in the {@code evalEnv} map.</li>
     * </ul>
     *
     * @param env the compile-time environment; non-null
     * @return a CEL compiler configured with the env's variables
     */
    private Cel buildEnvCel(ExpressionEnv env) {
        List<CelVarDecl> vars = new ArrayList<>();

        // state: map<string, dyn>
        vars.add(CelVarDecl.newVarDeclaration("state", MapType.create(SimpleType.STRING, SimpleType.DYN)));

        // named conditions: each is a bool variable
        for (String conditionId : env.namedConditionIds()) {
            vars.add(CelVarDecl.newVarDeclaration(conditionId, SimpleType.BOOL));
        }

        return cel.toCelBuilder().addVarDeclarations(vars).build();
    }

    /**
     * Maps the CEL AST's checked result type to our {@link ExpressionType} enum.
     *
     * @param ast the type-checked AST; non-null
     * @return the corresponding {@link ExpressionType}; {@link ExpressionType#DYN} as catch-all
     */
    private static ExpressionType mapCelType(CelAbstractSyntaxTree ast) {
        var celType = ast.getResultType();
        return switch (celType.kind()) {
            case BOOL -> ExpressionType.BOOLEAN;
            case STRING -> ExpressionType.STRING;
            case INT, DOUBLE -> ExpressionType.NUMBER;
            default -> ExpressionType.DYN;
        };
    }
}
