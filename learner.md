# learner.md — How This Application Is Built (Step by Step)

This document is a tutorial-style walkthrough of how the **Deployable Microservices** system is constructed from an empty repository to a fully containerized, mesh-ready, Helm-deployable set of services. It has been updated to match the actual implementation in this repo (not just the original plan) — where reality diverged from the plan, that's called out explicitly with the reason.

> Looking for how the bare Gradle/Spring Boot project skeleton itself gets created — before any entity, repository, or controller is written? That's `bootstrap.md`, not here. This document picks up right where `bootstrap.md` leaves off.

## Use Case Recap

An online bookstore slice with two independently-scalable Spring Boot services, an API Gateway, and a React UI:

- **product-service** (`services/product-service`, package `com.bookstore.product`) — product/inventory CRUD
- **order-service** (`services/order-service`, package `com.bookstore.order`) — order lifecycle; calls product-service to validate/reserve stock
- **api-gateway** (`services/api-gateway`, package `com.bookstore.gateway`) — single entry point, routes to both services
- **ui** (`ui/`) — React SPA, talks only to the gateway

## Prerequisites

| Tool | Version actually used | Purpose |
|---|---|---|
| JDK | 21 | Spring Boot 3 runtime |
| Gradle | 8.14.3 (via wrapper) | Build tool |
| Docker | 24+ | Container builds |
| Node.js | 22 (20 LTS also fine) | UI build |
| kubectl | matching cluster | K8s CLI |
| Helm | 3.15.x | Chart packaging/install |
| istioctl | matching Istio version | Mesh install/verification |
| kind or minikube | latest | Local Kubernetes cluster |

