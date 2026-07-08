/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.deployment;

import static org.jboss.as.weld.Capabilities.WELD_CAPABILITY_NAME;

import jakarta.enterprise.inject.spi.Extension;
import java.util.List;
import org.jboss.as.controller.capability.CapabilityServiceSupport;
import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.weld.WeldCapability;
import org.wildfly.extension.redis._private.RedisLogger;
import org.wildfly.extension.redis.injection.RedisBeanRegistry;
import org.wildfly.extension.redis.injection.RedisClientConfig;

public class RedisDeploymentProcessor implements DeploymentUnitProcessor {

    @Override
    public void deploy(DeploymentPhaseContext deploymentPhaseContext) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = deploymentPhaseContext.getDeploymentUnit();
        try {
            final CapabilityServiceSupport support = deploymentUnit.getAttachment(Attachments.CAPABILITY_SERVICE_SUPPORT);
            final WeldCapability weldCapability = support.getCapabilityRuntimeAPI(WELD_CAPABILITY_NAME, WeldCapability.class);

            if (weldCapability != null && !weldCapability.isPartOfWeldDeployment(deploymentUnit)) {
                RedisLogger.ROOT_LOGGER.cdiRequired();
                return;
            }

            List<RedisClientConfig> requiredConfigs = deploymentUnit.getAttachmentList(RedisAttachments.REDIS_CONFIGS);
            List<String> configNames = deploymentUnit.getAttachmentList(RedisAttachments.REDIS_CONFIG_KEYS);

            if (!requiredConfigs.isEmpty()) {
                for (int i = 0; i < requiredConfigs.size(); i++) {
                    RedisBeanRegistry.register(configNames.get(i), requiredConfigs.get(i));
                }

                for (Extension extension : RedisBeanRegistry.getCDIExtensions()) {
                    support.getOptionalCapabilityRuntimeAPI(WELD_CAPABILITY_NAME, WeldCapability.class).get()
                            .registerExtensionInstance(extension, deploymentUnit);
                }
            }
        } catch (CapabilityServiceSupport.NoSuchCapabilityException e) {
            // Weld not available, skip CDI registration
        }
    }

    @Override
    public void undeploy(DeploymentUnit context) {
    }
}
