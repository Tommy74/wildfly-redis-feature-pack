/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import org.wildfly.subsystem.SubsystemConfiguration;
import org.wildfly.subsystem.SubsystemExtension;
import org.wildfly.subsystem.SubsystemPersistence;

public class RedisExtension extends SubsystemExtension<RedisSubsystemSchema> {

    public RedisExtension() {
        super(SubsystemConfiguration.of(RedisSubsystemRegistrar.NAME, RedisSubsystemModel.CURRENT, RedisSubsystemRegistrar::new),
                SubsystemPersistence.of(RedisSubsystemSchema.CURRENT));
    }
}
