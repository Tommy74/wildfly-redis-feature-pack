/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import java.util.EnumSet;
import org.jboss.as.subsystem.test.AbstractSubsystemSchemaTest;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RedisSubsystemTestCase extends AbstractSubsystemSchemaTest<RedisSubsystemSchema> {

    @Parameters
    public static Iterable<RedisSubsystemSchema> parameters() {
        return EnumSet.allOf(RedisSubsystemSchema.class);
    }

    public RedisSubsystemTestCase(RedisSubsystemSchema schema) {
        super(RedisSubsystemRegistrar.NAME, new RedisExtension(), schema, RedisSubsystemSchema.CURRENT);
    }
}
