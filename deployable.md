# deployable.md — Deployment Guide (Local → Staging → Production)

This document is a step-by-step runbook for deploying the Deployable Microservices system, plus the production hardening checklist that must be satisfied before any real traffic hits it. Updated after the initial implementation to reflect what was actually built (chart layout, commands, gotchas) rather than just the original plan.

> **Validation note:** the environment this was built in had no outbound access to Docker Hub or `charts.bitnami.com` (registry/chart-repo domains outside its network allowlist), so `docker build` and `helm dependency update` for the Bitnami Postgres dependency could not be exercised end-to-end there. Everything Helm-side *was* validated with `helm lint` and `helm template` (including every `values-<env>.yaml` overlay) against locally-vendored chart dependencies, which is how the two gotchas in §4 were actually caught. `.github/workflows/ci.yml` now runs `docker build` for all four images and `helm dependency update` (against the real Bitnami repo) on every push/PR — GitHub-hosted runners have normal internet access, so this closes that gap going forward. Its first run against this branch is still the first real validation of those two things; watch it before treating this as fully proven.

## 1. Environments

| Environment | Cluster | Purpose | Istio | Data |
|---|---|---|---|---|
| local | kind/minikube | Dev inner-loop | installed via `istioctl install` | ephemeral Postgres |
| staging | shared/cloud K8s namespace | Pre-prod validation, load/chaos tests | mesh + mTLS STRICT | seeded, resettable |
| production | dedicated cloud K8s | Real traffic | mesh + mTLS STRICT | persistent, backed up |

Namespaces are isolated per environment (`bookstore-dev`, `bookstore-staging`, `bookstore-prod`), each with Istio sidecar injection enabled via the `istio-injection=enabled` label.

## 2. Prerequisites Before Any Deploy

- Kubernetes cluster reachable via `kubectl` context.
- Istio installed on the cluster (`istioctl install --set profile=default -y`) and verified (`istioctl verify-install`).
- Helm 3 installed locally / in CI runner.
- Container registry reachable, with `imagePullSecrets` configured in the target namespace (or workload identity if using a cloud-native registry).
- DNS + TLS certificate provisioned for the Istio ingress gateway's external hostname (staging/prod only).
- Secrets management decided (see §6) — **never** commit real secrets to the chart's `values.yaml`.

## 3. Build & Publish Images

1. Build via Gradle: `./gradlew check` (runs `test` + `integrationTest`, i.e. unit and Testcontainers/WireMock suites — a failing test blocks the image build; requires Docker for `integrationTest`, see `tests.md`).
2. Build the image per service. **All three Java services build from the repository root as context**, not from their own subdirectory — the Dockerfiles' first stage needs `settings.gradle`, the root `build.gradle`, and all `services/*` module sources to resolve the multi-module Gradle project, even though only one module's `bootJar` ends up in the final image:
   ```
   docker build -f services/product-service/Dockerfile -t <registry>/product-service:<git-sha> .
   docker build -f services/order-service/Dockerfile   -t <registry>/order-service:<git-sha>   .
   docker build -f services/api-gateway/Dockerfile      -t <registry>/api-gateway:<git-sha>     .
   docker build -f ui/Dockerfile                        -t <registry>/ui:<git-sha>              ui
   ```
   The UI is the one exception — it's a standalone npm project, so `ui/` alone is its own build context.
3. Push all four images. **Tag immutably with the git SHA** — this is what makes rollback a one-line `helm upgrade` with the previous tag, not a rebuild.
4. Scan every image before it's allowed into staging/prod (Trivy or equivalent) — fail the pipeline on HIGH/CRITICAL CVEs.

## 4. Helm Chart Layout

Actual layout — service charts are flat under `charts/`, siblings of the umbrella chart, not nested inside it:

