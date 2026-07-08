# WildFly Redis Feature Pack

A WildFly Galleon feature pack that provides two capabilities:

1. **Redis Client Subsystem** — a `redis-client` subsystem that manages Redis connection pools and makes Jedis's `UnifiedJedis` injectable into Jakarta EE applications via CDI
2. **Custom Infinispan Cache Store** — a `NonBlockingStore` backed by Redis, configurable as a standard Infinispan `<store class="...">` element, enabling any Infinispan cache to persist data to Redis

The custom store reads its connection configuration from the `redis-client` subsystem via a shared static registry — you configure the Redis connection once and reference it by name from the store.

## Prerequisites

- Java 17+
- Maven 3.9+
- Podman (for running Redis locally and integration tests)

### Running the Integration Tests

The test suite uses [Testcontainers](https://testcontainers.com/). On Fedora/RHEL with Podman:

```bash
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true

mvn clean install -Denforcer.skip
```

### Sample Application

The quickest way to try the feature pack is the included `redis-client-example` application. It demonstrates both Redis CDI injection (Part 1) and the custom Infinispan cache store backed by Redis (Part 2).

**1. Start Redis**

```bash
podman run --rm -it --name redis -p 6379:6379 redis:7-alpine
```

**2. Build the feature pack**

```bash
mvn clean install -DskipTests -Denforcer.skip
```

**3. Run the example application**

```bash
cd redis-client-example
mvn wildfly:dev -Denforcer.skip
```

**4. Test direct Redis operations** (Part 1 — CDI injection of `UnifiedJedis`)

```bash
curl http://localhost:8080/redis-example/api/redis/set/hello/world
# OK

curl http://localhost:8080/redis-example/api/redis/get/hello
# world
```

The provisioned server also includes an Infinispan cache (`mycontainer/mycache`) backed by the Redis custom store (Part 2). Any WildFly subsystem or application that uses this cache will have its entries automatically persisted to Redis with the key pattern `wf:ispn:mycache:*`.

---

## Part 1: Try It Out — Redis Client Injection

This section shows WildFly injecting a Jedis `UnifiedJedis` client via CDI.

### 1. Start Redis

```bash
podman run --rm -it --name redis -p 6379:6379 redis:7-alpine
```

### 2. Build the feature pack

```bash
mvn clean install -DskipTests -Denforcer.skip
```

### 3. Provision a WildFly server

Add the feature pack to your application's `pom.xml`:

```xml
<plugin>
    <groupId>org.wildfly.plugins</groupId>
    <artifactId>wildfly-maven-plugin</artifactId>
    <version>6.0.0.Final</version>
    <configuration>
        <feature-packs>
            <feature-pack>
                <groupId>org.wildfly</groupId>
                <artifactId>wildfly-galleon-pack</artifactId>
                <version>41.0.0.Beta1</version>
            </feature-pack>
            <feature-pack>
                <groupId>org.wildfly.redis</groupId>
                <artifactId>redis-client-feature-pack</artifactId>
                <version>1.0.0-SNAPSHOT</version>
            </feature-pack>
        </feature-packs>
        <layers>
            <layer>jaxrs-server</layer>
            <layer>redis-client</layer>
        </layers>
        <packagingScripts>
            <packaging-script>
                <commands>
                    <command>/subsystem=redis-client/redis-connection=default:add(cluster-nodes=127.0.0.1:6379)</command>
                </commands>
            </packaging-script>
        </packagingScripts>
    </configuration>
</plugin>
```

### 4. Inject and use Redis

```java
import org.wildfly.extension.redis.injection.RedisConnection;
import redis.clients.jedis.UnifiedJedis;

@ApplicationScoped
public class MyService {

    @Inject
    @RedisConnection("default")
    private UnifiedJedis jedis;

    public void store(String key, String value) {
        jedis.set(key, value);
    }

    public String load(String key) {
        return jedis.get(key);
    }
}
```

---

## Part 2: Custom Infinispan Cache Store Backed by Redis

The `redis-store` layer provides `RedisNonBlockingStore` — a custom Infinispan `NonBlockingStore` that persists cache entries to Redis. This lets any Infinispan cache (local, invalidation, replicated, distributed) use Redis as its backing store.

### How it works

The store is configured using the standard Infinispan `<store class="...">` element. The `connection` property references a named `redis-connection` from the `redis-client` subsystem:

```xml
<subsystem xmlns="urn:jboss:domain:infinispan:...">
    <cache-container name="mycontainer">
        <local-cache name="mycache" modules="org.wildfly.redis.store">
            <store class="org.wildfly.redis.store.RedisStoreConfigurationBuilder"
                   passivation="false" preload="false" purge="false" shared="true">
                <property name="connection">default</property>
            </store>
        </local-cache>
    </cache-container>
</subsystem>
```

The `modules="org.wildfly.redis.store"` attribute tells WildFly to use the store module's classloader for loading the store class.

### Provisioning with the redis-store layer

```xml
<layers>
    <layer>jaxrs-server</layer>
    <layer>redis-client</layer>
    <layer>redis-store</layer>
</layers>
```

The `redis-store` layer depends on `redis-client` and makes the store module available on the classpath.

### Store configuration via CLI

```bash
# Redis connection
/subsystem=redis-client/redis-connection=default:add(cluster-nodes=127.0.0.1:6379)

# Infinispan cache with Redis custom store
/subsystem=infinispan/cache-container=mycontainer:add()
/subsystem=infinispan/cache-container=mycontainer/local-cache=mycache:add(modules=[org.wildfly.redis.store])
/subsystem=infinispan/cache-container=mycontainer/local-cache=mycache/store=custom:add( \
    class=org.wildfly.redis.store.RedisStoreConfigurationBuilder, \
    passivation=false, preload=false, purge=false, shared=true, \
    properties={connection=default})
```

### Store properties

| Property        | Default           | Description                                                              |
|-----------------|-------------------|--------------------------------------------------------------------------|
| `connection`    | (none)            | Name of a `redis-connection` in the `redis-client` subsystem (preferred) |
| `cluster-nodes` | `127.0.0.1:6379`  | Comma-separated `host:port` pairs (fallback if `connection` not set)     |
| `password`      | (none)            | Redis password (fallback if `connection` not set)                        |

When `connection` is set, the store looks up the named connection from the `redis-client` subsystem's static registry (`RedisConnectionRegistry`). This way the Redis connection is configured once in the subsystem and shared with the store — no config duplication.

### Redis key format

Cache entries are stored with the key pattern:

```
wf:ispn:{cacheName}:{base64(marshalledKey)}
```

This prefix ensures cache data doesn't collide with other Redis data. TTL-based expiration is used when entries have a lifespan configured.

---

## Part 3: Production Configuration with SSL

In production, use `remote-destination-outbound-socket-binding` for managed host/port configuration and Elytron `client-ssl-context` for TLS encryption.

### Start Redis with TLS

```bash
# Generate certificates
openssl req -x509 -newkey rsa:2048 -keyout ca-key.pem -out ca-cert.pem \
  -days 365 -nodes -subj '/CN=Redis CA'

openssl req -newkey rsa:2048 -keyout server-key.pem -out server-req.pem \
  -nodes -subj '/CN=localhost'
openssl x509 -req -in server-req.pem -CA ca-cert.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -days 365 \
  -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1")
rm server-req.pem

# Start Redis with TLS
podman run --rm -it --name redis-tls -p 6380:6379 \
  -v ./ca-cert.pem:/tls/ca-cert.pem:ro \
  -v ./server-cert.pem:/tls/server-cert.pem:ro \
  -v ./server-key.pem:/tls/server-key.pem:ro \
  redis:7-alpine \
  redis-server \
    --tls-port 6379 --port 0 \
    --tls-cert-file /tls/server-cert.pem \
    --tls-key-file /tls/server-key.pem \
    --tls-ca-cert-file /tls/ca-cert.pem
```

### Create a truststore for WildFly

```bash
keytool -importcert -alias redis-ca -file ca-cert.pem \
  -keystore redis-truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt
```

Copy `redis-truststore.p12` to `$JBOSS_HOME/standalone/configuration/`.

### Configure with CLI packaging scripts

```xml
<packagingScripts>
    <packaging-script>
        <commands>
            <!-- Outbound socket binding for Redis -->
            <command>/socket-binding-group=standard-sockets/remote-destination-outbound-socket-binding=redis-server:add(host=${redis.host:localhost},port=${redis.port:6380})</command>

            <!-- Elytron SSL configuration -->
            <command>/subsystem=elytron/key-store=redis-truststore:add(credential-reference={clear-text=changeit},path=redis-truststore.p12,relative-to=jboss.server.config.dir,type=PKCS12)</command>
            <command>/subsystem=elytron/trust-manager=redis-trust-manager:add(key-store=redis-truststore)</command>
            <command>/subsystem=elytron/client-ssl-context=redis-ssl-context:add(trust-manager=redis-trust-manager)</command>

            <!-- Redis connection using socket binding + SSL -->
            <command>/subsystem=redis-client/redis-connection=default:add(outbound-socket-bindings=[redis-server],ssl-context=redis-ssl-context)</command>
        </commands>
    </packaging-script>
</packagingScripts>
```

### Connecting to a Redis Cluster

For a multi-node Redis Cluster, define one outbound socket binding per Redis node:

```
/socket-binding-group=standard-sockets/remote-destination-outbound-socket-binding=redis-1:add(host=redis1.example.com,port=6380)
/socket-binding-group=standard-sockets/remote-destination-outbound-socket-binding=redis-2:add(host=redis2.example.com,port=6380)
/socket-binding-group=standard-sockets/remote-destination-outbound-socket-binding=redis-3:add(host=redis3.example.com,port=6380)

/subsystem=redis-client/redis-connection=default:add(outbound-socket-bindings=[redis-1 redis-2 redis-3],ssl-context=redis-ssl-context)
```

When multiple socket bindings are provided, the subsystem creates a `JedisCluster` client. When a single binding is provided, it creates a `JedisPooled` client. Both are injected as `UnifiedJedis`.

---

## Reference

### Subsystem Configuration

Each `<redis-connection>` element supports the following attributes:

| Attribute                  | Type    | Default    | Description                                                     |
|----------------------------|---------|------------|-----------------------------------------------------------------|
| `name`                     | string  | (required) | Connection name, used with `@RedisConnection("name")`           |
| `cluster-nodes`            | string  | (none)     | Comma-separated `host:port` pairs (single node or cluster)      |
| `outbound-socket-bindings` | string  | (none)     | Space-separated outbound socket binding names                   |
| `password`                 | string  | (none)     | Authentication password                                         |
| `ssl`                      | boolean | `false`    | Enable SSL/TLS (uses JVM default trust store)                   |
| `ssl-context`              | string  | (none)     | Reference to an Elytron `client-ssl-context` (implies ssl=true) |
| `connection-timeout`       | int     | `2000`     | Connection timeout in milliseconds                              |
| `max-pool-size`            | int     | `8`        | Maximum connections in the pool                                  |
| `min-idle`                 | int     | `0`        | Minimum idle connections                                         |

Either `cluster-nodes` or `outbound-socket-bindings` must be provided (they are mutually exclusive).

All attributes support WildFly expressions (`${property:default}`).

### Galleon Layers

| Layer          | Description                                                        | Dependencies     |
|----------------|--------------------------------------------------------------------|------------------|
| `redis-client` | Adds the `redis-client` subsystem for CDI injection and connection management | `cdi`, `elytron` (optional) |
| `redis-store`  | Adds the custom Infinispan store module (`org.wildfly.redis.store`) | `redis-client`   |

### Running a Redis Cluster Locally

```bash
# Start 3 Redis nodes on the host network
for port in 7000 7001 7002; do
  podman run -d --rm --name "redis-${port}" --network host \
    redis:7-alpine \
    redis-server \
      --port "${port}" \
      --cluster-enabled yes \
      --cluster-config-file "nodes-${port}.conf" \
      --cluster-node-timeout 5000 \
      --appendonly yes
done

# Form the cluster
podman exec -it redis-7000 \
  redis-cli --cluster create \
    127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 \
    --cluster-replicas 0 --cluster-yes
```

Tear it down with:

```bash
podman rm -f redis-7000 redis-7001 redis-7002
```

## Architecture Deep Dive: How the Store Bridges WildFly Services into Infinispan

Infinispan cache stores run entirely within Infinispan's own runtime. A store's `NonBlockingStore.start(InitializationContext)` method only receives Infinispan-scoped objects (the cache, the marshaller, executors) — it has **no access** to WildFly's MSC service container, capabilities, or managed services. This creates a problem: our Redis store needs a `RedisClientConfig` that is configured and managed by the WildFly `redis-client` subsystem, but the store cannot look it up through WildFly's service layer.

### How WildFly's HotRod store solves this (the "capability injection" pattern)

WildFly's built-in HotRod store faces the same problem — it needs a `RemoteCacheContainer` that is managed as a WildFly MSC service. WildFly solves this with a dedicated store registrar that injects the live WildFly service object into the Infinispan configuration builder at subsystem boot time, before the configuration is frozen:

```
WildFly management model (store=hotrod, remote-cache-container="foo")
    │
    ▼
HotRodStoreResourceDefinitionRegistrar.resolve()
    │  CapabilityReferenceAttributeDefinition resolves "foo"
    │  into a ServiceDependency<RemoteCacheContainer>
    │
    │  .map(container -> new ConfigurationBuilder()
    │      .persistence()
    │      .addStore(RemoteCacheStoreConfigurationBuilder.class)
    │      .container(container)      ◄── LIVE SERVICE OBJECT INJECTED
    │      .template(templateName))
    │
    ▼
StoreResourceDefinitionRegistrar.resolve()
    │  Applies common store attributes (passivation, properties, etc.)
    │  Returns ServiceDependency<PersistenceConfigurationBuilder>
    │
    ▼
ConfigurationResourceServiceConfigurator
    │  Calls Builder.create() → freezes the builder into an immutable
    │  RemoteCacheStoreConfiguration with the RemoteCacheContainer
    │  baked into its AttributeSet
    │
    ▼
Infinispan PersistenceManagerImpl
    │  Reads store configuration, instantiates RemoteCacheStore
    │  Calls store.start(InitializationContext)
    │
    ▼
RemoteCacheStore.start()
    │  RemoteCacheStoreConfiguration config = ctx.getConfiguration();
    │  this.container = config.container();  ◄── RETRIEVES THE LIVE SERVICE
```

This works because:

1. **`HotRodStoreResourceDefinitionRegistrar`** has compile-time knowledge of the builder type (`RemoteCacheStoreConfigurationBuilder`) and its `.container()` method.
2. A **`CapabilityReferenceAttributeDefinition`** declares the `remote-cache-container` management attribute, which creates a proper MSC service dependency.
3. The live `RemoteCacheContainer` object is captured inside the Infinispan `AttributeSet` at builder time and frozen into the immutable configuration via `attributes.protect()`.
4. At store startup, `config.container()` retrieves the object from the frozen attribute set.

### Why the generic custom store can't do this

The generic `CustomStoreResourceDefinitionRegistrar` (used for `<store class="...">`) only knows two things: the `class` name (a string) and a list of `<property>` name/value pairs. Its `resolve()` method loads the builder class via reflection and calls `addStore(storeClass)` — but it has no capability reference attributes, no MSC dependency wiring, and no compile-time knowledge of the builder's API. It cannot call `.container(...)` or any custom setter because it doesn't know the builder type.

### How this feature pack bridges the gap: static connection registry

Since we use the generic `<store class="...">` mechanism (rather than creating a dedicated store type in the Infinispan extension), we need an alternative to the capability injection pattern. This feature pack uses a **static connection registry** shared across JBoss Modules classloaders:

```
WildFly boot
    │
    ▼
RedisConnectionServiceConfigurator (redis-client subsystem)
    │  MSC service starts, creates RedisClientConfig from
    │  subsystem attributes (cluster-nodes, password, SSL, etc.)
    │
    │  RedisConnectionRegistry.register("default", config)
    │  ◄── stores config in a static ConcurrentHashMap
    │      in the redis-client-injection module
    │
    ▼
Infinispan PersistenceManagerImpl (later, during cache start)
    │  Reads <store class="...RedisStoreConfigurationBuilder">
    │  with <property name="connection">default</property>
    │  Instantiates RedisNonBlockingStore, calls start()
    │
    ▼
RedisNonBlockingStore.start(InitializationContext)
    │  Reads "connection" from store properties
    │  RedisClientConfig config = RedisConnectionRegistry.get("default")
    │  ◄── reads from the SAME static map (shared classloader)
    │
    │  this.jedis = config.createUnifiedJedis()
```

This works because:

1. **Shared classloader.** Both `org.wildfly.extension.redis` (subsystem module) and `org.wildfly.redis.store` (store module) declare a dependency on `org.wildfly.extension.redis.injection`. JBoss Modules loads `RedisConnectionRegistry` exactly once — from the injection module's classloader — so both modules see the same static `ConcurrentHashMap`.

2. **Boot ordering.** The `redis-client` subsystem services start during the subsystem boot phase. Infinispan caches start afterward (they depend on the cache container configuration services, which are processed after extension services). By the time `RedisNonBlockingStore.start()` runs, `RedisConnectionRegistry` is already populated.

3. **Fallback.** If the `connection` property is not set or the registry lookup fails, the store falls back to reading `cluster-nodes` and `password` directly from store properties — the same system property expressions used by the subsystem (e.g., `${redis.cluster.nodes:127.0.0.1:6379}`).

### Trade-offs vs. the HotRod pattern

| Aspect | HotRod pattern | Static registry (this feature pack) |
|--------|---------------|-------------------------------------|
| MSC dependency graph | Formal — the store's service depends on `RemoteCacheContainer` | Informal — relies on boot ordering |
| Service lifecycle | Managed by MSC (start/stop/restart) | Static map, unregistered on subsystem removal |
| Requires WildFly extension changes | Yes — new `StoreResourceRegistration` enum entry + registrar class in the Infinispan extension | No — uses the generic `<store class="...">` mechanism |
| Configuration duplication | None — the store's `remote-cache-container` attribute references the service | None — the store's `connection` property references the subsystem config by name |
| Compile-time type safety | Full — the registrar knows the builder type | None — properties are strings |

The static registry is a pragmatic approach: it avoids modifying WildFly's Infinispan extension while still sharing the connection configuration between the subsystem and the store.

---

## Project Structure

```
wildfly-redis-feature-pack/
├── redis-client/
│   ├── injection/          CDI qualifier, config, portable extension, connection registry
│   ├── subsystem/          WildFly extension and subsystem implementation
│   └── store/              Custom Infinispan NonBlockingStore backed by Redis
├── redis-client-feature-pack/  Galleon feature pack (layers, JBoss modules)
├── redis-client-testsuite/     Integration tests (Arquillian + Testcontainers)
└── redis-client-example/       Sample application demonstrating both features
```

## License

Apache License, Version 2.0
