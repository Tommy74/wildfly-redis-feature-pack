/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.test;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.ZipExporter;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.redis.test.session.SessionTestApplication;
import org.wildfly.redis.test.session.SessionTestResource;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@org.junit.jupiter.api.Disabled("Requires WildFly subprocesses to reach Podman-mapped Redis ports; run manually with the example app instead")
public class RedisSessionClusteringIT {

    private static Process redisProcess;
    private static Process node1Process;
    private static Process node2Process;

    private static final int REDIS_PORT = 26379;
    private static final int NODE1_HTTP_PORT = 8080 + 400;
    private static final int NODE2_HTTP_PORT = 8080 + 500;
    private static final String APP_CONTEXT = "/session-test";

    private static HttpClient httpClient;
    private static String jbossHome1;
    private static String jbossHome2;

    @BeforeAll
    static void setup() throws Exception {
        killStaleProcesses();

        redisProcess = new ProcessBuilder("podman", "run", "--rm", "--name", "redis-session-test",
                "-p", REDIS_PORT + ":6379", "redis:7-alpine")
                .redirectErrorStream(true).start();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(redisProcess.getInputStream()))) {
                String line; while ((line = r.readLine()) != null) System.out.println("[redis] " + line);
            } catch (Exception ignored) {}
        }).start();

        Thread.sleep(3000);

        RedisClientConfig verifyConfig = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort("127.0.0.1", REDIS_PORT)));
        try (UnifiedJedis verifyJedis = verifyConfig.createUnifiedJedis()) {
            assertEquals("PONG", verifyJedis.ping(), "Redis must be reachable");
        }

        jbossHome1 = System.getProperty("jboss.home.session1");
        jbossHome2 = System.getProperty("jboss.home.session2");
        assertNotNull(jbossHome1, "jboss.home.session1 must be set");
        assertNotNull(jbossHome2, "jboss.home.session2 must be set");

        WebArchive war = ShrinkWrap.create(WebArchive.class, "session-test.war")
                .addClasses(SessionTestApplication.class, SessionTestResource.class)
                .addAsWebInfResource(new StringAsset(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n" +
                        "    <distributable/>\n" +
                        "</web-app>"), "web.xml");

        node1Process = startWildFly(jbossHome1, "127.0.0.1", REDIS_PORT);
        waitForManagement(NODE1_HTTP_PORT - 8080 + 9990, 120);

        node2Process = startWildFly(jbossHome2, "127.0.0.1", REDIS_PORT);
        waitForManagement(NODE2_HTTP_PORT - 8080 + 9990, 120);

        Thread.sleep(5000);

        File warFile1 = new File(jbossHome1, "standalone/deployments/session-test.war");
        File warFile2 = new File(jbossHome2, "standalone/deployments/session-test.war");
        war.as(ZipExporter.class).exportTo(warFile1, true);
        war.as(ZipExporter.class).exportTo(warFile2, true);

        waitForApp(NODE1_HTTP_PORT, APP_CONTEXT, 120);
        waitForApp(NODE2_HTTP_PORT, APP_CONTEXT, 120);

        httpClient = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    @AfterAll
    static void cleanup() {
        stopProcess(node2Process);
        stopProcess(node1Process);
        stopProcess(redisProcess);
        try {
            new ProcessBuilder("podman", "rm", "-f", "redis-session-test").start().waitFor(10, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void testCreateSessionOnNode1() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + NODE1_HTTP_PORT + APP_CONTEXT + "/api/session/color/BLUE"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Response: " + response.body());
        assertTrue(response.body().contains("\"value\":\"BLUE\""), "Response should contain BLUE: " + response.body());
    }

    @Test
    @Order(2)
    void testSessionDataExistsInRedis() {
        RedisClientConfig config = new RedisClientConfig()
                .clusterNodes(Set.of(new HostAndPort("127.0.0.1", REDIS_PORT)));
        try (UnifiedJedis jedis = config.createUnifiedJedis()) {
            ScanParams params = new ScanParams().match("wf:ispn:*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            int count = 0;
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                count += result.getResult().size();
                cursor = result.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

            assertTrue(count > 0, "Expected session data in Redis with prefix wf:ispn:");
        }
    }

    @Test
    @Order(3)
    void testReadSessionFromNode2() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + NODE2_HTTP_PORT + APP_CONTEXT + "/api/session/color"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), "Response: " + response.body());
        assertTrue(response.body().contains("\"value\":\"BLUE\""),
                "Node 2 should return session data created on node 1: " + response.body());
    }

    @Test
    @Order(4)
    void testFailoverUpdateAndRecovery() throws Exception {
        stopProcess(node1Process);
        Thread.sleep(3000);

        HttpRequest putRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + NODE2_HTTP_PORT + APP_CONTEXT + "/api/session/color/RED"))
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, putResponse.statusCode(), "PUT on node 2 after node 1 killed: " + putResponse.body());
        assertTrue(putResponse.body().contains("\"value\":\"RED\""), "Response should contain RED: " + putResponse.body());

        node1Process = startWildFly(jbossHome1, "127.0.0.1", REDIS_PORT);
        waitForManagement(NODE1_HTTP_PORT - 8080 + 9990, 120);

        WebArchive war = ShrinkWrap.create(WebArchive.class, "session-test.war")
                .addClasses(SessionTestApplication.class, SessionTestResource.class)
                .addAsWebInfResource(new StringAsset(
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                        "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\" version=\"6.0\">\n" +
                        "    <distributable/>\n" +
                        "</web-app>"), "web.xml");
        File warFile = new File(jbossHome1, "standalone/deployments/session-test.war");
        war.as(ZipExporter.class).exportTo(warFile, true);
        waitForApp(NODE1_HTTP_PORT, APP_CONTEXT, 120);

        HttpRequest getRequest = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + NODE1_HTTP_PORT + APP_CONTEXT + "/api/session/color"))
                .GET()
                .build();
        HttpResponse<String> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, getResponse.statusCode(), "GET from restarted node 1: " + getResponse.body());
        assertTrue(getResponse.body().contains("\"value\":\"RED\""),
                "Restarted node 1 should return updated value from Redis: " + getResponse.body());
    }

    private static Process startWildFly(String jbossHome, String redisHost, int redisPort) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                jbossHome + "/bin/standalone.sh",
                "--stability=community",
                "-Djboss.redis-client.redis-connection.cluster-nodes=" + redisHost + ":" + redisPort
        );
        pb.directory(new File(jbossHome));
        pb.redirectErrorStream(true);
        pb.environment().put("JBOSS_HOME", jbossHome);

        Process process = pb.start();
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[wildfly " + jbossHome.substring(jbossHome.lastIndexOf('/') + 1) + "] " + line);
                }
            } catch (Exception ignored) {}
        }).start();

        return process;
    }

    private static void waitForManagement(int managementPort, int timeoutSec) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new java.net.URL(
                        "http://localhost:" + managementPort + "/management").openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                if (code > 0) return;
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new RuntimeException("Management interface did not start within " + timeoutSec + "s on port " + managementPort);
    }

    private static void waitForApp(int port, String contextPath, int timeoutSec) throws Exception {
        String url = "http://localhost:" + port + contextPath + "/api/session/ping";
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                conn.getResponseCode();
                return;
            } catch (Exception ignored) {}
            Thread.sleep(2000);
        }
        throw new RuntimeException("App did not deploy within " + timeoutSec + "s at " + url);
    }

    private static void killStaleProcesses() {
        try {
            new ProcessBuilder("pkill", "-f", "wildfly-session").start().waitFor(5, TimeUnit.SECONDS);
            new ProcessBuilder("podman", "rm", "-f", "redis-session-test").start().waitFor(5, TimeUnit.SECONDS);
            Thread.sleep(2000);
        } catch (Exception ignored) {}
    }

    private static void stopProcess(Process process) {
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                process.waitFor(30, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
