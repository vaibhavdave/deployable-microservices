# learner.md — How This Application Is Built (Step by Step)

This document is a tutorial-style walkthrough of how the **Deployable Microservices** system is constructed from an empty repository to a fully containerized, mesh-ready, Helm-deployable set of services. Follow the steps in order — each step builds on the previous one and is independently testable before you move on.

## Use Case Recap

An online bookstore slice with two independently-scalable Spring Boot services, an API Gateway, and a React UI:

- **product-service** — product/inventory CRUD
- **order-service** — order lifecycle; calls product-service to validate/reserve stock
- **api-gateway** — single entry point, routes to both services
- **ui** — React SPA, talks only to the gateway

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| JDK | 21 | Spring Boot 3 runtime |
| Gradle | 8.x (via wrapper, no local install needed) | Build tool |
| Docker | 24+ | Container builds |
| Node.js | 20 LTS | UI build |
| kubectl | matching cluster | K8s CLI |
| Helm | 3.x | Chart packaging/install |
| istioctl | matching Istio version | Mesh install/verification |
| kind or minikube | latest | Local Kubernetes cluster |

## Step 1 — Bootstrap the Gradle Multi-Module Project

1. Initialize git (already done) and create the Gradle wrapper:
   ```
   gradle wrapper --gradle-version 8.8
   ```
2. Create `settings.gradle` declaring the three Java modules:
   ```groovy
   rootProject.name = 'deployable-microservices'
   include 'services:product-service'
   include 'services:order-service'
   include 'services:api-gateway'
   ```
3. Create a root `build.gradle` with shared config: Java 21 toolchain, Spring Boot plugin version, dependency management plugin (`io.spring.dependency-management`), common repositories, and shared test dependencies (JUnit 5, Testcontainers BOM) applied via `subprojects {}`.

**Why first:** everything else (services, tests, Docker builds) depends on the module graph being correct before any business code is written.

## Step 2 — Build `product-service`

Work inside `services/product-service`.

1. `build.gradle`: apply `org.springframework.boot`, add `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, PostgreSQL driver, Flyway.
2. Domain: `Product` entity (id, name, description, price, stockQuantity).
3. Persistence: `ProductRepository extends JpaRepository`.
4. Migration: Flyway `V1__init_product_table.sql` — never rely on Hibernate `ddl-auto=update` beyond local dev.
5. Service layer: `ProductService` with stock-reservation method (`reserveStock(id, qty)`) used later by order-service.
6. API: `ProductController` — `GET /products`, `GET /products/{id}`, `POST /products`, `PATCH /products/{id}/reserve`.
7. Tests: `ProductServiceTest` (unit, Mockito), `ProductControllerIT` (Testcontainers Postgres + `MockMvc`/`WebTestClient`).
8. `Dockerfile` — multi-stage: Gradle build stage → Eclipse Temurin JRE runtime stage, non-root user, `HEALTHCHECK` hitting `/actuator/health`.

Validate locally: `./gradlew :services:product-service:bootRun` and hit the endpoints with curl before moving on.

## Step 3 — Build `order-service`

1. Same module setup as product-service, plus `spring-webflux` (for `WebClient`) and `resilience4j-spring-boot3` for timeouts/retries/circuit breaker on the outbound call.
2. Domain: `Order` (id, productId, quantity, status, createdAt).
3. Client: `ProductServiceClient` wrapping `WebClient`, configured with `product-service.base-url` (resolved via K8s Service DNS name in-cluster, e.g. `http://product-service`).
4. Business rule: creating an order calls `ProductServiceClient.reserveStock()` synchronously; on failure return `409`/`503` with a clear error body — do not silently succeed.
5. Tests: unit tests for `OrderService` with a mocked `ProductServiceClient`; an integration test using `WireMock` to stand in for product-service and assert retry/circuit-breaker behavior on 5xx/timeout.
6. `Dockerfile` — same multi-stage pattern as product-service.

## Step 4 — Build `api-gateway`

1. `build.gradle`: `spring-cloud-starter-gateway` (reactive), `spring-boot-starter-actuator`.
2. Configure routes declaratively in `application.yml`:
   ```yaml
   spring:
     cloud:
       gateway:
         routes:
           - id: product-service
             uri: http://product-service
             predicates: [Path=/api/products/**]
             filters: [StripPrefix=1]
           - id: order-service
             uri: http://order-service
             predicates: [Path=/api/orders/**]
             filters: [StripPrefix=1]
   ```
3. Add a global CORS config (for the UI's origin) and a global request-logging filter.
4. Tests: `RouteConfigurationIT` — spins the gateway up with WireMock stubs for both downstream services and asserts path stripping/routing.
5. `Dockerfile` — same pattern.

**Note on Istio overlap:** the gateway owns application-level concerns (path routing to logical services, CORS, request aggregation later if needed). Istio owns mesh-level concerns (mTLS, retries at the network level, traffic shifting, ingress termination). They are complementary, not redundant — see `deployable.md`.

## Step 5 — Build the UI

1. `npm create vite@latest ui -- --template react-ts`.
2. A `ProductList` page (`GET /api/products`) and an `OrderForm` (`POST /api/orders`), both calling the **gateway** base URL (never call services directly).
3. Environment-driven API base URL (`VITE_API_BASE_URL`) so the same build works across dev/staging/prod.
4. Unit tests with Vitest + React Testing Library.
5. `Dockerfile` — multi-stage: `node:20` build stage → `nginx:alpine` serving the static build, with an `nginx.conf` that proxies `/api/**` if you want same-origin requests, or simply serves the SPA if the gateway is on a separate subdomain.

## Step 6 — Local End-to-End Validation (Pre-Kubernetes)

Before touching Helm/Istio, prove the four processes work together locally (e.g., via `docker-compose.yml` with Postgres, product-service, order-service, api-gateway, ui). This isolates "does my code work" from "does my K8s/Istio config work" — don't debug both at once.

## Step 7 — Containerize and Publish

Tag images as `<registry>/<service>:<git-sha>` (immutable tags, never re-push `latest` to an environment). Push to your registry (GHCR/ECR/etc.).

## Step 8 — Write Helm Charts

One chart per service (`Deployment`, `Service`, `HorizontalPodAutoscaler`, `PodDisruptionBudget`, `ServiceAccount`, `ConfigMap`, Istio `VirtualService`/`DestinationRule`) plus an umbrella chart that declares them (and the Postgres dependency) as dependencies with environment-specific `values-<env>.yaml`. Full detail in `deployable.md`.

## Step 9 — Wire Istio

Enable sidecar injection on the namespace, add `PeerAuthentication` (mTLS `STRICT`), an Istio `Gateway` + `VirtualService` for ingress into `api-gateway` and `ui`, and `DestinationRule`s with connection-pool/outlier-detection settings on `product-service`/`order-service`.

## Step 10 — Deploy Locally to kind/minikube

`helm install` the umbrella chart into a local cluster with Istio installed via `istioctl install`, confirm sidecars are injected (`kubectl get pods` shows `2/2`), and smoke-test through the Istio ingress gateway's external IP/port.

## Where to Go Next

- Read `deployable.md` for the full production deployment and operational runbook.
- Read `tests.md` for how testing should be layered from unit tests through post-production monitoring.
