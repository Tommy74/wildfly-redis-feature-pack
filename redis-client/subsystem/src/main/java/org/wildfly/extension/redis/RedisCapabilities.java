/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import org.jboss.as.controller.capability.RuntimeCapability;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.service.descriptor.UnaryServiceDescriptor;

public interface RedisCapabilities {

    UnaryServiceDescriptor<RedisClientConfig> REDIS_CLIENT_PROVIDER_DESCRIPTOR =
            UnaryServiceDescriptor.of("org.wildfly.redis.connection", RedisClientConfig.class);

    RuntimeCapability<Void> REDIS_CLIENT_PROVIDER_CAPABILITY =
            RuntimeCapability.Builder.of(REDIS_CLIENT_PROVIDER_DESCRIPTOR)
                    .setAllowMultipleRegistrations(true)
                    .build();
}
