/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.test;

import static org.junit.jupiter.api.Assertions.*;

import com.redis.testcontainers.RedisContainer;
import jakarta.inject.Inject;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.wildfly.extension.redis.injection.RedisConnection;
import redis.clients.jedis.UnifiedJedis;

@ExtendWith(ArquillianExtension.class)
public class RedisSubsystemIT {

    private static RedisContainer redis;

    @Inject
    @RedisConnection("default")
    private UnifiedJedis jedis;

    @BeforeAll
    static void startRedis() {
        redis = new RedisContainer("redis:7-alpine");
        redis.start();
        System.setProperty("redis.cluster.nodes",
                redis.getHost() + ":" + redis.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) redis.stop();
    }

    @Deployment
    public static WebArchive createDeployment() {
        return ShrinkWrap.create(WebArchive.class, "redis-test.war")
                .addClass(RedisSubsystemIT.class)
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addPackages(true, "org.hamcrest");
    }

    @Test
    public void testRedisInjection() {
        assertNotNull(jedis, "UnifiedJedis should be injected");
    }

    @Test
    public void testRedisSetGet() {
        String key = "test-key-" + System.currentTimeMillis();
        jedis.set(key, "hello-redis");
        assertEquals("hello-redis", jedis.get(key));
        jedis.del(key);
    }
}