```
charts/deployable-microservices/     # umbrella chart
  Chart.yaml                         # dependencies: product-service, order-service (file://),
                                      #   api-gateway, ui (file://), postgresql aliased x2 (Bitnami)
  values.yaml                        # shared defaults, safe-by-default (createSecret: false, etc.)
  values-dev.yaml                    # local convenience overrides (createSecret: true, no persistence)
  values-staging.yaml                # TLS on, real hostname, existingSecretName
  values-prod.yaml                   # TLS on, min 3 replicas, in-cluster Postgres disabled
  templates/
    peerauthentication.yaml          # mTLS STRICT, namespace-wide
    authorizationpolicy.yaml         # least-privilege per-service ALLOW rules
    networkpolicy.yaml               # defense-in-depth, namespace + istio-system only
charts/product-service/
  templates/
    deployment.yaml    # fixed Service/Deployment name = chart name (see note below)
    service.yaml
    hpa.yaml
    pdb.yaml
    serviceaccount.yaml
    secret.yaml         # gated by database.createSecret, false by default (dev-only convenience)
    destinationrule.yaml
charts/order-service/    (same shape)
charts/api-gateway/       (same shape, no database/secret; env-configured routes to product/order)
charts/ui/                (same shape, plus the shared Gateway + the one combined VirtualService)
```

There is no `configmap.yaml` — env vars are set directly in each `deployment.yaml` from Helm values, which was simpler than a separate ConfigMap for this few a number of settings. There is no per-service `virtualservice.yaml` on `product-service`/`order-service`/`api-gateway`: only `ui` renders a `VirtualService`, and it owns *both* the `/api` and `/` routes in one resource — see the note below for why.

Each service's `values.yaml` exposes at minimum: `image.repository`, `image.tag`, `replicaCount` (default **2**, never 1), `resources.requests/limits`, `autoscaling.minReplicas`/`maxReplicas`/`targetCPUUtilizationPercentage`, `pdb.minAvailable`.

