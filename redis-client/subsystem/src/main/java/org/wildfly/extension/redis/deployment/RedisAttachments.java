/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.deployment;

import org.jboss.as.server.deployment.AttachmentKey;
import org.jboss.as.server.deployment.AttachmentList;
import org.wildfly.extension.redis.injection.RedisClientConfig;

public class RedisAttachments {

    static final AttachmentKey<AttachmentList<RedisClientConfig>> REDIS_CONFIGS =
            AttachmentKey.createList(RedisClientConfig.class);

    static final AttachmentKey<AttachmentList<String>> REDIS_CONFIG_KEYS =
            AttachmentKey.createList(String.class);
}
