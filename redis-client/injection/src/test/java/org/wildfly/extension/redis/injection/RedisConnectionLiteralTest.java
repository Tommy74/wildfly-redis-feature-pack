/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis.injection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class RedisConnectionLiteralTest {

    @Test
    void testValueReturnsConstructorArgument() {
        RedisConnectionLiteral literal = new RedisConnectionLiteral("myconn");
        assertEquals("myconn", literal.value());
    }

    @Test
    void testAnnotationType() {
        RedisConnectionLiteral literal = new RedisConnectionLiteral("default");
        assertEquals(RedisConnection.class, literal.annotationType());
    }

    @Test
    void testEqualsWithSameValue() {
        RedisConnectionLiteral a = new RedisConnectionLiteral("same");
        RedisConnectionLiteral b = new RedisConnectionLiteral("same");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void testNotEqualsWithDifferentValue() {
        RedisConnectionLiteral a = new RedisConnectionLiteral("alpha");
        RedisConnectionLiteral b = new RedisConnectionLiteral("beta");
        assertNotEquals(a, b);
    }

    @Test
    void testToStringContainsValue() {
        RedisConnectionLiteral literal = new RedisConnectionLiteral("default");
        assertTrue(literal.toString().contains("default"));
    }
}
