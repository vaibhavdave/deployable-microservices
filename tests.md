# tests.md — Testing Strategy (Initial Build → Post-Production)

Testing is layered: each layer catches a different class of defect, and a defect should be caught at the cheapest layer capable of catching it. This document maps the testing pyramid onto the actual services in this repo and onto the delivery pipeline (PR → staging → production → live).

## 1. Testing Pyramid Overview

```
        ▲  Production monitoring / synthetic checks (continuous)
        │  Chaos & resilience tests (staging, pre-release)
        │  Performance / load tests (staging, pre-release)
        │  End-to-end tests (staging, in-cluster)
        │  Contract tests (order-service ↔ product-service)
        │  Integration tests (per service, Testcontainers)
        ▼  Unit tests (per service, every PR)
```
Lower layers run on every commit and must be fast (seconds); higher layers run less often (per PR, nightly, pre-release) and are allowed to be slower and more expensive.

## 2. Unit Testing (runs on every commit/PR)

**Scope:** pure business logic in isolation — no Spring context, no network, no DB.

- `product-service`: `ProductService` stock-reservation logic (insufficient stock, exact-boundary quantity, concurrent-looking updates), price validation.
- `order-service`: `OrderService` order-state transitions; the outbound call to product-service is mocked (Mockito) — this test proves order-service's own logic, not the network call.
- `api-gateway`: route-predicate configuration parsing where practical; most gateway behavior is better proven at the integration layer below.
- `ui`: component tests with Vitest + React Testing Library (renders, form validation, empty/error states) — no real network calls, mock the fetch layer.

