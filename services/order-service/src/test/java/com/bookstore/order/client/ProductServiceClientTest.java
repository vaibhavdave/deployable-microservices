package com.bookstore.order.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.web.reactive.function.client.WebClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stands product-service up as an in-process WireMock stub (no Docker/Testcontainers needed)
 * to prove ProductServiceClient's own HTTP handling and error mapping in isolation.
 */
class ProductServiceClientTest {

    private WireMockServer wireMockServer;
    private ProductServiceClient client;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        WebClient webClient = WebClient.builder().baseUrl("http://localhost:" + wireMockServer.port()).build();
        client = new ProductServiceClient(webClient);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    @Test
    void reserveStock_returnsProductOnSuccess() {
        wireMockServer.stubFor(patch(urlEqualTo("/products/1/reserve"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"id":1,"name":"Effective Java","description":"desc","price":45.0,"stockQuantity":8}
                                """)));

        ProductDto result = client.reserveStock(1L, 2);

        assertThat(result.stockQuantity()).isEqualTo(8);
    }

    @Test
    void reserveStock_throwsStockConflictOn409() {
        wireMockServer.stubFor(patch(urlEqualTo("/products/1/reserve"))
                .willReturn(aResponse().withStatus(409)));

        assertThatThrownBy(() -> client.reserveStock(1L, 999))
                .isInstanceOf(ProductClientException.class)
                .matches(ex -> ((ProductClientException) ex).isStockConflict());
    }

    @Test
    void reserveStock_throwsNotFoundOn404() {
        wireMockServer.stubFor(patch(urlEqualTo("/products/1/reserve"))
                .willReturn(aResponse().withStatus(404)));

        assertThatThrownBy(() -> client.reserveStock(1L, 1))
                .isInstanceOf(ProductClientException.class)
                .matches(ex -> !((ProductClientException) ex).isStockConflict());
    }
}
