/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.extension.redis;

import static org.wildfly.extension.redis.RedisCapabilities.REDIS_CLIENT_PROVIDER_CAPABILITY;
import static org.wildfly.extension.redis.RedisConnectionProviderRegistrar.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.network.OutboundSocketBinding;
import org.jboss.dmr.ModelNode;
import redis.clients.jedis.HostAndPort;
import org.wildfly.extension.redis.injection.RedisClientConfig;
import org.wildfly.extension.redis.injection.RedisConnectionRegistry;
import org.wildfly.service.capture.ValueRegistry;
import org.wildfly.subsystem.service.ResourceServiceConfigurator;
import org.wildfly.subsystem.service.ResourceServiceInstaller;
import org.wildfly.subsystem.service.ServiceDependency;
import org.wildfly.subsystem.service.capability.CapabilityServiceInstaller;

public class RedisConnectionServiceConfigurator implements ResourceServiceConfigurator {

    private final ValueRegistry<String, RedisClientConfig> registry;

    RedisConnectionServiceConfigurator(ValueRegistry<String, RedisClientConfig> registry) {
        this.registry = registry;
    }

    @Override
    public ResourceServiceInstaller configure(OperationContext context, ModelNode model) throws OperationFailedException {
        String connectionName = context.getCurrentAddressValue();
        String clusterNodesValue = CLUSTER_NODES.resolveModelAttribute(context, model).asStringOrNull();
        String password = PASSWORD.resolveModelAttribute(context, model).asStringOrNull();
        boolean sslFlag = SSL.resolveModelAttribute(context, model).asBoolean();
        int connectionTimeout = CONNECTION_TIMEOUT.resolveModelAttribute(context, model).asInt();
        int maxPoolSize = MAX_POOL_SIZE.resolveModelAttribute(context, model).asInt();
        int minIdle = MIN_IDLE.resolveModelAttribute(context, model).asInt();

        ServiceDependency<List<OutboundSocketBinding>> socketBindingsDep = OUTBOUND_SOCKET_BINDINGS.resolve(context, model);
        ServiceDependency<SSLContext> sslContextDep = SSL_CONTEXT.resolve(context, model);

        if ((clusterNodesValue == null || clusterNodesValue.isBlank()) && !model.hasDefined("outbound-socket-bindings")) {
            throw new OperationFailedException("Either 'cluster-nodes' or 'outbound-socket-bindings' must be defined");
        }

        Set<HostAndPort> staticClusterNodes = parseClusterNodes(clusterNodesValue);

        Supplier<RedisClientConfig> factory = () -> {
            Set<HostAndPort> clusterNodes;
            List<OutboundSocketBinding> bindings = socketBindingsDep.get();
            if (bindings != null && !bindings.isEmpty()) {
                clusterNodes = new HashSet<>();
                for (OutboundSocketBinding binding : bindings) {
                    clusterNodes.add(new HostAndPort(
                            binding.getUnresolvedDestinationAddress(),
                            binding.getDestinationPort()));
                }
            } else {
                clusterNodes = staticClusterNodes;
            }

            SSLContext sslContext = sslContextDep.get();
            boolean effectiveSsl = sslFlag || (sslContext != null);

            RedisClientConfig config = new RedisClientConfig()
                    .password(password)
                    .ssl(effectiveSsl)
                    .connectionTimeout(connectionTimeout)
                    .maxPoolSize(maxPoolSize)
                    .minIdle(minIdle)
                    .clusterNodes(clusterNodes);

            if (sslContext != null) {
                config.sslSocketFactory(sslContext.getSocketFactory());
            }

            RedisConnectionRegistry.register(connectionName, config);

            return config;
        };

        Consumer<RedisClientConfig> captor = registry.add(context.getCurrentAddressValue());
        ResourceServiceInstaller installer = CapabilityServiceInstaller.builder(REDIS_CLIENT_PROVIDER_CAPABILITY, factory)
                .withCaptor(captor)
                .requires(socketBindingsDep)
                .requires(sslContextDep)
                .asActive()
                .build();
        Consumer<OperationContext> remover = ctx -> {
            registry.remove(ctx.getCurrentAddressValue());
            RedisConnectionRegistry.unregister(ctx.getCurrentAddressValue());
        };
        return new ResourceServiceInstaller() {
            @Override
            public Consumer<OperationContext> install(OperationContext ctx) {
                return installer.install(ctx).andThen(remover);
            }
        };
    }

    private static Set<HostAndPort> parseClusterNodes(String clusterNodesValue) {
        if (clusterNodesValue == null || clusterNodesValue.isBlank()) {
            return new HashSet<>();
        }
        Set<HostAndPort> clusterNodes = new HashSet<>();
        for (String node : clusterNodesValue.split(",")) {
            String trimmed = node.trim();
            int lastColon = trimmed.lastIndexOf(':');
            if (lastColon > 0) {
                String nodeHost = trimmed.substring(0, lastColon);
                int nodePort = Integer.parseInt(trimmed.substring(lastColon + 1));
                clusterNodes.add(new HostAndPort(nodeHost, nodePort));
            }
        }
        return clusterNodes;
    }
}
