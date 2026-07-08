/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.example;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.Map;

@Path("/session")
public class SessionResource {

    @PUT
    @Path("/{key}/{value}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response setAttribute(@PathParam("key") String key, @PathParam("value") String value, @Context HttpServletRequest request) {
        HttpSession session = request.getSession(true);
        session.setAttribute(key, value);
        return Response.ok(Map.of("sessionId", session.getId(), "key", key, "value", value)).build();
    }

    @GET
    @Path("/{key}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAttribute(@PathParam("key") String key, @Context HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Response.status(Response.Status.NOT_FOUND).entity(Map.of("error", "no session")).build();
        }
        Object value = session.getAttribute(key);
        Map<String, String> result = new HashMap<>();
        result.put("sessionId", session.getId());
        result.put("key", key);
        result.put("value", value != null ? value.toString() : null);
        return Response.ok(result).build();
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getSessionInfo(@Context HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Response.ok(Map.of("active", false)).build();
        }
        return Response.ok(Map.of(
                "active", true,
                "sessionId", session.getId(),
                "creationTime", session.getCreationTime(),
                "lastAccessedTime", session.getLastAccessedTime(),
                "maxInactiveInterval", session.getMaxInactiveInterval()
        )).build();
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Response invalidateSession(@Context HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Response.ok(Map.of("invalidated", false, "reason", "no session")).build();
        }
        String id = session.getId();
        session.invalidate();
        return Response.ok(Map.of("invalidated", true, "sessionId", id)).build();
    }
}
