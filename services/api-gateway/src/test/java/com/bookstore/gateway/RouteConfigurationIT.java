package com.bookstore.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteConfigurationIT {

    static WireMockServer productServiceStub;
    static WireMockServer orderServiceStub;

    @BeforeAll
    static void startStubs() {
        productServiceStub = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        orderServiceStub = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        productServiceStub.start();
        orderServiceStub.start();
    }

    @AfterAll
    static void stopStubs() {
        productServiceStub.stop();
        orderServiceStub.stop();
    }

    @DynamicPropertySource
    static void routeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.gateway.routes[0].id", () -> "product-service");
        registry.add("spring.cloud.gateway.routes[0].uri", () -> "http://localhost:" + productServiceStub.port());
        registry.add("spring.cloud.gateway.routes[0].predicates[0]", () -> "Path=/api/products/**");
        registry.add("spring.cloud.gateway.routes[0].filters[0]", () -> "StripPrefix=1");

        registry.add("spring.cloud.gateway.routes[1].id", () -> "order-service");
        registry.add("spring.cloud.gateway.routes[1].uri", () -> "http://localhost:" + orderServiceStub.port());
        registry.add("spring.cloud.gateway.routes[1].predicates[0]", () -> "Path=/api/orders/**");
        registry.add("spring.cloud.gateway.routes[1].filters[0]", () -> "StripPrefix=1");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void routesProductRequestsToProductService() {
        productServiceStub.stubFor(get(urlEqualTo("/products"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("[]")));

        webTestClient.get().uri("/api/products")
                .exchange()
                .expectStatus().isOk();

        productServiceStub.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(urlEqualTo("/products")));
    }

    @Test
    void routesOrderRequestsToOrderService() {
        orderServiceStub.stubFor(post(urlEqualTo("/orders"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody("{}")));

        webTestClient.post().uri("/api/orders")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"productId\":1,\"quantity\":1}")
                .exchange()
                .expectStatus().isCreated();

        orderServiceStub.verify(com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/orders")));
    }
}
