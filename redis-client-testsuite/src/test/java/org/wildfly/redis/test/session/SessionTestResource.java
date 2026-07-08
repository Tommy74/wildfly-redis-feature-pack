/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.redis.test.session;

import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/session")
public class SessionTestResource {

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
}
