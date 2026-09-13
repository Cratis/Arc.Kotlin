// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package probe;

import example.TaskApplication;
import io.cratis.arc.artifacts.ArcArtifactModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ServiceLoader;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Runs inside each compiled tutorial; the JavaExec timeout also bounds startup and shutdown. */
public final class TutorialProbe {
    private TutorialProbe() {}

    public static void main(String[] args) throws Exception {
        var modules = ServiceLoader.load(ArcArtifactModule.class).stream().map(provider -> provider.type().getName()).toList();
        require(modules.equals(java.util.List.of("io.cratis.arc.generated.TaskApplicationArcArtifactModule")), modules.toString());
        try (var context = SpringApplication.run(TaskApplication.class, "--server.port=0", "--server.address=127.0.0.1")) {
            var port = ((WebServerApplicationContext) context).getWebServer().getPort();
            require(port > 0, "Expected a bound random HTTP port");
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            var mapper = JsonMapper.builder().build();
            var command = send(client, mapper, port, 0);
            var id = command.path("response").path("id").asString();
            UUID.fromString(id);
            verifyEnvelope(mapper, command, 0, id);
            var query = send(client, mapper, port, 1);
            verifyEnvelope(mapper, query, 1, id);
            require(query.path("data").size() == 1, query.toString());
            require(query.path("data").get(0).equals(command.path("response")), query.toString());
            System.out.println("TUTORIAL_HTTP_VERIFIED POST /api/create-task QUERY /api/tasks");
        }
    }

    private static JsonNode send(HttpClient client, JsonMapper mapper, int port, int index) throws Exception {
        var parts = resource("request-" + index + ".txt").strip().split("\n", 4);
        var header = parts[2].split(": ", 2);
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + parts[1]))
            .timeout(Duration.ofSeconds(10)).header(header[0], header[1])
            .method(parts[0], HttpRequest.BodyPublishers.ofString(parts[3])).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        require(response.statusCode() == 200, response.statusCode() + " " + response.body());
        require(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), response.toString());
        System.out.println("TUTORIAL_HTTP_RESPONSE " + parts[0] + " " + parts[1] + " " + response.body());
        return mapper.readTree(response.body());
    }

    private static void verifyEnvelope(JsonMapper mapper, JsonNode actual, int index, String id) throws IOException {
        var correlationId = actual.path("correlationId").asString();
        UUID.fromString(correlationId);
        var expected = mapper.readTree(resource("expected-" + index + ".json")
            .replace("<uuid>", correlationId).replace("<task-id>", id));
        require(expected.equals(actual), "Expected " + expected + " but got " + actual);
    }

    private static String resource(String name) throws IOException {
        try (var stream = TutorialProbe.class.getResourceAsStream("/" + name)) {
            if (stream == null) throw new IOException("Missing tutorial resource " + name);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
