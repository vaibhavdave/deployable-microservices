# deployable.md — Deployment Guide (Local → Staging → Production)

This document is a step-by-step runbook for deploying the Deployable Microservices system, plus the production hardening checklist that must be satisfied before any real traffic hits it.

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

1. Build via Gradle: `./gradlew build` (runs unit + integration tests — a failing test blocks the image build).
2. Build the image per service:
   ```
   docker build -t <registry>/product-service:<git-sha> services/product-service
   docker build -t <registry>/order-service:<git-sha> services/order-service
   docker build -t <registry>/api-gateway:<git-sha> services/api-gateway
   docker build -t <registry>/ui:<git-sha> ui
   ```
3. Push all four images. **Tag immutably with the git SHA** — this is what makes rollback a one-line `helm upgrade` with the previous tag, not a rebuild.
4. Scan every image before it's allowed into staging/prod (Trivy or equivalent) — fail the pipeline on HIGH/CRITICAL CVEs.

## 4. Helm Chart Layout

```
charts/deployable-microservices/     # umbrella chart
  Chart.yaml                         # dependencies: product-service, order-service,
                                      #   api-gateway, ui, postgresql (aliased x2)
  values.yaml                        # shared defaults
  values-dev.yaml
  values-staging.yaml
  values-prod.yaml
charts/product-service/
  templates/
    deployment.yaml    # replicas from values, resource requests/limits, probes
    service.yaml
    hpa.yaml
    pdb.yaml
    serviceaccount.yaml
    configmap.yaml
    virtualservice.yaml
    destinationrule.yaml
charts/order-service/    (same shape)
charts/api-gateway/       (same shape + istio Gateway resource)
charts/ui/                (same shape)
```

Each service's `values.yaml` exposes at minimum: `image.repository`, `image.tag`, `replicaCount` (default **2**, never 1), `resources.requests/limits`, `autoscaling.minReplicas`/`maxReplicas`/`targetCPUUtilizationPercentage`, `pdb.minAvailable`.

## 5. Deploy Commands

Local/dev:
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
`--atomic` auto-rolls back the release if the upgrade fails health checks — never deploy to prod without it.

## 6. Secrets & Configuration

- Non-sensitive config → Helm `values-<env>.yaml` → `ConfigMap`.
- Sensitive config (DB credentials, API keys) → **never** in `values.yaml`. Use Kubernetes `Secret` objects created out-of-band, or better, a secrets manager integration (Sealed Secrets, External Secrets Operator against AWS Secrets Manager/Vault/GCP Secret Manager). Charts reference secrets by name only.
- Database credentials are per-service (product DB creds only known to product-service, order DB creds only to order-service) — reinforces the database-per-service boundary.

## 7. Database Migrations

- Flyway runs as part of each service's startup (`spring.flyway.enabled=true`), gated by a K8s `initContainer` or Flyway's own locking so multiple replicas starting concurrently don't race the migration.
- For destructive/breaking schema changes: expand-contract pattern (add new column/table, deploy code that writes both, backfill, deploy code that reads only new, then drop old) — never a single-step breaking migration against a live multi-replica service.

## 8. Production Hardening Checklist

**Resilience & scaling**
- [ ] `replicaCount` ≥ 2 for every Deployment, no exceptions.
- [ ] `HorizontalPodAutoscaler` configured (CPU + memory), `minReplicas` ≥ 2.
- [ ] `PodDisruptionBudget` (`minAvailable` ≥ 1) on every service.
- [ ] Readiness and liveness probes on `/actuator/health/readiness` and `/actuator/health/liveness` (Spring Boot's split actuator health groups) — readiness gates traffic, liveness gates restarts.
- [ ] Resource `requests`/`limits` set on every container (prevents noisy-neighbor and enables correct HPA behavior).
- [ ] Anti-affinity or topology spread constraints so replicas of the same service land on different nodes/zones.

**Mesh & network**
- [ ] Istio `PeerAuthentication` set to `STRICT` mTLS cluster-wide.
- [ ] `AuthorizationPolicy` restricting which workloads may call which (e.g., only `api-gateway` may call `order-service`/`product-service`; only `order-service` may call `product-service`).
- [ ] `DestinationRule` outlier detection (eject failing pods from the load-balancing pool) and sane connection-pool limits.
- [ ] `NetworkPolicy` as defense-in-depth alongside Istio's L7 policies.
- [ ] Ingress TLS terminated at the Istio Gateway with a valid certificate (cert-manager recommended).

**Security**
- [ ] Containers run as non-root, read-only root filesystem where possible, no privileged containers.
- [ ] Pod Security Standards (`restricted`) enforced on the namespace.
- [ ] Image scanning gate in CI (no HIGH/CRITICAL CVEs reach prod).
- [ ] RBAC: each `ServiceAccount` scoped to only what it needs (no cluster-admin anywhere near app workloads).
- [ ] Secrets never in Git, never in plain `ConfigMap`.

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
- [ ] Postgres deployed with persistent volumes backed by the cloud provider's durable storage class, not `emptyDir`.
- [ ] Automated backups + a tested restore procedure (a backup nobody has restored from is not a backup).
- [ ] Connection pooling (PgBouncer or Spring's Hikari tuned) sized against `maxReplicas × pool-size` so scale-out doesn't exhaust DB connections.

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
