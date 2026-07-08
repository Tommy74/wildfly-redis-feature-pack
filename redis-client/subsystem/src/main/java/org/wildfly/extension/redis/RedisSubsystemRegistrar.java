/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.SubsystemRegistration;
import org.jboss.as.controller.SubsystemResourceRegistration;
import org.jboss.as.controller.descriptions.ParentResourceDescriptionResolver;
import org.jboss.as.controller.descriptions.SubsystemResourceDescriptionResolver;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.server.deployment.Phase;
import org.wildfly.extension.redis.deployment.RedisDependencyProcessor;
import org.wildfly.extension.redis.deployment.RedisDeploymentProcessor;
import org.wildfly.subsystem.resource.ManagementResourceRegistrar;
import org.wildfly.subsystem.resource.ManagementResourceRegistrationContext;
import org.wildfly.subsystem.resource.ResourceDescriptor;
import org.wildfly.subsystem.resource.SubsystemResourceDefinitionRegistrar;

class RedisSubsystemRegistrar implements SubsystemResourceDefinitionRegistrar {

    public static final String NAME = "redis-client";
    static final SubsystemResourceRegistration REGISTRATION = SubsystemResourceRegistration.of(NAME);
    static final ParentResourceDescriptionResolver RESOLVER =
            new SubsystemResourceDescriptionResolver(NAME, RedisSubsystemRegistrar.class);

    private static final int PHASE_DEPENDENCIES_REDIS = 0x1940;
    private static final int PHASE_POST_MODULE_REDIS = 0x3850;

    @Override
    public ManagementResourceRegistration register(SubsystemRegistration parent, ManagementResourceRegistrationContext context) {
        ManagementResourceRegistration registration = parent.registerSubsystemModel(
                ResourceDefinition.builder(REGISTRATION, RESOLVER).build());

        ResourceDescriptor descriptor = ResourceDescriptor.builder(RESOLVER)
                .withDeploymentChainContributor(target -> {
                    target.addDeploymentProcessor(NAME, Phase.DEPENDENCIES, PHASE_DEPENDENCIES_REDIS, new RedisDependencyProcessor());
                    target.addDeploymentProcessor(NAME, Phase.POST_MODULE, PHASE_POST_MODULE_REDIS, new RedisDeploymentProcessor());
                })
                .build();

        ManagementResourceRegistrar.of(descriptor).register(registration);

        new RedisConnectionProviderRegistrar(RESOLVER).register(registration, context);

        return registration;
    }
}
