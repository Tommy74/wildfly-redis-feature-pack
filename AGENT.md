# AGENT.md - WildFly Redis Feature Pack

## Project Overview

**WildFly Redis Feature Pack** is a Galleon feature pack that extends WildFly application server with Redis integration capabilities. It provides two main features:

1. **Redis Client Subsystem** (`redis-client`) - Manages Redis connection pools and enables CDI injection of Jedis `UnifiedJedis` clients into Jakarta EE applications
2. **Custom Infinispan Cache Store** (`redis-store`) - A `NonBlockingStore` implementation that persists Infinispan cache entries to Redis

### Key Characteristics

- **Version**: 1.0.0-SNAPSHOT
- **License**: Apache License 2.0
- **Java Version**: 17+
- **WildFly Version**: 41.0.0.Beta1
- **Redis Client**: Jedis 5.2.0
- **Build Tool**: Maven 3.9+

## Project Structure

```
wildfly-redis-feature-pack/
├── redis-client/                    # Core implementation modules
│   ├── injection/                   # CDI portable extension and connection registry
│   │   └── src/main/java/org/wildfly/extension/redis/injection/
│   │       ├── RedisBeanRegistry.java
│   │       ├── RedisClientConfig.java
│   │       ├── RedisConnection.java          # CDI qualifier
│   │       ├── RedisConnectionLiteral.java
│   │       ├── RedisConnectionRegistry.java  # Static registry (shared)
│   │       └── RedisPortableExtension.java   # CDI extension
│   │
│   ├── subsystem/                   # WildFly extension and subsystem
│   │   └── src/main/java/org/wildfly/extension/redis/
│   │       ├── RedisExtension.java           # Entry point
│   │       ├── RedisSubsystemRegistrar.java
│   │       ├── RedisSubsystemSchema.java
│   │       ├── RedisSubsystemModel.java
│   │       ├── RedisCapabilities.java
│   │       ├── RedisConnectionProviderRegistrar.java
│   │       ├── RedisConnectionServiceConfigurator.java
│   │       ├── deployment/
│   │       │   ├── RedisAttachments.java
│   │       │   ├── RedisDependencyProcessor.java
│   │       │   └── RedisDeploymentProcessor.java
│   │       └── _private/
│   │           └── RedisLogger.java
│   │
│   └── store/                       # Custom Infinispan store
│       └── src/main/java/org/wildfly/redis/store/
│           ├── RedisNonBlockingStore.java
│           └── RedisStoreConfigurationBuilder.java
│
├── redis-client-feature-pack/       # Galleon feature pack
│   ├── wildfly-feature-pack-build.xml
│   └── src/main/resources/
│       ├── feature_groups/
│       │   ├── redis-client-ssl.xml
│       │   └── redis-sockets.xml
│       ├── layers/
│       │   └── standalone/
│       │       ├── redis-client/layer-spec.xml
│       │       └── redis-store/layer-spec.xml
│       └── modules/system/layers/base/
│           ├── org/wildfly/extension/redis/
│           │   ├── injection/main/module.xml
│           │   └── main/module.xml
│           ├── org/wildfly/redis/store/main/module.xml
│           └── redis/clients/jedis/main/module.xml
│
├── redis-client-testsuite/          # Integration tests
│   └── src/test/java/org/wildfly/redis/test/
│       ├── RedisCustomStoreIT.java
│       ├── RedisSessionClusteringIT.java
│       ├── RedisSingleNodeIT.java
│       ├── RedisSocketBindingIT.java
│       └── RedisSubsystemIT.java
│
└── redis-client-example/            # Sample application
    └── src/main/java/org/wildfly/redis/example/
        ├── RedisApplication.java
        ├── RedisResource.java
        └── SessionResource.java
```

## Architecture Deep Dive

### The Static Registry Pattern

**Problem**: Infinispan cache stores run entirely within Infinispan's runtime and have no access to WildFly's MSC service container. The store needs a `RedisClientConfig` managed by the WildFly subsystem, but cannot look it up through WildFly's service layer.

**Solution**: A static connection registry (`RedisConnectionRegistry`) shared across JBoss Modules classloaders:

