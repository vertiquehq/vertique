// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.delayed.processor.emit;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import dev.vertique.codegen.CodegenContext;
import dev.vertique.codegen.delayed.processor.DelayedJobContractModel;
import dev.vertique.codegen.support.Identifiers;
import java.io.IOException;
import javax.annotation.processing.Generated;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;

/**
 * Emitter that generates {@code {Contract}_DelayedJobProxy} classes for {@code @DelayedJobContract}
 * interfaces.
 *
 * <p>Each generated proxy is {@code public final}, implements the contract interface, and reproduces
 * the behavior of the reflective {@link java.lang.reflect.InvocationHandler}-based
 * {@code DelayedJobClientProxy} with zero reflection:
 * <ul>
 *   <li>The constructor {@code (DelayedJobService, DelayedJobContract, JsonObject)} resolves the
 *       effective {@code maxAttempts}/{@code queue}/{@code priority} from the per-contract config with
 *       annotation fallback, exactly as the reflective proxy does.</li>
 *   <li>Each of the six {@code enqueue} overloads delegates to a shared private {@code doEnqueue}
 *       helper; the {@code SqlClient}-bearing overloads fail fast on a {@code null} transaction.</li>
 *   <li>{@code toString}/{@code equals}/{@code hashCode} mirror the reflective proxy's
 *       {@code Object}-method handling ({@code "DelayedJobClient[name]"} and identity semantics).</li>
 * </ul>
 *
 * <p>The class lands in the contract's own package (never the {@code -Avertique.codegen.package}
 * override) so {@code DelayedJobClientFactory} can resolve it by name; see ADR-0070/ADR-0071.
 */
public final class DelayedJobProxyEmitter {

    private static final String PROCESSOR_FQN = "dev.vertique.codegen.delayed.processor.DelayedJobContractProcessor";
    private static final String SUFFIX = "_DelayedJobProxy";

    private static final String PKG = "dev.vertique.job.delayed";
    private static final ClassName DELAYED_JOB_SERVICE = ClassName.get(PKG, "DelayedJobService");
    private static final ClassName DELAYED_JOB_CONTRACT = ClassName.get(PKG, "DelayedJobContract");
    private static final ClassName DELAYED_JOB = ClassName.get(PKG, "DelayedJob");
    private static final ClassName DELAYED_JOB_OPTIONS = ClassName.get(PKG, "DelayedJobOptions");
    private static final ClassName JSON_OBJECT = ClassName.get("io.vertx.core.json", "JsonObject");
    private static final ClassName FUTURE = ClassName.get("io.vertx.core", "Future");
    private static final ClassName SQL_CLIENT = ClassName.get("io.vertx.sqlclient", "SqlClient");
    private static final ClassName UUID = ClassName.get("java.util", "UUID");
    private static final ClassName INSTANT = ClassName.get("java.time", "Instant");
    private static final ClassName DURATION = ClassName.get("java.time", "Duration");
    private static final ClassName DURABLE_METADATA = ClassName.get("dev.vertique.core.context", "DurableMetadata");

    private static final ParameterizedTypeName FUTURE_UUID = ParameterizedTypeName.get(FUTURE, UUID);

    private final CodegenContext ctx;

    /**
     * Creates an emitter bound to the given codegen context.
     *
     * @param ctx the shared codegen context; must not be {@code null}
     */
    public DelayedJobProxyEmitter(CodegenContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Emits the {@code {Contract}_DelayedJobProxy} class for the given model.
     *
     * @param model the validated contract model; its {@code payloadType()} must be non-{@code null}
     */
    public void emit(DelayedJobContractModel model) {
        TypeElement contract = model.contractType();
        String packageName = ctx.packageNameOf(contract);
        String generatedSimpleName = Identifiers.generatedClassName(contract, SUFFIX);
        ClassName contractClassName = ClassName.get(contract);
        TypeName payloadType = TypeName.get(model.payloadType());

        TypeSpec typeSpec = TypeSpec.classBuilder(generatedSimpleName)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(contractClassName)
                .addAnnotation(AnnotationSpec.builder(Generated.class)
                        .addMember("value", "$S", PROCESSOR_FQN)
                        .build())
                .addJavadoc(
                        "Generated static delayed-job proxy for {@link $T}.\n\n"
                                + "<p>Zero-reflection replacement for the JDK dynamic proxy; selected at runtime by\n"
                                + "{@code DelayedJobClientFactory} when present.\n",
                        contractClassName)
                .addField(DELAYED_JOB_SERVICE, "jobService", Modifier.PRIVATE, Modifier.FINAL)
                .addField(ClassName.get(String.class), "handlerName", Modifier.PRIVATE, Modifier.FINAL)
                .addField(TypeName.INT, "effectiveMaxAttempts", Modifier.PRIVATE, Modifier.FINAL)
                .addField(ClassName.get(String.class), "effectiveQueue", Modifier.PRIVATE, Modifier.FINAL)
                .addField(TypeName.INT, "effectivePriority", Modifier.PRIVATE, Modifier.FINAL)
                .addMethod(buildConstructor())
                .addMethod(enqueue(payloadType)
                        .addStatement("return doEnqueue(payload, null, null, null)")
                        .build())
                .addMethod(enqueue(payloadType)
                        .addParameter(INSTANT, "runAt")
                        .addStatement("return doEnqueue(payload, runAt, null, null)")
                        .build())
                .addMethod(enqueue(payloadType)
                        .addParameter(DURATION, "delay")
                        .addStatement(
                                "return doEnqueue(payload, delay != null ? $T.now().plus(delay) : null, null, null)",
                                INSTANT)
                        .build())
                .addMethod(enqueue(payloadType)
                        .addParameter(SQL_CLIENT, "tx")
                        .addCode(nullTxGuard())
                        .addStatement("return doEnqueue(payload, null, tx, null)")
                        .build())
                .addMethod(enqueue(payloadType)
                        .addParameter(DELAYED_JOB_OPTIONS, "options")
                        .addStatement("return doEnqueue(payload, null, null, options)")
                        .build())
                .addMethod(enqueue(payloadType)
                        .addParameter(DELAYED_JOB_OPTIONS, "options")
                        .addParameter(SQL_CLIENT, "tx")
                        .addCode(nullTxGuard())
                        .addStatement("return doEnqueue(payload, null, tx, options)")
                        .build())
                .addMethod(buildDoEnqueue(payloadType))
                .addMethod(buildToString())
                .addMethod(buildEquals())
                .addMethod(buildHashCode())
                .build();

        JavaFile javaFile = JavaFile.builder(packageName, typeSpec).build();
        try {
            javaFile.writeTo(ctx.filer());
        } catch (IOException e) {
            ctx.diagnostics()
                    .error(
                            contract,
                            "Failed to write generated source file '%s.%s': %s",
                            packageName,
                            generatedSimpleName,
                            e.getMessage());
        }
    }

    private MethodSpec buildConstructor() {
        return MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC)
                .addParameter(DELAYED_JOB_SERVICE, "jobService")
                .addParameter(DELAYED_JOB_CONTRACT, "annotation")
                .addParameter(JSON_OBJECT, "contractConfig")
                .addJavadoc("Constructs the generated proxy, resolving effective config (config overrides "
                        + "annotation defaults).\n\n"
                        + "@param jobService the delayed-job service to enqueue through\n"
                        + "@param annotation the contract annotation supplying name and defaults\n"
                        + "@param contractConfig the per-contract config section overriding annotation defaults\n")
                .addStatement("this.jobService = jobService")
                .addStatement("this.handlerName = annotation.name()")
                .addStatement(
                        "this.effectiveMaxAttempts = contractConfig.getInteger($S, annotation.maxAttempts())",
                        "maxAttempts")
                .addStatement("this.effectiveQueue = contractConfig.getString($S, annotation.queue())", "queue")
                .addStatement(
                        "this.effectivePriority = contractConfig.getInteger($S, annotation.priority())", "priority")
                .build();
    }

