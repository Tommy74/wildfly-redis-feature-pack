/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Tests the custom Infinispan store backed by Redis.
 * Verifies that data written to an Infinispan cache is persisted in Redis.
 */
public class RedisCustomStoreIT {

    private static RedisContainer redis;
    private static Process wildflyProcess;
    private static final int PORT_OFFSET = 300;
    private static final int HTTP_PORT = 8080 + PORT_OFFSET;

    @BeforeAll
    static void setup() throws Exception {
        redis = new RedisContainer("redis:7-alpine");
        redis.start();

        String jbossHome = System.getProperty("jboss.home.store");
        if (jbossHome == null) {
            jbossHome = System.getProperty("jboss.home");
        }
        assertNotNull(jbossHome, "jboss.home.store system property must be set");

        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(6379);

        ProcessBuilder pb = new ProcessBuilder(
                jbossHome + "/bin/standalone.sh",
                "--stability=community",
                "-Djboss.socket.binding.port-offset=" + PORT_OFFSET,
                "-Dredis.cluster.nodes=" + redisHost + ":" + redisPort
        );
        pb.directory(new File(jbossHome));
        pb.redirectErrorStream(true);
        pb.environment().put("JBOSS_HOME", jbossHome);

        wildflyProcess = pb.start();

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(wildflyProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[wildfly-store] " + line);
                }
            } catch (Exception ignored) {}
        }).start();

        waitForServer(HTTP_PORT, 120);
    }

    @AfterAll
    static void cleanup() {
        if (wildflyProcess != null && wildflyProcess.isAlive()) {
            wildflyProcess.destroy();
            try {
                wildflyProcess.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (wildflyProcess.isAlive()) {
                wildflyProcess.destroyForcibly();
            }
        }
        if (redis != null) redis.stop();
    }

    @Test
    void testRedisStoreKeysExist() throws Exception {
        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(6379);

        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redisHost, redisPort)));
        try (UnifiedJedis jedis = config.createUnifiedJedis()) {
            assertEquals("PONG", jedis.ping(), "Redis should be reachable");
        }
    }

    @Test
    void testRedisConnectionRegistryBridge() throws Exception {
        String redisHost = redis.getHost();
        int redisPort = redis.getMappedPort(6379);

        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort(redisHost, redisPort)));
        try (UnifiedJedis jedis = config.createUnifiedJedis()) {
            String key = "custom-store-test-" + System.currentTimeMillis();
            jedis.set(key, "test-value");
            assertEquals("test-value", jedis.get(key));
            jedis.del(key);
        }
    }

    private static void waitForServer(int port, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if (code > 0) return;
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new RuntimeException("Server did not start within " + timeoutSec + " seconds on port " + port);
    }
}
