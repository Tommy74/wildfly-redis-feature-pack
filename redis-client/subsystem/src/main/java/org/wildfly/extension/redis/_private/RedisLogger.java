/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis._private;

import java.lang.invoke.MethodHandles;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

import static org.jboss.logging.Logger.Level.WARN;

@MessageLogger(projectCode = "WFREDIS", length = 5)
public interface RedisLogger extends BasicLogger {

    RedisLogger ROOT_LOGGER = Logger.getMessageLogger(MethodHandles.lookup(), RedisLogger.class,
            "org.wildfly.extension.redis");

    @LogMessage(level = WARN)
    @Message(id = 1, value = "The deployment does not have Jakarta CDI enabled. Redis CDI injection will not work.")
    void cdiRequired();
}
