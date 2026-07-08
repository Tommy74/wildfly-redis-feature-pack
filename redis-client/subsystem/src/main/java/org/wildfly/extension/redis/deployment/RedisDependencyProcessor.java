/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.deployment;

import static org.wildfly.extension.redis.RedisCapabilities.REDIS_CLIENT_PROVIDER_CAPABILITY;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.module.ModuleDependency;
import org.jboss.as.server.deployment.module.ModuleSpecification;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLoader;
import org.wildfly.extension.redis.injection.RedisConnection;
import org.jboss.as.server.deployment.annotation.CompositeIndex;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.DotName;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RedisDependencyProcessor implements DeploymentUnitProcessor {

    private static final String[] EXPORTED_MODULES = {
            "redis.clients.jedis",
            "org.wildfly.extension.redis.injection"
    };

    @Override
    public void deploy(DeploymentPhaseContext deploymentPhaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit deploymentUnit = deploymentPhaseContext.getDeploymentUnit();
        ModuleSpecification moduleSpecification = deploymentUnit.getAttachment(Attachments.MODULE_SPECIFICATION);
        ModuleLoader moduleLoader = Module.getBootModuleLoader();

        for (String module : EXPORTED_MODULES) {
            ModuleDependency modDep = ModuleDependency.Builder.of(moduleLoader, module)
                    .setExport(true)
                    .setImportServices(true)
                    .build();
            modDep.addImportFilter(s -> s.equals("META-INF"), true);
            moduleSpecification.addSystemDependency(modDep);
        }

        final CompositeIndex index = deploymentUnit.getAttachment(Attachments.COMPOSITE_ANNOTATION_INDEX);
        if (index == null) {
            return;
        }

        List<AnnotationInstance> annotations = index.getAnnotations(DotName.createSimple(RedisConnection.class));
        if (annotations == null || annotations.isEmpty()) {
            return;
        }

        Set<String> requiredConnections = new HashSet<>();
        for (AnnotationInstance annotation : annotations) {
            AnnotationValue value = annotation.value();
            String connectionName = (value != null) ? value.asString() : "default";
            requiredConnections.add(connectionName);
        }

        for (String connectionName : requiredConnections) {
            deploymentUnit.addToAttachmentList(RedisAttachments.REDIS_CONFIG_KEYS, connectionName);
            deploymentPhaseContext.addDeploymentDependency(
                    REDIS_CLIENT_PROVIDER_CAPABILITY.getCapabilityServiceName(connectionName),
                    RedisAttachments.REDIS_CONFIGS);
        }
    }
}
