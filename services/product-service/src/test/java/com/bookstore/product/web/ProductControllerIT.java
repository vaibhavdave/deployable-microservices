package com.bookstore.product.web;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ProductControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("product_db")
            .withUsername("product_user")
            .withPassword("product_pass");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createThenFetchProduct() throws Exception {
        String requestBody = """
                {"name":"Effective Java","description":"Joshua Bloch","price":45.00,"stockQuantity":10}
                """;

        mockMvc.perform(post("/products").contentType("application/json").content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is("Effective Java")))
                .andExpect(jsonPath("$.stockQuantity", is(10)));

        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"));
    }

    @Test
    void reserveStock_conflictWhenExceedsAvailable() throws Exception {
        String requestBody = """
                {"name":"Clean Architecture","description":"Robert Martin","price":40.00,"stockQuantity":2}
                """;

        String response = mockMvc.perform(post("/products").contentType("application/json").content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        Long id = com.jayway.jsonpath.JsonPath.read(response, "$.id").toString().equals("null")
                ? null
                : Long.valueOf(com.jayway.jsonpath.JsonPath.read(response, "$.id").toString());

        mockMvc.perform(patch("/products/{id}/reserve", id)
                        .contentType("application/json")
                        .content("{\"quantity\":5}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getById_notFoundReturns404() throws Exception {
        mockMvc.perform(get("/products/{id}", 99999))
                .andExpect(status().isNotFound());
    }
}
