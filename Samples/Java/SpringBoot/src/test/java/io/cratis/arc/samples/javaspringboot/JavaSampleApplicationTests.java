// Copyright (c) Cratis. All rights reserved.
// Licensed under the MIT license. See LICENSE file in the project root for full license information.

package io.cratis.arc.samples.javaspringboot;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cratis.arc.artifacts.ArcArtifactModule;
import io.cratis.arc.springboot.ArcArtifactModules;
import java.util.ServiceLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** End-to-end verification of generated Arc hosting for the Java sample. */
@SpringBootTest
@AutoConfigureMockMvc
public class JavaSampleApplicationTests {
    private static final String CREATE_ROUTE = "/api/create-task";
    private static final String COMPLETE_ROUTE = "/api/complete-task";
    private static final String BY_ID_ROUTE = "/api/tasks/by-id";
    private static final String LIST_ROUTE = "/api/tasks";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskRepository repository;

    @Autowired
    private ArcArtifactModules artifactModules;

    @BeforeEach
    public void resetRepository() {
        repository.clear();
    }

    @Test
    public void generatedModuleIsServiceLoadableAndCommandReturnsATypedResponse() throws Exception {
        var loaded = ServiceLoader.load(ArcArtifactModule.class).stream().map(ServiceLoader.Provider::get).toList();
        assertEquals(
            java.util.List.of("JavaSpringBootSampleArcArtifactModule"),
            loaded.stream().map(module -> module.getClass().getSimpleName()).toList());
        assertEquals(
            loaded.stream().map(Object::getClass).toList(),
            artifactModules.getModules().stream().map(Object::getClass).toList());

        execute(post(CREATE_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Write Java sample\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.id").isString())
            .andExpect(jsonPath("$.response.title").value("Write Java sample"))
            .andExpect(jsonPath("$.validationResults", hasSize(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize(0)));
    }

    @Test
    public void completeCommandConsumesProvidedTaskAndReturnsTheUpdatedTypedResponse() throws Exception {
        var created = repository.create("Exercise provide flow");

        execute(post(COMPLETE_ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"taskId\":\"" + created.id() + "\"}"))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.response.id").value(created.id()))
            .andExpect(jsonPath("$.response.title").value("Exercise provide flow"))
            .andExpect(jsonPath("$.response.completed").value(true))
            .andExpect(jsonPath("$.validationResults", hasSize(0)))
            .andExpect(jsonPath("$.exceptionMessages", hasSize(0)));

        assertEquals(new TaskView(created.id(), created.title(), true), repository.byId(created.id()));
    }

    @Test
    public void completeCommandReturnsProvideValidationForAMissingTask() throws Exception {
        execute(post(COMPLETE_ROUTE)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"taskId\":\"missing-task\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("taskId"))
            .andExpect(jsonPath("$.validationResults[0].message").value("The task does not exist."))
            .andExpect(jsonPath("$.exceptionMessages", hasSize(0)));

        assertTrue(repository.all().isEmpty());
    }

    @Test
    public void validateRejectsInvalidCommandsWithoutChangingState() throws Exception {
        execute(post(CREATE_ROUTE + "/validate").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.isSuccess").value(false))
            .andExpect(jsonPath("$.validationResults[0].members[0]").value("title"))
            .andExpect(jsonPath("$.validationResults[0].message").value("A task title is required."));
        assertTrue(repository.all().isEmpty());
    }

    @Test
    public void getByIdAndRfcQueryListReturnTypedQueryEnvelopes() throws Exception {
        var command = execute(
            post(CREATE_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Read generated query\"}"))
            .andExpect(status().isOk())
            .andReturn();
        var id = objectMapper.readTree(command.getResponse().getContentAsString()).path("response").path("id").asText();

        execute(get(BY_ID_ROUTE).queryParam("id", id))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data.id").value(id))
            .andExpect(jsonPath("$.data.title").value("Read generated query"))
            .andExpect(jsonPath("$.data.completed").value(false))
            .andExpect(jsonPath("$.paging").exists());

        execute(query(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{\"arguments\":{}}"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.isSuccess").value(true))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    public void malformedCommandAndQueryBodiesProduceSafeValidationEnvelopes() throws Exception {
        execute(post(CREATE_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(jsonPath("$.exceptionMessages", hasSize(0)));

        execute(query(LIST_ROUTE).contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.validationResults[0].reason").value("malformedRequest"))
            .andExpect(jsonPath("$.exceptionMessages", hasSize(0)));
    }

    private ResultActions execute(MockHttpServletRequestBuilder requestBuilder) throws Exception {
        var initial = mockMvc.perform(requestBuilder).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(initial));
    }

    private MockHttpServletRequestBuilder query(String route) {
        return request(HttpMethod.valueOf("QUERY"), route);
    }
}