**Tooling:** JUnit 5, Mockito, AssertJ. **Gate:** must pass before merge; target ≥80% line coverage on service/domain packages (not a vanity 100% — don't test getters/setters).

## 3. Integration / Component Testing (every PR)

**Scope:** a single service wired up with its real dependencies where feasible.

- `product-service` / `order-service`: `@SpringBootTest` + **Testcontainers Postgres** — real Flyway migration runs, real JPA queries, real transaction boundaries. Covers the DB-integration class of bug unit tests can't (constraint violations, migration correctness, query correctness).
- `order-service` → `product-service` call: use **WireMock** to stand in for product-service and assert:
  - happy path (stock reserved) produces a confirmed order,
  - a 409/insufficient-stock response is surfaced correctly to the caller,
  - a timeout/5xx triggers the configured Resilience4j retry then circuit-breaker open, not a silent failure.
- `api-gateway`: boot the gateway with WireMock stubs for both downstream services; assert path stripping, routing, and CORS headers.
- `ui`: integration tests against a mocked API layer (MSW — Mock Service Worker) covering the full product-list → order-placement flow within the SPA.

**Gate:** must pass before merge. Runs in CI with Docker-in-Docker for Testcontainers.

## 4. Contract Testing (every PR touching the shared API)

Because `order-service` depends on `product-service`'s HTTP contract, add consumer-driven contract tests (Spring Cloud Contract or Pact) so a breaking change to `product-service`'s API is caught in `product-service`'s own CI, before it ever reaches a shared environment — not discovered later as an order-service outage.

## 5. End-to-End Testing (staging, in-cluster)

Deploy the full umbrella Helm chart into an ephemeral or shared **staging** namespace with Istio installed, then run a black-box test suite that:

- Hits only the public entry point (Istio ingress → UI / `api-gateway`), never internal service DNS — this proves routing, CORS, and mesh config are all correct together, not just the app code.
- Covers the golden path: browse products → place an order → verify stock decremented.
- Covers cross-service failure: force product-service to reject (out-of-stock fixture) and confirm the UI surfaces a sane error, not a raw 500/stack trace.

**Tooling:** Playwright (UI-driven) plus a REST-level suite (RestAssured/Karate) hitting the gateway directly. Runs on every merge to main and again before a production release.

## 6. Performance / Load Testing (pre-release, staging)

Because scaling is a core requirement here (multiple replicas, not one), load testing must specifically validate the **autoscaling behavior**, not just raw throughput:

- Run k6 (or Gatling) against the gateway with a ramping load profile.
- Confirm HPA scales `product-service`/`order-service` out under load and back in after — watch `kubectl get hpa -w` during the run.
- Establish baseline latency/error-rate numbers per replica count; these become the SLOs alerted on in production.
- Test the DB connection pool under max-replica load — this is the most common place autoscaling "works" at the pod level but the database quietly falls over.

## 7. Resilience / Chaos Testing (pre-release, staging)

Validate that the mesh-level resilience configured in `deployable.md` actually does something under failure, not just that it's present in YAML:

- Istio fault injection: inject latency/HTTP errors into `product-service` traffic via `VirtualService` and confirm `order-service`'s Resilience4j retry/circuit-breaker reacts as designed.
- Pod-kill test: delete a running `product-service` pod under load and confirm zero user-visible errors (this is the direct proof that "≥2 replicas + readiness probes + PDB" actually delivers availability, not just satisfies a checklist).
- Node-drain simulation: cordon/drain a node and confirm the PDB prevents all replicas of a service going down simultaneously.

## 8. Security Testing (every PR + pre-release)

- **Dependency scanning:** OWASP Dependency-Check or Snyk on every Gradle build (Java deps) and `npm audit`/Snyk on the UI — fail on known-exploitable CVEs.
- **Container image scanning:** Trivy against every built image before it's allowed to be pushed to a shared registry.
- **DAST:** a lightweight authenticated scan (OWASP ZAP baseline) against the staging gateway endpoint pre-release.
- **mTLS verification:** an explicit staging check that a request to `product-service` bypassing the mesh (plain HTTP, no sidecar) is rejected — proves `PeerAuthentication: STRICT` is actually enforced, not just configured.

## 9. Mapping Tests to the Pipeline

| Stage | Tests run | Blocking? |
|---|---|---|
| Local dev (`./gradlew test`) | Unit | Yes, before pushing |
| Every PR (CI) | Unit + Integration + Contract + dependency/image scan | Yes, merge blocked on failure |
| Merge to main | + End-to-end (staging deploy) | Yes, auto-revert or hold promotion on failure |
| Pre-production release | + Performance/load + Chaos + DAST | Yes, manual release gate |
| Production | Smoke test immediately post-deploy; synthetic monitoring continuously | Yes — failed smoke test triggers the rollback runbook in `deployable.md` |

## 10. Post-Production: Testing Doesn't Stop at Deploy

Treat production observability as the outermost, continuously-running test layer:

- **Synthetic monitoring:** a scheduled job that exercises the golden path (browse → order) against production every few minutes from outside the cluster, alerting on failure exactly like a test failure.
- **SLOs and error budgets:** define latency/error-rate SLOs per service (informed by the load-test baselines from §6); alert on burn rate, not just instantaneous breaches.
- **Progressive delivery as a test:** when shifting traffic to a new version via Istio weights (per `deployable.md` §9), treat each traffic-shift step as a live test gate — hold or roll back if error rate/latency regresses at 5%/25% before proceeding to 100%.
- **Post-incident tests:** every production incident should produce a new automated test (unit, integration, or chaos) that would have caught it — the test suite should grow from real failures, not just imagined ones.

## 11. Test Data Management

- Unit/integration tests use in-memory fixtures or Testcontainers-provisioned databases — never point tests at a shared environment's real data.
- Staging is seeded with realistic-but-synthetic data via a repeatable seed script (not copied production data — avoids both PII exposure and non-reproducible test runs).
- Contract and E2E tests use fixed, versioned fixtures so failures are diagnosable (a flaky fixture that changes over time hides real regressions).

## 12. Definition of Done (testing lens)

A change is releasable to production only when:
- [ ] Unit + integration tests pass for every touched service.
- [ ] Contract tests pass for any change to a shared API.
- [ ] E2E suite passes against a staging deploy of the actual release artifact (same image tags going to prod).
- [ ] No HIGH/CRITICAL findings from dependency/image scans.
- [ ] Load test confirms HPA scale-out/in behaves as expected for this release if the change touches resource usage patterns.
- [ ] Rollback path (Helm revision + DB migration down-path) is confirmed viable before deploy.
