# Plan: Add `cluster-nodes` store property with shared system property expression

## Context

The `RedisNonBlockingStore` already supports a `cluster-nodes` property as a **fallback** when the `connection` registry lookup fails (`RedisNonBlockingStore.java:121-132`). However, the CLI commands that provision servers never set this property on the store — they only set `connection=default`.

The goal is to:
1. Explicitly add `cluster-nodes` as a store `<property>` in all provisioning CLI commands
2. Use a shared system property expression `${jboss.redis-client.redis-connection.cluster-nodes:127.0.0.1:6379}` for both the `redis-connection` subsystem attribute and the store property
3. Keep the `connection`-based registry lookup as the primary/default mechanism — `cluster-nodes` acts as a fallback

**No Java code changes needed for the core logic.** The existing fallback in `RedisNonBlockingStore.doStart()` already handles the `cluster-nodes` property. Only CLI commands, test `-D` flags, and documentation need updating.

## Changes

### 1. `redis-client-example/pom.xml` — 4 CLI command blocks

Each block currently has:
```
/subsystem=redis-client/redis-connection=default:add(cluster-nodes=127.0.0.1:6379)
...store=custom:add(..., properties={connection=default})
```

Change to:
```
/subsystem=redis-client/redis-connection=default:add(cluster-nodes=${jboss.redis-client.redis-connection.cluster-nodes:127.0.0.1:6379})
...store=custom:add(..., properties={connection=default, cluster-nodes=${jboss.redis-client.redis-connection.cluster-nodes:127.0.0.1:6379}})
```

Affected lines (approximate): ~80, ~103, ~137, ~164.

### 2. `redis-client-testsuite/pom.xml` — 4 CLI command blocks

Change the system property name from `redis.cluster.nodes` to `jboss.redis-client.redis-connection.cluster-nodes` in all `redis-connection` commands, and add `cluster-nodes` to the store properties where a store is configured.

- **Line ~135** (provision-subsystem-server): `redis-connection` only, no store — just update the expression name
- **Line ~182** (provision-session-server-1): update `redis-connection` expression + add `cluster-nodes` to store properties
- **Line ~228** (provision-session-server-2): same as above
- **Line ~270** (provision-store-server): `redis-connection` only — just update the expression name

### 3. Test files — update `-D` system property name

Rename `redis.cluster.nodes` → `jboss.redis-client.redis-connection.cluster-nodes` in:

- `RedisCustomStoreIT.java:56` — `-Dredis.cluster.nodes=` flag
- `RedisSessionClusteringIT.java:210` — `-Dredis.cluster.nodes=` flag
- `RedisSubsystemIT.java:36` — `System.setProperty("redis.cluster.nodes", ...)`
- `RedisSocketBindingIT.java:38` — `System.setProperty("redis.cluster.nodes", ...)`
- `arquillian.xml:11` — `-Dredis.cluster.nodes=...` in `javaVmArguments`

### 4. Documentation — Javadoc updates

**`RedisStoreConfigurationBuilder.java`**: Update the example XML in the class Javadoc to show both properties:
```xml
<store class="org.wildfly.redis.store.RedisStoreConfigurationBuilder">
    <property name="connection">default</property>
    <property name="cluster-nodes">${jboss.redis-client.redis-connection.cluster-nodes:127.0.0.1:6379}</property>
</store>
```

**`RedisNonBlockingStore.java`**: Update the class-level Javadoc to document the recommended usage with both properties.

## Verification

1. `mvn clean install -DskipTests` — full build succeeds
2. `mvn test -pl redis-client/store` — unit tests pass
3. Inspect generated `standalone.xml` in `redis-client-example/target/server/standalone/configuration/` to confirm both the `redis-connection` and the store `properties` contain the shared expression
4. `mvn verify -pl redis-client-testsuite` (with Redis running) — integration tests pass with the renamed system property
