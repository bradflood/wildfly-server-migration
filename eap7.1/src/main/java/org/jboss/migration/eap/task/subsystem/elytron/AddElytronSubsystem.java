/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.migration.eap.task.subsystem.elytron;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.migration.core.task.TaskContext;
import org.jboss.migration.wfly10.config.management.HostConfiguration;
import org.jboss.migration.wfly10.config.management.HostControllerConfiguration;
import org.jboss.migration.wfly10.config.management.ManageableServerConfiguration;
import org.jboss.migration.wfly10.config.management.ManageableServerConfigurationType;
import org.jboss.migration.wfly10.config.management.StandaloneServerConfiguration;
import org.jboss.migration.wfly10.config.management.SubsystemResource;
import org.jboss.migration.wfly10.config.task.management.resource.ManageableResourceBuildParameters;
import org.jboss.migration.wfly10.config.task.management.subsystem.AddSubsystemResourceSubtaskBuilder;
import org.jboss.migration.wfly10.config.task.management.subsystem.AddSubsystemResources;
import org.jboss.migration.wfly10.config.task.subsystem.ExtensionNames;
import org.jboss.migration.wfly10.config.task.subsystem.SubsystemNames;

import static org.jboss.migration.core.task.component.TaskSkipPolicy.skipIfDefaultTaskSkipPropertyIsSet;

/**
 * @author emmartins
 */
public class AddElytronSubsystem<S> extends AddSubsystemResources<S> {
    public AddElytronSubsystem() {
        super(ExtensionNames.ELYTRON, new AddElytronSubsystemConfig<>());
    }

    public static class AddElytronSubsystemConfig<S> extends AddSubsystemResourceSubtaskBuilder<S> {
        protected AddElytronSubsystemConfig() {
            super(SubsystemNames.ELYTRON);
            skipPolicy(skipIfDefaultTaskSkipPropertyIsSet());
        }

