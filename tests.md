# tests.md — Testing Strategy (Initial Build → Post-Production)

Testing is layered: each layer catches a different class of defect, and a defect should be caught at the cheapest layer capable of catching it. This document maps the testing pyramid onto the actual services in this repo and onto the delivery pipeline (PR → staging → production → live). Updated after the initial implementation — the unit/integration split described below (§2–3) was forced by a real constraint hit during the build, not just planned in the abstract; see the note there.

## 1. Testing Pyramid Overview

```
        ▲  Production monitoring / synthetic checks (continuous)
        │  Chaos & resilience tests (staging, pre-release)
        │  Performance / load tests (staging, pre-release)
        │  End-to-end tests (staging, in-cluster)
        │  Contract tests (order-service ↔ product-service)
        │  Helm chart validation (lint/template, every PR touching charts/)
        │  Integration tests (per service, Testcontainers — needs Docker)
        ▼  Unit tests (per service, every PR — no Docker required)
```
Lower layers run on every commit and must be fast (seconds); higher layers run less often (per PR, nightly, pre-release) and are allowed to be slower and more expensive.

## 2. Unit Testing (runs on every commit/PR, `./gradlew test` / `npm run test`)

**Scope:** pure business logic and HTTP-client behavior in isolation — no real database, no live downstream service. Note this is broader than "no network at all": tests that stand up an **in-process** WireMock server are still fast, still Docker-free, and run in this tier alongside pure logic tests — only tests needing a real external process (Postgres via Testcontainers) are excluded, see §3.

- `product-service`: `ProductServiceTest` — stock-reservation logic (exact-boundary quantity, insufficient stock throws, missing product throws), creation persists correctly. Mockito-mocked repository.
- `order-service`: `OrderServiceTest` — order-state transitions (confirmed on success, rejected on stock conflict) with a mocked `ProductServiceClient`; `ProductServiceClientTest` — stands up an **in-process WireMock server** (no Docker) to prove the client's own HTTP call, JSON mapping, and 409/404 → `ProductClientException` translation, independent of Resilience4j or Spring wiring.
- `api-gateway`: `RouteConfigurationIT` — despite the `IT` suffix (kept for naming consistency with the per-service convention), this boots the real gateway with **two in-process WireMock stubs** for the downstream services and asserts `/api/products/**` and `/api/orders/**` route and strip-prefix correctly. No Docker needed, so it runs in this fast tier, not gated behind `integrationTest`.
- `ui`: `ProductList.test.tsx` / `OrderForm.test.tsx` — Vitest + React Testing Library + MSW (mocks at the network layer, not by stubbing `fetch` directly), covering render/empty/error states and the order-placement success/conflict/cancel paths. 7 tests total, all passing.

**Tooling:** JUnit 5, Mockito, AssertJ, WireMock (in-process), Vitest, MSW. **Gate:** must pass before merge.

## 3. Integration / Component Testing (`./gradlew integrationTest`, needs Docker)

