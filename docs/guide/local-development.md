# Local Development

Everything needed to run and test this platform locally, with no AWS account involved at all.

## Prerequisites

- **Docker** + **Docker Compose** — the only requirement. The application, its build tooling, and its test suite all run inside containers; nothing else needs to be installed on the host for the workflow below.

(Tooling for deploying to AWS — Terraform, the AWS CLI, `kubectl` — is a separate concern; see [Deployment](deployment.md#required-tools).)

## Bring up the stack

```bash
docker compose up -d
```

Starts MySQL, LocalStack (standing in for S3/EventBridge/Lambda — see [Infrastructure](infrastructure.md)), and the application itself.

Verify it's healthy:

```bash
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8080/
```

Tear down:

```bash
docker compose down
```

## Database

Schema migrations live in `app/src/main/resources/db/migration` and are applied by [Flyway](https://flywaydb.org/) automatically on application startup, in version order. A failed migration aborts startup rather than leaving the app running against a half-migrated schema, so a healthy app means migrations succeeded. To check explicitly:

```bash
docker compose logs app | grep -E "Migrating schema|Successfully applied"
docker compose exec mysql mysql -u streaming -pstreaming streaming \
  -e "SELECT version, description, success FROM flyway_schema_history"
```

Never edit a migration that has already been applied — Flyway checksums them and will refuse to start against a database where a previously applied file has changed. Add a new `V<n>__*.sql` instead.

### Seed data

Seed data lives in `app/src/main/resources/db/seed`, outside Flyway's migration path, and is **never applied automatically** — run it explicitly when you want it:

```bash
docker compose exec -T mysql mysql -u streaming -pstreaming streaming \
  < app/src/main/resources/db/seed/dev_user.sql
```

This creates a `dev@example.com` / `password` user. The script is idempotent, so re-running it is safe. Seed scripts are for local development only and must never be run against a deployed environment.

## Tests

```bash
docker compose exec app ./gradlew test
```

Integration tests use [Testcontainers](https://testcontainers.com/) to spin up a real, ephemeral MySQL instance per run, independent of the docker-compose MySQL used for manual local development. This works because the `app` service mounts the host's Docker socket and sets `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`, letting Testcontainers start sibling containers and reach them from inside the app container. Expect noisy `RyukResourceReaper` connection-retry stack traces in the output — Ryuk only handles container cleanup and its failure does not affect test results.

## Project layout

```
app/                              Spring Boot application (Gradle)
app/Dockerfile                    Slim multi-stage image (deployment)
app/Dockerfile.dev                Single-stage image with JDK + source (local dev)
app/src/main/resources/db/migration   Flyway migrations (auto-applied on startup)
app/src/main/resources/db/seed        Seed data (run manually, local only)
lambda/                           Submit/complete/state-change Lambdas (standalone Gradle project)
docker-compose.yml                Local dev stack: app + MySQL + LocalStack
k8s/                              kind-oriented manifests, local cluster validation
k8s/aws/                          Real-AWS deployment manifest — see Deployment
terraform/                        AWS infrastructure — see Infrastructure
```

---

[← Home](../../README.md) · Next: [Deployment](deployment.md)
