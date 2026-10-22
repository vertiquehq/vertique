// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.schema;

import dagger.Binds;
import dagger.BindsOptionalOf;
import dagger.Module;
import dagger.multibindings.IntoSet;
import dev.vertique.workflow.migration.WorkflowStateCompatibility;
import dev.vertique.workflow.registry.WorkflowDefinitionValidator;

/**
 * Dagger module that checks the payload types of every workflow definition when it is registered.
 *
 * <p>It contributes a {@link WorkflowDefinitionValidator} to the workflow registry. For each start and decision
 * payload type of a candidate definition the validator generates the canonical JSON Schema under the process JSON
 * profile, requires the payload to be an object (or {@code void}), checks that every pattern compiles, compiles the
 * schema and keeps the result in a cache keyed by payload type and profile. A definition that fails any step is not
 * published, whether it is registered at bootstrap or activated at runtime.
 *
 * <p>The module expects the application to bind {@code JsonMapperProfileRegistry} and {@code JsonConfig}, as the
 * JSON runtime module does. A {@code jakarta.validation.Validator} binding is optional: when one is present the
 * generated schemas also reflect the constraints it reports. Install the validation module alongside so that the
 * schema and the engine's payload enforcement agree.
 *
 * <p>It also binds {@link WorkflowPayloadSchemas}, which reads the cached schemas back as descriptors for clients,
 * and {@link WorkflowStateCompatibility}, which compares the state classes of definitions that share an id across
 * scopes by their JSON Schemas: added properties are allowed, removed or retyped properties are not.
 *
 * <p>Registration validators run synchronously on the registering thread; call {@code activate} off the event loop.
 */
@Module
public abstract class WorkflowSchemaModule {

    /**
     * Binds the read access to the payload schemas of registered definitions.
     *
     * @param schemas the default implementation
     * @return the service as the public interface
     */
    @Binds
    abstract WorkflowPayloadSchemas payloadSchemas(DefaultWorkflowPayloadSchemas schemas);

    /**
     * Contributes the payload registration validator to the workflow registry.
     *
     * @param validator the validator
     * @return the validator as a registration-time definition validator
     */
    @Binds
    @IntoSet
    abstract WorkflowDefinitionValidator payloadSchemaRegistrationValidator(
            PayloadSchemaRegistrationValidator validator);

    /**
     * Binds the comparison the workflow registry and the engine use to decide whether one state class extends
     * another, so differing state classes of one definition id across scopes are compared instead of refused.
     *
     * @param compatibility the schema-based comparison
     * @return the comparison as the public interface
     */
    @Binds
    abstract WorkflowStateCompatibility stateCompatibility(SchemaStateCompatibility compatibility);

    /**
     * Declares the optional bean validator the schema generator reads constraints from.
     *
     * @return the validator when the application binds one
     */
    @BindsOptionalOf
    abstract jakarta.validation.Validator optionalValidator();
}