**A constraint that shaped this split, not just a plan detail:** Testcontainers needs both a working Docker daemon and outbound access to a container registry to pull the Postgres image. Neither is guaranteed everywhere — a sandboxed CI runner or locked-down dev container may have neither (this was true of the environment this repo was first built in). Gating these tests behind their own Gradle task, `integrationTest` (JUnit 5 `@Tag("integration")`, configured in the root `build.gradle`'s `subprojects {}` block), means the fast unit-test loop (§2) never silently breaks in an environment without Docker — it simply skips a class of test it can't run, rather than failing for an unrelated reason. `check` depends on both `test` and `integrationTest`, so a full `./gradlew build`/`check` still exercises everything wherever Docker *is* available.

**Scope:** a single service wired up with its real dependencies where feasible.

- `product-service` / `order-service`: `ProductControllerIT` / `OrderControllerIT`, both `@Tag("integration")` — `@SpringBootTest` + **Testcontainers Postgres** (real Flyway migration, real JPA queries, real transaction boundaries) plus, for `OrderControllerIT`, a WireMock stub standing in for product-service so the full slice (controller → service → client → repository) is proven together. Covers the DB-integration class of bug unit tests can't (constraint violations, migration correctness, query correctness).
- Resilience assertions worth calling out explicitly once a real product-service dependency exists in CI: a 409/insufficient-stock response surfaces as `OrderController` returning 409 (asserted in `OrderControllerIT`); a timeout/5xx should trigger the configured Resilience4j retry then circuit-breaker open — this specific case needs a slow/failing WireMock stub (fixed delay or repeated 5xx) and isn't yet in `OrderControllerIT`; add it before relying on the circuit breaker in production.
- `ui`: MSW-backed component tests already cover the product-list → order-placement flow (§2); a browser-driven E2E pass against a real deployed stack is §6, not this layer.

**Gate:** must pass before merge, in CI with Docker available (Docker-in-Docker or a Docker-enabled runner).

## 4. Helm Chart Validation (every PR touching `charts/`, no cluster needed)

**Added after the fact, not originally planned — because it caught two real bugs before any cluster was involved.** `helm lint` and `helm template` (rendered with every `values-<env>.yaml` overlay layered on) need only the Helm binary and the chart's dependencies vendored locally (`helm dependency update`, which resolves `file://` subchart references without network access) — no Docker, no live cluster, no Istio install. Run this on every PR touching `charts/`:

```
helm dependency update charts/deployable-microservices
helm lint charts/product-service charts/order-service charts/api-gateway charts/ui charts/deployable-microservices
helm template bookstore charts/deployable-microservices -f charts/deployable-microservices/values-dev.yaml
helm template bookstore charts/deployable-microservices -f charts/deployable-microservices/values-staging.yaml
helm template bookstore charts/deployable-microservices -f charts/deployable-microservices/values-prod.yaml
```

What static rendering actually caught during this build, purely from reading the output — proof this layer earns its place rather than being a checkbox:
- Two `VirtualService` objects (one from `ui`, one from `api-gateway`) both bound to the same host, relying on undefined cross-resource match-order for `/api` to beat a catch-all `/` — visible immediately by reading the rendered YAML side by side, long before it would have shown up as a flaky routing bug in a real cluster.
- The Bitnami `postgresql` chart aliased twice (`product-db`, `order-db`) rendering the same Service name for both until `fullnameOverride` was set explicitly — again visible by rendering with `helm template` and diffing the two Postgres Service names.

**Note on the Bitnami dependency specifically:** `helm dependency update` needs `charts.bitnami.com` reachable to resolve the `postgresql` chart; if that's blocked (as it was in this repo's initial build environment), lint/template the four local-file-dependency charts individually plus a temporary local-only umbrella `Chart.yaml` to validate everything except the Postgres subchart itself — not a substitute for validating the real dependency at least once wherever `charts.bitnami.com` is reachable (a normal CI runner or dev machine).

## 5. Contract Testing (every PR touching the shared API)

Because `order-service` depends on `product-service`'s HTTP contract, add consumer-driven contract tests (Spring Cloud Contract or Pact) so a breaking change to `product-service`'s API is caught in `product-service`'s own CI, before it ever reaches a shared environment — not discovered later as an order-service outage.

## 6. End-to-End Testing (staging, in-cluster)

Deploy the full umbrella Helm chart into an ephemeral or shared **staging** namespace with Istio installed, then run a black-box test suite that:

- Hits only the public entry point (Istio ingress → UI / `api-gateway`), never internal service DNS — this proves routing, CORS, and mesh config are all correct together, not just the app code.
- Covers the golden path: browse products → place an order → verify stock decremented.
- Covers cross-service failure: force product-service to reject (out-of-stock fixture) and confirm the UI surfaces a sane error, not a raw 500/stack trace.

**Tooling:** Playwright (UI-driven) plus a REST-level suite (RestAssured/Karate) hitting the gateway directly. Runs on every merge to main and again before a production release.

## 7. Performance / Load Testing (pre-release, staging)

Because scaling is a core requirement here (multiple replicas, not one), load testing must specifically validate the **autoscaling behavior**, not just raw throughput:

- Run k6 (or Gatling) against the gateway with a ramping load profile.
- Confirm HPA scales `product-service`/`order-service` out under load and back in after — watch `kubectl get hpa -w` during the run.
- Establish baseline latency/error-rate numbers per replica count; these become the SLOs alerted on in production.
- Test the DB connection pool under max-replica load — this is the most common place autoscaling "works" at the pod level but the database quietly falls over.