    private static MethodSpec.Builder enqueue(TypeName payloadType) {
        return MethodSpec.methodBuilder("enqueue")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(FUTURE_UUID)
                .addParameter(payloadType, "payload");
    }

    private static CodeBlock nullTxGuard() {
        return CodeBlock.builder()
                .beginControlFlow("if (tx == null)")
                .addStatement(
                        "return $T.failedFuture(new $T($S))",
                        FUTURE,
                        NullPointerException.class,
                        "SqlClient argument must not be null on transactional enqueue overload")
                .endControlFlow()
                .build();
    }

    private MethodSpec buildDoEnqueue(TypeName payloadType) {
        return MethodSpec.methodBuilder("doEnqueue")
                .addModifiers(Modifier.PRIVATE)
                .returns(FUTURE_UUID)
                .addParameter(payloadType, "payload")
                .addParameter(INSTANT, "runAt")
                .addParameter(SQL_CLIENT, "tx")
                .addParameter(DELAYED_JOB_OPTIONS, "options")
                .addStatement("$T queue = effectiveQueue", String.class)
                .addStatement("int priority = effectivePriority")
                .addStatement("int maxAttempts = effectiveMaxAttempts")
                .addStatement("$T jobId = null", String.class)
                .addStatement("$T premergedMetadata = null", DURABLE_METADATA)
                .beginControlFlow("if (options != null)")
                .beginControlFlow("if (options.runAt() != null)")
                .addStatement("runAt = options.runAt()")
                .endControlFlow()
                .beginControlFlow("if (options.queue() != null)")
                .addStatement("queue = options.queue()")
                .endControlFlow()
                .beginControlFlow("if (options.priority() != null)")
                .addStatement("priority = options.priority()")
                .endControlFlow()
                .beginControlFlow("if (options.maxAttempts() != null)")
                .addStatement("maxAttempts = options.maxAttempts()")
                .endControlFlow()
                .addStatement("jobId = options.jobId()")
                .addStatement("premergedMetadata = options.premergedMetadata()")
                .endControlFlow()
                .addStatement(
                        "var jobBuilder = $T.builder().handler(handlerName).payload(payload).runAt(runAt)"
                                + ".queue(queue).priority(priority).maxAttempts(maxAttempts).jobId(jobId)",
                        DELAYED_JOB)
                .beginControlFlow("if (premergedMetadata != null)")
                .addStatement("$T job = jobBuilder.metadata(premergedMetadata).build()", DELAYED_JOB)
                .addStatement(
                        "return tx != null ? jobService.enqueuePremerged(job, tx) : jobService.enqueuePremerged(job)")
                .endControlFlow()
                .addStatement("$T job = jobBuilder.build()", DELAYED_JOB)
                .addStatement("return tx != null ? jobService.enqueue(job, tx) : jobService.enqueue(job)")
                .build();
    }

    private static MethodSpec buildToString() {
        return MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(String.class)
                .addStatement("return $S + handlerName + $S", "DelayedJobClient[", "]")
                .build();
    }

    private static MethodSpec buildEquals() {
        return MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
                .addParameter(Object.class, "o")
                .addStatement("return this == o")
                .build();
    }

    private static MethodSpec buildHashCode() {
        return MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT)
                .addStatement("return $T.identityHashCode(this)", System.class)
                .build();
    }
}
