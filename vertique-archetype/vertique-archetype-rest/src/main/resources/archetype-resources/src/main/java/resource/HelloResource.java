package ${package}.resource;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/hello")
public class HelloResource {

    @Inject
    public HelloResource() {}

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public HelloResponse hello() {
        return new HelloResponse("Hello, Vertique!");
    }

    public record HelloResponse(String message) {}
}