**Naming note:** `Service`/`Deployment` names are fixed to the chart name (`product-service`, `order-service`, `api-gateway`, `ui`) rather than prefixed with the Helm release name. This gives predictable, fixed in-cluster DNS for service-to-service calls (`order-service` reaches product-service at `http://product-service`, matching `PRODUCT_SERVICE_BASE_URL`'s default). It assumes **one release per namespace/environment** — this project's deployment model, per §1 — a second concurrent release of this chart in the same namespace would collide on resource names.

**Bitnami Postgres aliasing gotcha:** the umbrella chart declares the `postgresql` chart twice, aliased `product-db` and `order-db`, for database-per-service. Aliasing alone does **not** change the rendered resource names inside that subchart — both aliased instances would otherwise render the identical `<release>-postgresql` Service name and collide. `values.yaml` sets `product-db.fullnameOverride: product-db-postgresql` and `order-db.fullnameOverride: order-db-postgresql` explicitly, matching each service's own `database.host` default. If you rename either override, update the matching service's `database.host` too.

**Single combined `VirtualService`:** Istio does not guarantee route-match precedence across two *separate* `VirtualService` objects bound to the same host — a `/`-matching one from `ui` and a `/api`-matching one from `api-gateway` would rely on undefined merge order for `/api` to correctly win over the catch-all. Both routes are declared in one `VirtualService` (owned by `ui`, which also owns the `Gateway`), with `/api` listed before `/`.

## 5. Deploy Commands

`helm dependency update` needs the Bitnami repo added once per machine (`helm repo add bitnami https://charts.bitnami.com/bitnami`) before it can resolve the `postgresql` dependency; the two local `file://` service charts resolve without network access. Run this after any change to a subchart or to `Chart.yaml`:
```
helm dependency update charts/deployable-microservices
```

Local/dev (`values.yaml` is always loaded automatically as the chart's defaults — only the environment overlay needs to be passed explicitly):
```
kubectl create namespace bookstore-dev
kubectl label namespace bookstore-dev istio-injection=enabled
helm upgrade --install bookstore charts/deployable-microservices \
  -n bookstore-dev -f charts/deployable-microservices/values-dev.yaml
```

Staging/production (same shape, different values file and namespace, run from CI with an approval gate for prod):
```
helm upgrade --install bookstore charts/deployable-microservices \
  -n bookstore-prod -f charts/deployable-microservices/values-prod.yaml \
  --set product-service.image.tag=<git-sha> \
  --set order-service.image.tag=<git-sha> \
  --set api-gateway.image.tag=<git-sha> \
  --set ui.image.tag=<git-sha> \
  --atomic --timeout 5m
```
`--atomic` auto-rolls back the release if the upgrade fails health checks — never deploy to prod without it. Production also expects `product-service.database.host`/`order-service.database.host` in `values-prod.yaml` to point at a real managed Postgres instance — the in-cluster Bitnami chart is disabled there (`product-db.enabled: false` / `order-db.enabled: false`); the placeholder hostnames in `values-prod.yaml` must be replaced before the first real prod deploy.

## 6. Secrets & Configuration

- Non-sensitive config → Helm `values-<env>.yaml` → plain env vars set directly on each container in `deployment.yaml` (no separate `ConfigMap` resource — with this few settings per service, a `ConfigMap` would be an extra indirection without benefit; revisit if the per-service config surface grows significantly).
- Sensitive config (DB credentials, API keys) → **never** in `values.yaml`. Use Kubernetes `Secret` objects created out-of-band, or better, a secrets manager integration (Sealed Secrets, External Secrets Operator against AWS Secrets Manager/Vault/GCP Secret Manager). Charts reference secrets by name via `existingSecretName`, and only create one themselves as a dev-only convenience gated by `database.createSecret` (false by default, see §4).
- Database credentials are per-service (product DB creds only known to product-service, order DB creds only to order-service) — reinforces the database-per-service boundary.

## 7. Database Migrations

- Flyway runs as part of each service's startup (`spring.flyway.enabled=true`), gated by a K8s `initContainer` or Flyway's own locking so multiple replicas starting concurrently don't race the migration.
- For destructive/breaking schema changes: expand-contract pattern (add new column/table, deploy code that writes both, backfill, deploy code that reads only new, then drop old) — never a single-step breaking migration against a live multi-replica service.

## 8. Production Hardening Checklist

**Resilience & scaling**
- [x] `replicaCount: 2` default for every Deployment (3 in `values-prod.yaml` for the three backend services), no exceptions.
- [x] `HorizontalPodAutoscaler` configured (CPU + memory for the Java services; CPU only for `ui`, which has no meaningful memory-driven scaling profile), `minReplicas` ≥ 2 everywhere.
- [x] `PodDisruptionBudget` (`minAvailable: 1`) on every service.
- [x] Readiness and liveness probes on `/actuator/health/readiness` / `/actuator/health/liveness` for the Java services (Spring Boot Actuator's health-probe groups), `/healthz` for `ui` (a static 200 route in `nginx.conf`).
- [x] Resource `requests`/`limits` set on every container.
- [ ] Anti-affinity or topology spread constraints — the `topologySpreadConstraints` value exists on every chart (empty by default) but no default constraint is set; fill it in per-cluster once you know your actual zone/node-pool topology.

**Mesh & network**
- [x] Istio `PeerAuthentication` set to `STRICT` mTLS, namespace-wide (`charts/deployable-microservices/templates/peerauthentication.yaml`, gated by `istioMeshPolicy.enabled`).
- [x] `AuthorizationPolicy` restricting which workloads may call which — implemented exactly as: api-gateway → {product-service, order-service}; order-service → product-service; ingress-gateway → {api-gateway, ui} (`templates/authorizationpolicy.yaml`). The ingress-gateway principal (`istioMeshPolicy.ingressGatewayPrincipal`) defaults to the standard `istioctl` default/demo profile's SA — **verify against your actual cluster** (`kubectl get sa -n istio-system`) if it wasn't installed with that profile.
- [x] `DestinationRule` outlier detection and connection-pool limits on every internal service (`product-service`, `order-service`, `api-gateway`).
- [x] `NetworkPolicy` as defense-in-depth (`templates/networkpolicy.yaml`) — default-deny ingress except from the same namespace and `istio-system`.
- [ ] Ingress TLS terminated at the Istio Gateway with a valid certificate (cert-manager recommended) — the `Gateway` template supports this (`global.tls.enabled` + `credentialName`), but it's off by default and the actual cert-manager `Certificate`/`Issuer` resources are not part of this chart; provision them separately per cluster.

**Security**
- [x] Containers run as non-root (`runAsUser`/`runAsNonRoot` set on every pod spec) with capabilities dropped. Read-only root filesystem is set `true` on the three Java services (with an `emptyDir` mounted at `/tmp` for Spring Boot/Tomcat's own temp-file needs). It is **not** set on `ui` — nginx needs to write `/usr/share/nginx/html/config.js` at container start for the runtime-config mechanism (see `learner.md` Step 5); that's a deliberate, narrower tradeoff, not an oversight.
- [ ] Pod Security Standards (`restricted`) enforced on the namespace — not yet applied as a namespace label in this repo; add `pod-security.kubernetes.io/enforce=restricted` when creating each environment's namespace.
- [ ] Image scanning gate in CI (no HIGH/CRITICAL CVEs reach prod) — `.github/workflows/ci.yml` now builds all four images on every PR (`docker-build` job, no push), but does not yet scan them; add a Trivy step before relying on this gate.
- [x] RBAC: each service gets its own dedicated `ServiceAccount` (`serviceAccount.create: true` by default in every chart), scoped only by the `AuthorizationPolicy` rules above — no explicit K8s `Role`/`RoleBinding` was needed since these services don't call the K8s API.
- [x] Secrets never in Git: the only `Secret` the charts can create themselves (`product-service`/`order-service`'s `secret.yaml`) is gated `false` by default and only enabled in `values-dev.yaml`; staging/prod set `existingSecretName` and expect it pre-provisioned out-of-band.

**Observability**
- [ ] Prometheus scraping `/actuator/prometheus` on every service + Istio's own metrics.
- [ ] Grafana dashboards for latency/error-rate/saturation per service (the four golden signals).
- [ ] Distributed tracing (Jaeger/Tempo via Istio) so a request across gateway → order-service → product-service is traceable end to end.
- [ ] Centralized logging (Loki/EFK) with correlation/trace IDs propagated through the gateway.
- [ ] Alerting on SLO burn rate, not just raw thresholds.

**Release process**
- [ ] Progressive rollout for prod: Istio traffic shifting (e.g., 5% → 25% → 100% to the new `DestinationRule` subset) rather than an instant full cutover, especially for order-service.
- [ ] `--atomic` Helm upgrades with automatic rollback on failed health checks.
- [ ] A documented, tested rollback command: `helm rollback bookstore <previous-revision> -n bookstore-prod`.
- [ ] Database migrations verified backward-compatible before code rollback is trusted.

**Data**
- [x] Postgres in dev/staging uses persistent volumes (`primary.persistence.enabled: true`, 5Gi, via the Bitnami chart) rather than `emptyDir`. Production disables the in-cluster chart entirely (`product-db.enabled: false` / `order-db.enabled: false`) in favor of a managed instance (RDS/Cloud SQL/etc.) — the cloud provider's durable storage class and backup tooling apply there, not this Helm chart's concern.
- [ ] Automated backups + a tested restore procedure — the responsibility of whichever managed Postgres service is chosen for staging/prod; not something this chart provisions.
- [ ] Connection pooling (PgBouncer or Spring's Hikari tuned) sized against `maxReplicas × pool-size` so scale-out doesn't exhaust DB connections — not yet tuned; Hikari's defaults are in effect today, revisit once real load-test numbers exist (see `tests.md` §7).

## 9. Rollout Runbook (Production Release)

1. Confirm CI is green: build, all test suites (see `tests.md`), image scan.
2. Deploy to staging with the exact image tags intended for prod; run the staging smoke + regression suite.
3. Get release approval (manual gate).
4. `helm upgrade --install ... --atomic` against prod with the new tags.
5. Watch: pod readiness, HPA behavior, Istio dashboards (error rate/latency) for 15–30 minutes.
6. If using progressive traffic shift, step the `VirtualService` weight up in stages, watching metrics at each step.
7. Announce completion; keep the previous Helm revision available for fast rollback for at least one full release cycle.

## 10. Rollback Runbook

1. `helm rollback bookstore <previous-revision> -n bookstore-prod`.
2. If the issue is data-related (bad migration), do **not** rely on code rollback alone — follow the expand-contract migration's documented down-path.
3. Confirm health via readiness probes + Istio dashboards before declaring the rollback complete.
4. File a post-incident note capturing root cause and the gap in pre-prod validation that missed it.