## 8. Resilience / Chaos Testing (pre-release, staging)

Validate that the mesh-level resilience configured in `deployable.md` actually does something under failure, not just that it's present in YAML:

- Istio fault injection: inject latency/HTTP errors into `product-service` traffic via `VirtualService` and confirm `order-service`'s Resilience4j retry/circuit-breaker reacts as designed.
- Pod-kill test: delete a running `product-service` pod under load and confirm zero user-visible errors (this is the direct proof that "≥2 replicas + readiness probes + PDB" actually delivers availability, not just satisfies a checklist).
- Node-drain simulation: cordon/drain a node and confirm the PDB prevents all replicas of a service going down simultaneously.

## 9. Security Testing (every PR + pre-release)

- **Dependency scanning:** OWASP Dependency-Check or Snyk on every Gradle build (Java deps) and `npm audit`/Snyk on the UI — fail on known-exploitable CVEs.
- **Container image scanning:** Trivy against every built image before it's allowed to be pushed to a shared registry.
- **DAST:** a lightweight authenticated scan (OWASP ZAP baseline) against the staging gateway endpoint pre-release.
- **mTLS verification:** an explicit staging check that a request to `product-service` bypassing the mesh (plain HTTP, no sidecar) is rejected — proves `PeerAuthentication: STRICT` is actually enforced, not just configured.

## 10. Mapping Tests to the Pipeline

| Stage | Tests run | Blocking? |
|---|---|---|
| Local dev (`./gradlew test`, `npm run test`) | Unit (no Docker required) | Yes, before pushing |
| Every PR (CI) | Unit + `./gradlew integrationTest` (Docker) + Helm lint/template (if `charts/` touched) + Contract + dependency/image scan | Yes, merge blocked on failure |
| Merge to main | + End-to-end (staging deploy) | Yes, auto-revert or hold promotion on failure |
| Pre-production release | + Performance/load + Chaos + DAST | Yes, manual release gate |
| Production | Smoke test immediately post-deploy; synthetic monitoring continuously | Yes — failed smoke test triggers the rollback runbook in `deployable.md` |

## 11. Post-Production: Testing Doesn't Stop at Deploy

Treat production observability as the outermost, continuously-running test layer:

- **Synthetic monitoring:** a scheduled job that exercises the golden path (browse → order) against production every few minutes from outside the cluster, alerting on failure exactly like a test failure.
- **SLOs and error budgets:** define latency/error-rate SLOs per service (informed by the load-test baselines from §7); alert on burn rate, not just instantaneous breaches.
- **Progressive delivery as a test:** when shifting traffic to a new version via Istio weights (per `deployable.md` §9), treat each traffic-shift step as a live test gate — hold or roll back if error rate/latency regresses at 5%/25% before proceeding to 100%.
- **Post-incident tests:** every production incident should produce a new automated test (unit, integration, or chaos) that would have caught it — the test suite should grow from real failures, not just imagined ones.

## 12. Test Data Management

- Unit/integration tests use in-memory fixtures or Testcontainers-provisioned databases — never point tests at a shared environment's real data.
- Staging is seeded with realistic-but-synthetic data via a repeatable seed script (not copied production data — avoids both PII exposure and non-reproducible test runs).
- Contract and E2E tests use fixed, versioned fixtures so failures are diagnosable (a flaky fixture that changes over time hides real regressions).

## 13. Definition of Done (testing lens)

A change is releasable to production only when:
- [ ] `./gradlew test` (unit, all modules) and `npm run test` (ui) pass.
- [ ] `./gradlew integrationTest` passes wherever Docker is available (CI; not guaranteed in every dev sandbox — see §3).
- [ ] `helm lint` + `helm template` (every `values-<env>.yaml`) pass for any change touching `charts/`.
- [ ] Contract tests pass for any change to a shared API.
- [ ] E2E suite passes against a staging deploy of the actual release artifact (same image tags going to prod).
- [ ] No HIGH/CRITICAL findings from dependency/image scans.
- [ ] Load test confirms HPA scale-out/in behaves as expected for this release if the change touches resource usage patterns.
- [ ] Rollback path (Helm revision + DB migration down-path) is confirmed viable before deploy.
