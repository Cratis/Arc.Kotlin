// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.contracts;

import io.cratis.arc.contracts.fixtures.JavaMapMetadataCommand;
import io.cratis.arc.generated.ContractTestsArcArtifactModule;
import io.cratis.arc.metadata.EndpointRouteHelper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(classes = GeneratedModelValidationHostingTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext
final class GeneratedModelValidationHttpJavaContractTest {
    enum Operation { EXECUTE, VALIDATE, QUERY, OBSERVABLE }
    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @ParameterizedTest
    @EnumSource(Operation.class)
    void javaContributedAsyncModelRuleRejectsGeneratedJavaArtifactsOverRealHttp(Operation operation) throws Exception {
        for (boolean rejected : new boolean[] {true, false}) {
            String value = rejected ? "invalid-java" : "valid";
            var module = new ContractTestsArcArtifactModule();
            String path;
            HttpRequest.Builder request;
            String member;
            if (operation == Operation.EXECUTE || operation == Operation.VALIDATE) {
                var handler = module.getCommandHandlers().stream().filter(item -> item.getCommandType() == JavaMapMetadataCommand.class).findFirst().orElseThrow();
                path = EndpointRouteHelper.commandRoute(handler.getMetadata()) + (operation == Operation.VALIDATE ? "/validate" : "");
                request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"strings\":{\"entry\":\"" + value + "\"},\"numbers\":{},\"nested\":{},\"optional\":null}"));
                member = "strings.entry";
            } else {
                String name = operation == Operation.QUERY ? "byId" : "observeJava";
                var performer = module.getQueryPerformers().stream()
                    .filter(item -> item.getFullyQualifiedName().getValue().endsWith(".JavaQueryReadModel." + name)).findFirst().orElseThrow();
                member = operation == Operation.QUERY ? "identifier" : "label";
                path = EndpointRouteHelper.queryRoute(performer.getDescriptor()) + "?" + member + "=" + value
                    + "&waitForFirstResult=true&waitForFirstResultTimeout=2";
                request = HttpRequest.newBuilder(uri(path)).GET();
            }
            var response = http.send(request.timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(rejected ? 400 : 200, response.statusCode(), response.body());
            var envelope = mapper.readTree(response.body());
            assertEquals(!rejected, envelope.path("isSuccess").booleanValue());
            var feedback = envelope.path("validationResults");
            if (rejected) {
                assertFalse(envelope.path("isSuccess").booleanValue());
                assertEquals(1, feedback.size());
                assertEquals(member, feedback.path(0).path("members").path(0).stringValue());
                assertEquals("Java model rejected", feedback.path(0).path("message").stringValue());
                assertEquals("rule", feedback.path(0).path("reason").stringValue());
            } else {
                assertTrue(envelope.path("isSuccess").booleanValue());
                assertEquals(0, feedback.size());
            }
        }
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
}
