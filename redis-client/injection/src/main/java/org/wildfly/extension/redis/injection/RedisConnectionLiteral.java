/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import jakarta.enterprise.util.AnnotationLiteral;

public class RedisConnectionLiteral extends AnnotationLiteral<RedisConnection> implements RedisConnection {
    private final String value;

    public RedisConnectionLiteral(String value) {
        this.value = value;
    }

    @Override
    public String value() {
        return value;
    }
}
