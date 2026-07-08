/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.example;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.wildfly.extension.redis.injection.RedisConnection;
import redis.clients.jedis.UnifiedJedis;

@Path("/redis")
@ApplicationScoped
public class RedisResource {

    @Inject
    @RedisConnection("default")
    private UnifiedJedis jedis;

    @GET
    @Path("/set/{key}/{value}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response set(@PathParam("key") String key, @PathParam("value") String value) {
        jedis.set(key, value);
        return Response.ok("OK").build();
    }

    @GET
    @Path("/get/{key}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response get(@PathParam("key") String key) {
        String value = jedis.get(key);
        return Response.ok(value != null ? value : "null").build();
    }

    @DELETE
    @Path("/del/{key}")
    @Produces(MediaType.TEXT_PLAIN)
    public Response del(@PathParam("key") String key) {
        jedis.del(key);
        return Response.ok("OK").build();
    }
}