```
WildFly Boot
    │
    ▼
RedisConnectionServiceConfigurator (redis-client subsystem)
    │  MSC service starts, creates RedisClientConfig
    │  RedisConnectionRegistry.register("default", config)
    │  ◄── Stores in static ConcurrentHashMap
    │
    ▼
Infinispan PersistenceManagerImpl (during cache start)
    │  Instantiates RedisNonBlockingStore, calls start()
    │
    ▼
RedisNonBlockingStore.start(InitializationContext)
    │  RedisClientConfig config = RedisConnectionRegistry.get("default")
    │  ◄── Reads from SAME static map (shared classloader)
    │  this.jedis = config.createUnifiedJedis()
```

**Why it works**:
1. Both `org.wildfly.extension.redis` (subsystem) and `org.wildfly.redis.store` (store) depend on `org.wildfly.extension.redis.injection`
2. JBoss Modules loads `RedisConnectionRegistry` once from the injection module's classloader
3. Both modules see the same static `ConcurrentHashMap`
4. Boot ordering ensures subsystem services start before Infinispan caches

### CDI Integration

The `RedisPortableExtension` dynamically registers CDI beans for each configured Redis connection:

```java
void afterBeanDiscovery(@Observes AfterBeanDiscovery abd) {
    for (Map.Entry<String, RedisClientConfig> entry : configs.entrySet()) {
        String name = entry.getKey();
        RedisClientConfig config = entry.getValue();
        abd.addBean()
            .types(UnifiedJedis.class)
            .qualifiers(new RedisConnectionLiteral(name))
            .scope(Dependent.class)
            .produceWith(instance -> pools.computeIfAbsent(name, 
                k -> config.createUnifiedJedis()))
            .disposeWith((jedis, instance) -> { });
    }
}
```

Applications inject Redis clients using the `@RedisConnection` qualifier:

```java
@Inject
@RedisConnection("default")
private UnifiedJedis jedis;
```

### Subsystem Configuration

The `redis-client` subsystem supports:

| Attribute | Type | Default | Description |
|-----------|------|---------|-------------|
| `name` | string | (required) | Connection name for CDI injection |
| `cluster-nodes` | string | none | Comma-separated `host:port` pairs |
| `outbound-socket-bindings` | string | none | Space-separated socket binding names |
| `password` | string | none | Authentication password |
| `ssl` | boolean | false | Enable SSL/TLS |
| `ssl-context` | string | none | Elytron `client-ssl-context` reference |
| `connection-timeout` | int | 2000 | Connection timeout (ms) |
| `max-pool-size` | int | 8 | Maximum pool connections |
| `min-idle` | int | 0 | Minimum idle connections |

### Galleon Layers

| Layer | Description | Dependencies |
|-------|-------------|--------------|
| `redis-client` | Adds redis-client subsystem for CDI injection | `cdi`, `elytron` (optional) |
| `redis-store` | Adds custom Infinispan store module | `redis-client` |

## Development Workflow

### Prerequisites

```bash
# Install Java 17+
java -version

# Install Maven 3.9+
mvn -version

# Install Podman (for Redis and integration tests)
podman --version

# Start Podman socket (Fedora/RHEL)
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

### Building the Project

```bash
# Full build with tests
mvn clean install -Denforcer.skip

# Skip tests
mvn clean install -DskipTests -Denforcer.skip

# Build specific module
cd redis-client/subsystem
mvn clean install -Denforcer.skip
```

### Running Integration Tests

```bash
# All integration tests
cd redis-client-testsuite
mvn clean verify -Denforcer.skip

# Specific test
mvn clean verify -Dit.test=RedisSingleNodeIT -Denforcer.skip
```

### Testing the Example Application

```bash
# 1. Start Redis
podman run --rm -it --name redis -p 6379:6379 redis:7-alpine

# 2. Build feature pack
mvn clean install -DskipTests -Denforcer.skip

# 3. Run example in dev mode
cd redis-client-example
mvn wildfly:dev -Denforcer.skip

# 4. Test Redis operations
curl http://localhost:8080/redis-example/api/redis/set/hello/world
curl http://localhost:8080/redis-example/api/redis/get/hello

# 5. Test session clustering
curl -b cookie.txt -c cookie.txt -X PUT \
  http://localhost:8080/redis-example/api/session/color/BLUE
curl -b cookie.txt -c cookie.txt \
  http://localhost:8080/redis-example/api/session/color
