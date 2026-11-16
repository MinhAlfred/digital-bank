# 09 - Observability and Operations

## Contents
- Actuator
- Health probes and graceful shutdown
- Metrics
- Tracing
- Logging in production
- Timeouts and connection pools
- Virtual threads
- Containers and Docker
- Twelve-factor and deployment rules

## Actuator

Add `spring-boot-starter-actuator`. Expose the minimum:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus      # add others deliberately, never "*" in prod
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when-authorized
  server:
    port: 9090                                 # optional: separate management port, not publicly routed
```

- Only `health` (liveness/readiness) is reachable without authentication, and only from the platform/internal network. Everything else (`metrics`, `prometheus`, `env`, `loggers`, `heapdump`...) requires authentication or a private management port.
- Never expose `env`, `configprops`, `heapdump`, `threaddump`, `beans`, `mappings` on a public interface.
- `info` contains build/git info (`spring-boot-maven-plugin build-info`, `git-commit-id-plugin`) for traceability.

## Health probes and graceful shutdown

- Kubernetes/orchestrator uses `/actuator/health/liveness` and `/actuator/health/readiness`. Liveness only reflects "process is alive" (no dependency checks); readiness reflects ability to take traffic (DB, required downstream).
- Custom `HealthIndicator`s must be fast (timeout) and must not call expensive operations.
- Graceful shutdown:

```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

Set the orchestrator's termination grace period higher than this timeout. Long jobs handle `SmartLifecycle`/`@PreDestroy` to finish or hand off work.

## Metrics

- Micrometer is the facade; export to Prometheus (`micrometer-registry-prometheus`) or the platform's registry. Add common tags: `application`, `environment`.
- Use built-in metrics (JVM, HTTP server/client, HikariCP, cache, Tomcat). Add **business metrics** for key outcomes (`orders.created`, `payments.failed{reason}`) via `MeterRegistry`/`@Observed` and counters/timers.
- Metric names `lower.dot.case`; low-cardinality tags only (never user id, order id, URL with ids, free text).
- Define SLOs and alert on symptoms (error rate, latency p95/p99, saturation), not on every cause.
- Enable HTTP percentiles/histograms only where needed (cost).

## Tracing

- Micrometer Tracing + OpenTelemetry bridge/exporter (OTLP). Propagation W3C `traceparent` is the default; keep it.
- Trace id/span id are in the logging MDC automatically and returned in error responses.
- Sampling probability configured per environment (`management.tracing.sampling.probability`), 100% only in dev.
- Outbound clients must use auto-configured builders (`RestClient.Builder` for blocking, `WebClient.Builder` for reactive; HTTP interfaces backed by the matching client) so tracing and metrics instrumentation apply.

## Logging in production

- JSON structured logs to stdout (`logging.structured.format.console`), never to files inside containers. Log shipping is the platform's job.
- Root level `INFO`; package overrides in config; `DEBUG` toggled at runtime through the `loggers` actuator endpoint (authenticated) when diagnosing.
- See `06-exceptions-and-logging.md` for content rules.

## Timeouts and connection pools

- **Every outbound call has a timeout.** Starting values: connect 2s, read/response 5s, adjusted to the request deadline. Use target-version blocking-client properties or configure the reactive connector explicitly; include pool-acquisition limits and bounded in-flight work.
- **HikariCP:** `maximum-pool-size` sized deliberately (rule of thumb: much smaller than request threads; start at 10-20 and measure), `connection-timeout` 2-5s, `max-lifetime` below the DB/proxy idle cutoff, `leak-detection-threshold` set in non-prod.
- Tune request/admission limits, thread/scheduler capacity and DB/client pools together. With virtual threads a DB pool bounds DB concurrency, not total admitted requests; also bound queues and upstream work. Reactive runtimes use their driver/client pools rather than HikariCP unless an explicit blocking adapter exists.
- Database statement timeouts (`spring.jpa.properties.jakarta.persistence.query.timeout` or DB-side) for queries exposed to user input.

## Virtual threads

- In supported blocking runtimes on Java 21+: `spring.threads.virtual.enabled=true` lets Tomcat and Spring's executors use virtual threads for blocking I/O code.
- Do not pool virtual threads, do not rely on thread-locals holding large objects, avoid long `synchronized` blocks around blocking calls on Java 21-23 (pinning; largely fixed from Java 24). Prefer `ReentrantLock` when you must hold a lock across blocking I/O.
- Measure before/after; keep platform-thread executors for CPU-bound work.

## Containers and Docker

- Build images with Cloud Native Buildpacks (`./mvnw spring-boot:build-image` / `bootBuildImage`) or a multi-stage Dockerfile using the layered jar. Pin the base image by digest or exact tag. Use a JRE-only, minimal, non-root image.
- Run as a non-root user, read-only root filesystem where possible, no secrets baked into the image.
- JVM is container-aware; set memory via `-XX:MaxRAMPercentage` (e.g. 75) instead of fixed `-Xmx` unless justified. Set CPU/memory requests and limits in the orchestrator.
- `HEALTHCHECK`/probes use the actuator endpoints.
- Immutable images: the same artifact moves through all environments; configuration differs only via env/config.
- Optional startup optimizations (CDS/AOT cache, GraalVM native image) are adopted only with measured benefit and test coverage in the native profile.

## Twelve-factor and deployment rules

- Stateless instances: no local session state or local files that matter; use external stores.
- Config in environment; logs to stdout; one codebase, many deploys; dev/prod parity (Testcontainers/Docker Compose locally with `spring-boot-docker-compose` or Testcontainers dev services).
- Scheduled and background work is safe to run on multiple instances (locks/idempotency).
- Database migrations run before/with the new version and are backward compatible with the previous app version (expand/contract) so rolling deploys work.
- Every deploy is traceable: version, commit sha, build time in `/actuator/info`; changelog maintained.
- Provide a `README` with run/test/deploy commands and an operations runbook (alerts, dashboards, common failures).
## Per-service operations

Keep each service independently configurable and observable: service identity in logs/traces/metrics, compatible API/event contracts and backward-compatible migrations. Propagate trace context across HTTP and message boundaries with configured instrumentation. WebFlux uses Reactor-aware context propagation; a virtual-thread switch does not alter Netty's event-loop model. Consumers stop accepting new work and handle in-flight acknowledgment/commit deliberately during shutdown.