**Framework versions pinned:** Spring Boot **3.2.5** + Spring Cloud **2023.0.1** (the gateway module). This matters for one concrete reason: Spring Boot 3.2.x still bundles Flyway 9.x, which has Postgres support built into `flyway-core` directly. Spring Boot 3.3+ jumps to Flyway 10, which split Postgres support into a separate `flyway-database-postgresql` artifact — adding that dependency against Boot 3.2.5 fails dependency resolution (`Could not find org.flywaydb:flyway-database-postgresql:.`, no version, because the BOM doesn't manage it). If you upgrade the Boot version later, revisit this dependency.

## Step 1 — Bootstrap the Gradle Multi-Module Project

1. Generate the wrapper from a locally installed Gradle (`gradle wrapper --gradle-version 8.14.3`). If your network blocks `services.gradle.org`/GitHub release redirects (sandboxed CI runners sometimes do), generate with `--no-validate-url` — the wrapper files are still correct, only the immediate distribution-URL reachability check is skipped; the first real `./gradlew` invocation on a normal machine still downloads and verifies the distribution.
2. `settings.gradle` declares the three Java modules:
   ```groovy
   rootProject.name = 'deployable-microservices'
   include 'services:product-service'
   include 'services:order-service'
   include 'services:api-gateway'
   ```
3. Root `build.gradle` (Groovy DSL) applies `org.springframework.boot` and `io.spring.dependency-management` as `apply false` at the root, then in `subprojects {}`: Java 21 toolchain, and two test tasks — `test` (excludes JUnit tag `integration`) and a custom `integrationTest` task (includes only tag `integration`), with `check` depending on both. See **Step 6** for why this split exists; it was added after hitting a real constraint, not planned upfront.

**Why first:** everything else (services, tests, Docker builds) depends on the module graph being correct before any business code is written.

## Step 2 — Build `product-service`

1. `build.gradle`: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `spring-boot-starter-actuator`, `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core` only (see version note above).
2. Domain: `Product` entity (id, name, description, price, stockQuantity, `@Version` for optimistic locking) with a `reserve(quantity)` method that throws `InsufficientStockException` rather than letting stock go negative.
3. Persistence: `ProductRepository extends JpaRepository`.
4. Migration: Flyway `V1__init_product_table.sql` with a `CHECK` constraint on `stock_quantity >= 0` as a second line of defense below the application-level check — `hibernate.ddl-auto: validate`, never `update`, beyond a throwaway prototype.
5. Service layer: `ProductService.reserveStock(id, qty)`, used later by order-service via HTTP.
6. API: `ProductController` — `GET /products`, `GET /products/{id}`, `POST /products`, `PATCH /products/{id}/reserve`; a `@RestControllerAdvice` maps `ProductNotFoundException` → 404 and `InsufficientStockException` → 409.
7. Tests: `ProductServiceTest` (unit, Mockito) plus `ProductControllerIT` (`@Tag("integration")`, Testcontainers Postgres + `MockMvc`) — see Step 6 for the tagging convention.
8. `Dockerfile` — multi-stage: `eclipse-temurin:21-jdk-jammy` build stage → `eclipse-temurin:21-jre-jammy` runtime stage, non-root user (uid 1000). **No Docker `HEALTHCHECK`** — deliberately omitted because Kubernetes readiness/liveness probes (wired in the Helm chart) already cover this, and adding one would require installing `curl`/`wget` into the runtime image for no benefit outside `docker run` debugging.

Validate locally: `./gradlew :services:product-service:test` (fast) before wiring up anything downstream.

## Step 3 — Build `order-service`

1. Dependencies beyond the product-service set: `spring-webflux` + `io.projectreactor.netty:reactor-netty` directly (**not** the full `spring-boot-starter-webflux`) — this gets you `WebClient` without pulling in Spring Boot's reactive-server auto-configuration, which would otherwise compete with the servlet/Tomcat stack this service uses for its own REST API. Also `spring-boot-starter-aop` (required for Resilience4j's annotation-based aspects) and `io.github.resilience4j:resilience4j-spring-boot3`.
2. Domain: `Order` (id, productId, quantity, `OrderStatus` enum PENDING/CONFIRMED/REJECTED, createdAt).
3. Client: `ProductServiceClient` wraps `WebClient`, annotated `@Retry(name="productService")` + `@CircuitBreaker(name="productService", fallbackMethod=...)`. Resilience4j config in `application.yml`: retry only on `IOException`/`WebClientRequestException` (network-level failures), and the circuit breaker's `ignore-exceptions` excludes `ProductClientException` so a legitimate business rejection (409 stock conflict, 404 not found) never counts against the breaker's failure rate — only real product-service degradation should trip it.
4. **A transaction-scope bug caught during development, not in the original plan:** the first draft of `OrderService.create()` wrapped the whole method — including the outbound WebClient call to product-service — in one `@Transactional`. That holds a DB connection open for the full duration of a network round-trip, which is exactly the connection-pool-exhaustion-under-load failure mode called out in `deployable.md`'s production checklist. Fixed by removing the method-level `@Transactional` and letting each `orderRepository.save()` be its own short transaction (the default for a single `CrudRepository` call), with the network call happening in between, transaction-free.
5. Tests: `OrderServiceTest` (unit, mocked `ProductServiceClient`); `ProductServiceClientTest` (stands up an in-process WireMock server — no Docker needed — to prove the client's own HTTP/error-mapping logic); `OrderControllerIT` (`@Tag("integration")`, Testcontainers Postgres + WireMock, full slice).
6. `Dockerfile` — same pattern as product-service.

## Step 4 — Build `api-gateway`

1. `build.gradle` adds the Spring Cloud BOM (`spring-cloud-dependencies:2023.0.1`) via `dependencyManagement { imports { mavenBom ... } }` and `spring-cloud-starter-gateway`.
2. Routes declared in `application.yml` exactly as planned (`/api/products/**` → product-service, `/api/orders/**` → order-service, both `StripPrefix=1`), with the downstream URIs read from `PRODUCT_SERVICE_URI`/`ORDER_SERVICE_URI` env vars (defaulting to `localhost` ports for local dev).
3. `CorsWebFilter` bean reading allowed origins from `ui.allowed-origins` (comma-separated string binds to `List<String>` via Spring's relaxed binding), and a `GlobalFilter` (`RequestLoggingFilter`, `Ordered.HIGHEST_PRECEDENCE`) logging method/URI/status for every routed request.
4. Tests: `RouteConfigurationIT` — boots the real gateway (`RANDOM_PORT`) with two in-process WireMock servers standing in for product-service/order-service, overrides the route URIs via `@DynamicPropertySource`, and asserts requests land on the right stub. No Docker needed here either — WireMock is in-process.
5. `Dockerfile` — same multi-stage pattern.

**Note on Istio overlap:** the gateway owns application-level concerns (path routing to logical services, CORS, request logging). Istio owns mesh-level concerns (mTLS, connection-pool limits, outlier detection, ingress TLS termination). They are complementary, not redundant — see `deployable.md`.

## Step 5 — Build the UI

1. `npm create vite@latest ui -- --template react-ts`; added `vitest`, `@testing-library/react`, `@testing-library/user-event`, `jsdom`, and `msw` as dev dependencies.
2. `ProductList` (`GET /products` via the gateway) and `OrderForm` (`POST /orders`), wired together in `App.tsx`; both call a small `api/client.ts` wrapper, never `fetch` directly from components.
3. **Runtime config, not just build-time env vars — a real gap found during the build:** Vite bakes `VITE_API_BASE_URL` into the JS bundle at *build* time. But the whole point of Helm's `values-<env>.yaml` pattern is build-once-deploy-many — baking the API URL at build time would force a separate image build per environment, defeating that. Fixed with a small runtime-config layer: `index.html` loads `/config.js` before the app bundle; `docker-entrypoint.d/50-runtime-config.sh` (an nginx entrypoint hook, **not** a replacement `ENTRYPOINT` — it must not `exec` anything itself, nginx's own entrypoint script runs the rest) regenerates that file from the `API_BASE_URL` env var at container start; `api/client.ts` reads `window.__ENV__?.API_BASE_URL` first, falling back to the Vite build-time var, falling back to a hardcoded local default. One image, any environment.
4. Base image is `nginxinc/nginx-unprivileged:1.27-alpine` (listens on 8080 as uid 101 by default), not plain `nginx:alpine` — needed to run as non-root without extra Dockerfile plumbing, matching the production hardening checklist in `deployable.md`.
5. Tests: Vitest + React Testing Library + MSW (intercepts at the network layer, not by mocking `fetch` directly) — 7 tests across `ProductList.test.tsx` and `OrderForm.test.tsx`, covering success, empty state, error state, disabled-when-out-of-stock, and the order-placement success/conflict/cancel paths. `npm run test` and `npm run build` both run clean.

## Step 6 — Testing Split: Unit vs. Integration (a real constraint, not a plan detail)

Testcontainers-based tests need a working Docker daemon **and** outbound access to a container registry to pull the Postgres image. Neither is guaranteed in every environment (a locked-down CI runner or sandboxed dev container may have neither). Rather than let that block fast local iteration, every module's `build.gradle` (via the root `subprojects {}` block) defines:

- `test` — JUnit 5, **excludes** tests tagged `@Tag("integration")`. No Docker required. This is what you run in the inner dev loop.
- `integrationTest` — **includes only** `@Tag("integration")` tests (the Testcontainers-backed `*IT` classes). Requires Docker + registry access.
- `check` depends on both, so a full `./gradlew build` still exercises everything in an environment that has Docker.

Not everything that looks like an "integration test" needs this tag: `ProductServiceClientTest` and `RouteConfigurationIT` use WireMock's in-process server (no container, no Docker), so they run as fast, un-tagged tests despite testing real HTTP behavior. Only the Testcontainers-backed classes (`ProductControllerIT`, `OrderControllerIT`) carry `@Tag("integration")`. See `tests.md` for how this maps onto the CI pipeline.

## Step 7 — Local End-to-End Validation (Pre-Kubernetes)

Before touching Helm/Istio, prove the four processes work together locally (Postgres + product-service + order-service + api-gateway + `npm run dev` for the UI, or a `docker-compose.yml` wiring the built images together). This isolates "does my code work" from "does my K8s/Istio config work" — don't debug both at once.

## Step 8 — Containerize and Publish

Each service's Dockerfile builds from the **repository root** as build context, not from the service's own subdirectory — a multi-module Gradle project needs `settings.gradle`, the root `build.gradle`, and all three `services/*` directories present to resolve the module graph, even though only one module's `bootJar` is actually built and copied into the final image:

```
docker build -f services/product-service/Dockerfile -t <registry>/product-service:<git-sha> .
docker build -f services/order-service/Dockerfile   -t <registry>/order-service:<git-sha>   .
docker build -f services/api-gateway/Dockerfile      -t <registry>/api-gateway:<git-sha>     .
docker build -f ui/Dockerfile                        -t <registry>/ui:<git-sha>              ui
```
(The UI image is the one exception — it's a separate npm project, so its own directory is a self-contained build context.)

Tag images immutably as `<registry>/<service>:<git-sha>`; push to your registry (GHCR/ECR/etc.).

## Step 9 — Write Helm Charts

Structure actually used — flat under `charts/`, not nested inside the umbrella chart's own directory:

```
charts/product-service/    charts/order-service/
charts/api-gateway/         charts/ui/
charts/deployable-microservices/   # umbrella chart
```

Each service chart has `Deployment`, `Service`, `HorizontalPodAutoscaler`, `PodDisruptionBudget`, `ServiceAccount`, a `DestinationRule` (mesh resilience settings), and — for `product-service`/`order-service` — an optional dev-only `Secret` template gated by `database.createSecret` (defaults `false`; only `values-dev.yaml` turns it on). `ui` additionally owns the single shared Istio `Gateway` and the **one** `VirtualService` that routes both `/api` (to api-gateway) and `/` (to ui) — see Step 10 for why that's one resource, not two. The umbrella chart (`charts/deployable-microservices`) declares all four as local `file://` dependencies plus the Bitnami `postgresql` chart twice, aliased `product-db`/`order-db`, and layers `values-dev.yaml` / `values-staging.yaml` / `values-prod.yaml` on top of its own `values.yaml` defaults. Full detail, including the exact deploy commands, is in `deployable.md`.

**A naming decision worth calling out:** `Service`/`Deployment` names are fixed to the chart name (e.g. `product-service`), not prefixed with the Helm release name as `helm create` scaffolds by default. This is what lets `order-service` reach `http://product-service` and the gateway reach `http://order-service` with a fixed, predictable hostname instead of having to template the caller's config with the callee's release name. The tradeoff, made deliberately: this assumes **one release per namespace/environment** (stated as this project's deployment model in `deployable.md`) — a second concurrent release of the same chart in the same namespace would collide on resource names.

**A second real gotcha:** aliasing the same chart (`postgresql`) twice in one umbrella `Chart.yaml` (`product-db`, `order-db`) does **not** automatically change what that subchart calls itself internally — without an explicit `fullnameOverride` per alias in the umbrella's `values.yaml`, both aliased Postgres instances would render the same `<release>-postgresql` Service name and collide. Fixed by setting `product-db.fullnameOverride: product-db-postgresql` / `order-db.fullnameOverride: order-db-postgresql`, matching exactly what `product-service`/`order-service`'s own `database.host` default expects.

## Step 10 — Wire Istio

All of this is Helm-templated (no separate `kubectl apply -f istio/` step) — `PeerAuthentication` (mTLS `STRICT`) and per-service `AuthorizationPolicy` (least-privilege: api-gateway → {product,order}-service, order-service → product-service, ingress-gateway → {api-gateway, ui}) plus a baseline `NetworkPolicy` live in the **umbrella chart's own** `templates/` (cross-cutting, not any one service's concern), gated by `istioMeshPolicy.enabled`. Each service chart owns its own `DestinationRule` (connection-pool limits, outlier detection). The `ui` chart owns the shared `Gateway` resource and the single combined `VirtualService`.

**Why one `VirtualService`, not two:** the original sketch had `api-gateway` own a `/api`-matching `VirtualService` and `ui` own a `/`-matching one, both bound to the same host. Istio does not guarantee match-order precedence across *separate* `VirtualService` objects bound to the same host — relying on that merge behavior for `/api` to correctly take precedence over a catch-all `/` is a known Istio pitfall, not a guaranteed contract. Fixed by consolidating both routes into one `VirtualService` (owned by `ui`, since it already owns the `Gateway`), with the more specific `/api` match listed first.

`istioMeshPolicy.ingressGatewayPrincipal` (the SPIFFE identity `AuthorizationPolicy` trusts for inbound traffic) defaults to the standard `istioctl` demo/default profile's ingress gateway `ServiceAccount` — verify this against your actual cluster (`kubectl get sa -n istio-system`) before relying on it in a non-default install.

## Step 11 — Validate Charts Without a Live Cluster

`helm lint` and `helm template` on every chart (plus the umbrella chart with each `values-<env>.yaml` layered on) caught both gotchas above — the `VirtualService` merge-order issue and the Postgres alias collision — purely from static rendering, before a single manifest touched a real cluster. Treat this as a first-class, cheap validation step; see `tests.md`'s new "Helm chart validation" stage.

## Step 12 — Deploy Locally to kind/minikube

`istioctl install`, confirm sidecar injection is enabled on the target namespace, then `helm dependency update` (requires `helm repo add bitnami https://charts.bitnami.com/bitnami` first) and `helm upgrade --install` the umbrella chart. Confirm sidecars are injected (`kubectl get pods` shows `2/2`), then smoke-test through the Istio ingress gateway's external IP/port. Exact commands are in `deployable.md`.

## Step 13 — Wire Up CI (`.github/workflows/ci.yml`)

Added after Step 12, once local deployment worked, so CI could validate the exact same things this walkthrough validated manually: `backend-unit-test` (`./gradlew test`, no Docker), `backend-integration-test` (`./gradlew integrationTest`, needs Docker — GitHub-hosted runners have it), `ui-test` (`npm run test && npm run build`), `helm-validate` (`helm lint`/`helm template` across all three environment overlays), and `docker-build` (builds all four images, `push: false` — proves the Dockerfiles work without needing registry credentials in CI yet). This is also the first place the `docker build`/`helm dependency update` steps that couldn't be run in this repo's original sandboxed build environment (see `deployable.md`'s validation note) actually get exercised end to end — watch its first run on this branch rather than assuming it's clean.

## Where to Go Next

- Read `bootstrap.md` for how the bare project skeleton (before Step 1 here) gets created.
- Read `deployable.md` for the full production deployment and operational runbook.
- Read `tests.md` for how testing should be layered from unit tests through post-production monitoring.
