package com.bookstore.order.web;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full slice: real Postgres (Testcontainers) + WireMock standing in for product-service,
 * proving OrderController -> OrderService -> ProductServiceClient -> repository end to end.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class OrderControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("order_db")
            .withUsername("order_user")
            .withPassword("order_pass");

    static WireMockServer productServiceStub;

    @BeforeAll
    static void startWireMock() {
        productServiceStub = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        productServiceStub.start();
    }

    @AfterAll
    static void stopWireMock() {
        productServiceStub.stop();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("product-service.base-url", () -> "http://localhost:" + productServiceStub.port());
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createOrder_confirmedWhenProductServiceReservesStock() throws Exception {
        productServiceStub.stubFor(patch(urlEqualTo("/products/1/reserve"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":1,"name":"Effective Java","description":"desc","price":45.0,"stockQuantity":8}
                                """)));

        mockMvc.perform(post("/orders").contentType("application/json")
                        .content("{\"productId\":1,\"quantity\":2}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", org.hamcrest.Matchers.is("CONFIRMED")));
    }

    @Test
    void createOrder_rejectedWhenProductServiceReturnsConflict() throws Exception {
        productServiceStub.stubFor(patch(urlEqualTo("/products/2/reserve"))
                .willReturn(aResponse().withStatus(409)));

        mockMvc.perform(post("/orders").contentType("application/json")
                        .content("{\"productId\":2,\"quantity\":999}"))
                .andExpect(status().isConflict());
    }

    /**
     * Proves the Resilience4j @Retry actually fires on a 5xx (not just a config-file
     * assertion): product-service fails twice, then succeeds on the 3rd call, matching
     * this service's max-attempts: 3. Requires the real Spring context -- the AOP proxy
     * that makes @Retry/@CircuitBreaker do anything only exists there, not in a plain
     * `new ProductServiceClient(webClient)` unit test.
     */
    @Test
    void createOrder_confirmedAfterTransientFailuresRetried() throws Exception {
        productServiceStub.stubFor(patch(urlEqualTo("/products/3/reserve"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("first-failure-done"));
        productServiceStub.stubFor(patch(urlEqualTo("/products/3/reserve"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("first-failure-done")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-failure-done"));
        productServiceStub.stubFor(patch(urlEqualTo("/products/3/reserve"))
                .inScenario("retry-then-succeed")
                .whenScenarioStateIs("second-failure-done")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":3,"name":"Retry Book","description":"desc","price":20.0,"stockQuantity":4}
                                """)));

        mockMvc.perform(post("/orders").contentType("application/json")
                        .content("{\"productId\":3,\"quantity\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", org.hamcrest.Matchers.is("CONFIRMED")));

        productServiceStub.verify(3, WireMock.patchRequestedFor(urlEqualTo("/products/3/reserve")));
    }

    @Test
    void createOrder_serviceUnavailableWhenProductServiceKeepsFailing() throws Exception {
        productServiceStub.stubFor(patch(urlEqualTo("/products/4/reserve"))
                .willReturn(aResponse().withStatus(503)));

        mockMvc.perform(post("/orders").contentType("application/json")
                        .content("{\"productId\":4,\"quantity\":1}"))
                .andExpect(status().isServiceUnavailable());

        productServiceStub.verify(3, WireMock.patchRequestedFor(urlEqualTo("/products/4/reserve")));
    }
}