        @Override
        protected void addConfiguration(ManageableResourceBuildParameters<S, SubsystemResource.Parent> params, TaskContext taskContext) {
            final ManageableServerConfiguration configuration = params.getServerConfiguration();
            final ManageableServerConfigurationType configurationType = configuration.getConfigurationType();
            final PathAddress subsystemPathAddress = params.getResource().getSubsystemResourcePathAddress(getSubsystem());
            final Operations.CompositeOperationBuilder compositeOperationBuilder = Operations.CompositeOperationBuilder.create();

            // add subsystem
            compositeOperationBuilder.addStep(new SubsystemAddOperation(subsystemPathAddress)
                    .finalProviders("combined-providers")
                    .addDisallowedProvider("OracleUcrypto")
                    .toModelNode());

            // add providers
            compositeOperationBuilder.addStep(new ProviderLoaderAddOperation(subsystemPathAddress, "elytron")
                    .module("org.wildfly.security.elytron")
                    .toModelNode());

            compositeOperationBuilder.addStep(new ProviderLoaderAddOperation(subsystemPathAddress, "openssl")
                    .module("org.wildfly.openssl")
                    .toModelNode());

            compositeOperationBuilder.addStep(new AggregateProvidersAddOperation(subsystemPathAddress, "combined-providers")
                    .addProvider("elytron")
                    .addProvider("openssl")
                    .toModelNode());

            // add audit logging
            compositeOperationBuilder.addStep(new FileAuditLogAddOperation(subsystemPathAddress, "local-audit")
                    .path("audit.log")
                    .relativeTo(configurationType != HostConfiguration.RESOURCE_TYPE ? "jboss.server.log.dir" : "jboss.domain.log.dir")
                    .format("JSON")
                    .toModelNode());

            // add security domains
            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostControllerConfiguration.RESOURCE_TYPE) {
                final SecurityDomainAddOperation securityDomainAddOperation = new SecurityDomainAddOperation(subsystemPathAddress, "ApplicationDomain")
                        .permissionMapper("default-permission-mapper")
                        .defaultRealm("ApplicationRealm")
                        .securityEventListener("local-audit")
                        .addRealm(new SecurityDomainAddOperation.Realm("ApplicationRealm").roleDecoder("groups-to-roles"));
                if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE) {
                    securityDomainAddOperation.addRealm(new SecurityDomainAddOperation.Realm("local"));
                }
                compositeOperationBuilder.addStep(securityDomainAddOperation.toModelNode());
            }

            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new SecurityDomainAddOperation(subsystemPathAddress, "ManagementDomain")
                        .permissionMapper("default-permission-mapper")
                        .defaultRealm("ManagementRealm")
                        .securityEventListener("local-audit")
                        .addRealm(new SecurityDomainAddOperation.Realm("ManagementRealm").roleDecoder("groups-to-roles"))
                        .addRealm(new SecurityDomainAddOperation.Realm("local").roleMapper("super-user-mapper"))
                        .toModelNode());
            }

            // add security realms
            compositeOperationBuilder.addStep(new IdentityRealmAddOperation(subsystemPathAddress, "local")
                    .identity("$local")
                    .toModelNode());

            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostControllerConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new PropertiesRealmAddOperation(subsystemPathAddress, "ApplicationRealm")
                        .usersProperties(new PropertiesRealmAddOperation.Properties("application-users.properties").relativeTo(configurationType == StandaloneServerConfiguration.RESOURCE_TYPE ? "jboss.server.config.dir" : "jboss.domain.config.dir").digestRealmName("ApplicationRealm"))
                        .groupsProperties(new PropertiesRealmAddOperation.Properties("application-roles.properties").relativeTo(configurationType == StandaloneServerConfiguration.RESOURCE_TYPE ? "jboss.server.config.dir" : "jboss.domain.config.dir"))
                        .toModelNode());
            }

            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new PropertiesRealmAddOperation(subsystemPathAddress, "ManagementRealm")
                        .usersProperties(new PropertiesRealmAddOperation.Properties("mgmt-users.properties").relativeTo(configurationType == StandaloneServerConfiguration.RESOURCE_TYPE ? "jboss.server.config.dir" : "jboss.domain.config.dir").digestRealmName("ManagementRealm"))
                        .groupsProperties(new PropertiesRealmAddOperation.Properties("mgmt-groups.properties").relativeTo(configurationType == StandaloneServerConfiguration.RESOURCE_TYPE ? "jboss.server.config.dir" : "jboss.domain.config.dir"))
                        .toModelNode());
            }

            // add mappers
            compositeOperationBuilder.addStep(new LogicalPermissionMapperAddOperation(subsystemPathAddress, "default-permission-mapper")
                    .logicalOperation("unless")
                    .left("constant-permission-mapper")
                    .right("anonymous-permission-mapper")
                    .toModelNode());

            compositeOperationBuilder.addStep(new SimplePermissionMapperAddOperation(subsystemPathAddress, "anonymous-permission-mapper")
                    .addPermissionMapping(new PermissionMapping().addPrincipal("anonymous").addPermission(new Permission("org.wildfly.security.auth.permission.LoginPermission")))
                    .toModelNode());

            final ConstantPermissionMapperAddOperation constantPermissionMapperAddOperation = new ConstantPermissionMapperAddOperation(subsystemPathAddress, "constant-permission-mapper")
                    .addPermission(new Permission("org.wildfly.security.auth.permission.LoginPermission"));
            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostControllerConfiguration.RESOURCE_TYPE) {
                constantPermissionMapperAddOperation.addPermission(new Permission("org.wildfly.extension.batch.jberet.deployment.BatchPermission").module("org.wildfly.extension.batch.jberet").targetName("*"))
                        .addPermission(new Permission("org.wildfly.transaction.client.RemoteTransactionPermission").module("org.wildfly.transaction.client"))
                        .addPermission(new Permission("org.jboss.ejb.client.RemoteEJBPermission").module("org.jboss.ejb-client"));
            }
            compositeOperationBuilder.addStep(constantPermissionMapperAddOperation.toModelNode());

            compositeOperationBuilder.addStep(new ConstantRealmMapperAddOperation(subsystemPathAddress, "local")
                    .realmName("local")
                    .toModelNode());

            compositeOperationBuilder.addStep(new SimpleRoleDecoderAddOperation(subsystemPathAddress, "groups-to-roles")
                    .attribute("groups")
                    .toModelNode());

            compositeOperationBuilder.addStep(new ConstantRoleMapperAddOperation(subsystemPathAddress, "super-user-mapper")
                    .addRole("SuperUser")
                    .toModelNode());

            // add http
            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new HttpAuthenticationFactoryAddOperation(subsystemPathAddress, "management-http-authentication")
                        .securityDomain("ManagementDomain")
                        .httpServerMechanismFactory("global")
                        .addMechanismConfiguration(new MechanismConfiguration("DIGEST").addMechanismRealmConfiguration(new MechanismRealmConfiguration("ManagementRealm")))
                        .toModelNode());
            } else if (configurationType == HostConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new HttpAuthenticationFactoryAddOperation(subsystemPathAddress, "management-http-authentication")
                        .securityDomain("ManagementDomain")
                        .httpServerMechanismFactory("global")
                        .addMechanismConfiguration(new MechanismConfiguration("BASIC").addMechanismRealmConfiguration(new MechanismRealmConfiguration("Management Realm")))
                        .toModelNode());
            }

            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostControllerConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new HttpAuthenticationFactoryAddOperation(subsystemPathAddress, "application-http-authentication")
                        .securityDomain("ApplicationDomain")
                        .httpServerMechanismFactory("global")
                        .addMechanismConfiguration(new MechanismConfiguration("BASIC").addMechanismRealmConfiguration(new MechanismRealmConfiguration("Application Realm")))
                        .addMechanismConfiguration(new MechanismConfiguration("FORM"))
                        .toModelNode());
            }

            compositeOperationBuilder.addStep(new ProviderHttpServerMechanismFactoryAddOperation(subsystemPathAddress, "global")
                    .toModelNode());

            // add sasl
            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new SaslAuthenticationFactoryAddOperation(subsystemPathAddress, "management-sasl-authentication")
                        .securityDomain("ManagementDomain")
                        .saslServerFactory("configured")
                        .addMechanismConfiguration(new MechanismConfiguration("JBOSS-LOCAL-USER").realmMapper("local"))
                        .addMechanismConfiguration(new MechanismConfiguration("DIGEST-MD5").addMechanismRealmConfiguration(new MechanismRealmConfiguration("ManagementRealm")))
                        .toModelNode());
            }

            if (configurationType == StandaloneServerConfiguration.RESOURCE_TYPE || configurationType == HostControllerConfiguration.RESOURCE_TYPE) {
                compositeOperationBuilder.addStep(new SaslAuthenticationFactoryAddOperation(subsystemPathAddress, "application-sasl-authentication")
                        .securityDomain("ApplicationDomain")
                        .saslServerFactory("configured")
                        .addMechanismConfiguration(new MechanismConfiguration("JBOSS-LOCAL-USER").realmMapper("local"))
                        .addMechanismConfiguration(new MechanismConfiguration("DIGEST-MD5").addMechanismRealmConfiguration(new MechanismRealmConfiguration("ApplicationRealm")))
                        .toModelNode());
            }

            compositeOperationBuilder.addStep(new ProviderSaslServerFactoryAddOperation(subsystemPathAddress, "global")
                    .toModelNode());

            compositeOperationBuilder.addStep(new MechanismProviderFilteringSaslServerFactoryAddOperation(subsystemPathAddress, "elytron")
                    .saslServerFactory("global")
                    .addFilter("WildFlyElytron")
                    .toModelNode());

            compositeOperationBuilder.addStep(new ConfigurableSaslServerFactoryAddOperation(subsystemPathAddress, "configured")
                    .saslServerFactory("elytron")
                    .addProperty("wildfly.sasl.local-user.default-user", "$local")
                    .toModelNode());

            // execute composed
            configuration.executeManagementOperation(compositeOperationBuilder.build().getOperation());
        }
    }
}