```

### Testing with Redis Cluster

```bash
# Start 3-node Redis cluster
for port in 7000 7001 7002; do
  podman run -d --rm --name "redis-${port}" --network host \
    redis:7-alpine redis-server \
      --port "${port}" \
      --cluster-enabled yes \
      --cluster-config-file "nodes-${port}.conf" \
      --cluster-node-timeout 5000 \
      --appendonly yes
done

# Form cluster
podman exec -it redis-7000 \
  redis-cli --cluster create \
    127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 \
    --cluster-replicas 0 --cluster-yes

# Cleanup
podman rm -f redis-7000 redis-7001 redis-7002
```

### Testing with SSL/TLS

```bash
# Generate certificates
openssl req -x509 -newkey rsa:2048 -keyout ca-key.pem -out ca-cert.pem \
  -days 365 -nodes -subj '/CN=Redis CA'

openssl req -newkey rsa:2048 -keyout server-key.pem -out server-req.pem \
  -nodes -subj '/CN=localhost'

openssl x509 -req -in server-req.pem -CA ca-cert.pem -CAkey ca-key.pem \
  -CAcreateserial -out server-cert.pem -days 365 \
  -extfile <(echo "subjectAltName=DNS:localhost,IP:127.0.0.1")

# Start Redis with TLS
podman run --rm -it --name redis-tls -p 6380:6379 \
  -v ./ca-cert.pem:/tls/ca-cert.pem:ro \
  -v ./server-cert.pem:/tls/server-cert.pem:ro \
  -v ./server-key.pem:/tls/server-key.pem:ro \
  redis:7-alpine redis-server \
    --tls-port 6379 --port 0 \
    --tls-cert-file /tls/server-cert.pem \
    --tls-key-file /tls/server-key.pem \
    --tls-ca-cert-file /tls/ca-cert.pem

# Create truststore
keytool -importcert -alias redis-ca -file ca-cert.pem \
  -keystore redis-truststore.p12 -storetype PKCS12 \
  -storepass changeit -noprompt
```

## Key Implementation Files

### Subsystem Entry Point
- **File**: `redis-client/subsystem/src/main/java/org/wildfly/extension/redis/RedisExtension.java`
- **Purpose**: WildFly extension entry point, registers the subsystem
- **Key Method**: Constructor configures subsystem with current model and schema

### Connection Registry
- **File**: `redis-client/injection/src/main/java/org/wildfly/extension/redis/injection/RedisConnectionRegistry.java`
- **Purpose**: Static registry shared between subsystem and store
- **Key Methods**: `register()`, `get()`, `unregister()`

### CDI Portable Extension
- **File**: `redis-client/injection/src/main/java/org/wildfly/extension/redis/injection/RedisPortableExtension.java`
- **Purpose**: Dynamically registers CDI beans for Redis connections
- **Key Methods**: `afterBeanDiscovery()`, `beforeShutdown()`

### Custom Store Implementation
- **File**: `redis-client/store/src/main/java/org/wildfly/redis/store/RedisNonBlockingStore.java`
- **Purpose**: Infinispan NonBlockingStore backed by Redis
- **Key Methods**: `start()`, `load()`, `write()`, `delete()`

### Deployment Processor
- **File**: `redis-client/subsystem/src/main/java/org/wildfly/extension/redis/deployment/RedisDeploymentProcessor.java`
- **Purpose**: Adds module dependencies to deployments
- **Key Method**: `deploy()` - adds `org.wildfly.extension.redis.injection` dependency

## Configuration Examples

### Basic Configuration (CLI)

```bash
# Add Redis connection
/subsystem=redis-client/redis-connection=default:add(
  cluster-nodes=127.0.0.1:6379
)

# With password
/subsystem=redis-client/redis-connection=secure:add(
  cluster-nodes=127.0.0.1:6379,
  password=mypassword
)
```

### Production Configuration with SSL

```bash
# Socket binding
/socket-binding-group=standard-sockets/remote-destination-outbound-socket-binding=redis-server:add(
  host=${redis.host:localhost},
  port=${redis.port:6380}
)

# Elytron SSL
/subsystem=elytron/key-store=redis-truststore:add(
  credential-reference={clear-text=changeit},
  path=redis-truststore.p12,
  relative-to=jboss.server.config.dir,
  type=PKCS12
)
/subsystem=elytron/trust-manager=redis-trust-manager:add(
  key-store=redis-truststore
)
/subsystem=elytron/client-ssl-context=redis-ssl-context:add(
  trust-manager=redis-trust-manager
)

# Redis connection with SSL
/subsystem=redis-client/redis-connection=default:add(
  outbound-socket-bindings=[redis-server],
  ssl-context=redis-ssl-context
)
```

### Infinispan Cache with Redis Store

```bash
# Create cache container
/subsystem=infinispan/cache-container=mycontainer:add()

# Add cache with Redis store
/subsystem=infinispan/cache-container=mycontainer/local-cache=mycache:add(
  modules=[org.wildfly.redis.store]
)
/subsystem=infinispan/cache-container=mycontainer/local-cache=mycache/store=custom:add(
  class=org.wildfly.redis.store.RedisStoreConfigurationBuilder,
  passivation=false,
  preload=false,
  purge=false,
  shared=true,
  properties={connection=default}
)
```

## Testing Strategy

### Unit Tests
- Located in each module's `src/test/java`
- Test individual components in isolation
- Run with: `mvn test`

### Integration Tests
- Located in `redis-client-testsuite`
- Use Arquillian + Testcontainers
- Test full WildFly deployment scenarios
- Key test classes:
  - `RedisSingleNodeIT` - Basic Redis operations
  - `RedisSessionClusteringIT` - Session replication
  - `RedisCustomStoreIT` - Infinispan store functionality
  - `RedisSocketBindingIT` - Socket binding configuration
  - `RedisSubsystemIT` - Subsystem parsing and configuration

### Test Execution

```bash
# All tests
mvn clean verify -Denforcer.skip

# Specific test
mvn clean verify -Dit.test=RedisSingleNodeIT -Denforcer.skip

# Skip tests
mvn clean install -DskipTests -Denforcer.skip
```

## Common Development Tasks

### Adding a New Subsystem Attribute

1. Update `RedisSubsystemSchema` with new attribute definition
2. Add attribute to `RedisConnectionProviderRegistrar`
3. Update `RedisConnectionServiceConfigurator` to read and use the attribute
4. Update `RedisClientConfig` if needed
5. Update schema XSD files in `src/main/resources/schema/`
6. Add tests in `RedisSubsystemTestCase`

### Adding a New Store Property

1. Update `RedisStoreConfigurationBuilder` to accept the property
2. Modify `RedisNonBlockingStore` to use the property
3. Update documentation in README.md
4. Add integration test

### Debugging

```bash
# Enable debug logging
export MAVEN_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"
mvn wildfly:dev -Denforcer.skip

# Attach debugger to port 5005

# View WildFly logs
tail -f redis-client-example/target/server/standalone/log/server.log
```

## Troubleshooting

### Common Issues

**Issue**: Tests fail with "Cannot connect to Podman socket"
```bash
# Solution: Start Podman socket
systemctl --user start podman.socket
export DOCKER_HOST=unix:///run/user/$UID/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

**Issue**: "Module org.wildfly.extension.redis.injection not found"
```bash
# Solution: Rebuild feature pack
mvn clean install -DskipTests -Denforcer.skip
```

**Issue**: Redis connection timeout
```bash
# Solution: Check Redis is running
podman ps | grep redis
podman logs redis

# Test connection
redis-cli -h 127.0.0.1 -p 6379 ping
```

**Issue**: SSL handshake failure
```bash
# Solution: Verify truststore and certificates
keytool -list -keystore redis-truststore.p12 -storepass changeit

# Check Redis TLS configuration
podman logs redis-tls
```

## Release Process

1. Update version in all `pom.xml` files
2. Update CHANGELOG.md
3. Run full test suite: `mvn clean verify -Denforcer.skip`
4. Create Git tag: `git tag -a v1.0.0 -m "Release 1.0.0"`
5. Build release artifacts: `mvn clean install -DskipTests -Denforcer.skip`
6. Deploy to repository: `mvn deploy`

## Resources

- **WildFly Documentation**: https://docs.wildfly.org/
- **Jedis Documentation**: https://github.com/redis/jedis
- **Infinispan Documentation**: https://infinispan.org/docs/
- **Galleon Documentation**: https://docs.wildfly.org/galleon/
- **Redis Documentation**: https://redis.io/docs/

## Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make changes and add tests
4. Run tests: `mvn clean verify -Denforcer.skip`
5. Commit changes: `git commit -am "Add my feature"`
6. Push to branch: `git push origin feature/my-feature`
7. Create Pull Request

## License

Apache License, Version 2.0 - See LICENSE file for details
