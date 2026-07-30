# AGENTS.md — Visoris Backend

## Quick start

```sh
sbt ~reStart          # hot-reload dev server (sbt-revolver)
sbt test              # run all tests
sbt "testOnly *HelloWorldSpec"  # single test class
sbt assembly          # produce fat JAR (target/scala-*/app.jar)
docker compose up     # full stack: api + postgres + minio
```

## Stack

| Layer | Tech |
|---|---|
| Language | Scala 3.3.6 |
| Build | sbt 1.11.5 |
| HTTP | http4s Ember Server 0.23.36 |
| JSON | circe 0.14.14 |
| DB | Doobie + HikariCP + PostgreSQL 42.7.13 |
| Migrations | Flyway 13.0.0 |
| Auth | jwt-circe 11.0.4 (declared, not wired) |
| Config | ciris 3.15.0 (declared, not wired — uses `sys.env` directly) |
| Storage | MinIO (S3-compatible, via env config) |
| Test | munit + munit-cats-effect 2.2.0 |
| Logs | logback-classic (console, INFO level, ANSI colors) |

## Architecture

Entrypoint: `com.visoris.backend.Main` (Cats Effect `IOApp.Simple`).  
Startup: read env vars → HikariCP pool (32 threads) → Flyway migrate → http4s Ember on `0.0.0.0:8080` → `useForever`.

All config from environment (`.env` in dev via Docker Compose):
- `DB_URL`, `DB_USER`, `DB_PASSWORD`, `DB_DATABASE`
- `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`, `MINIO_SECRET_KEY`

## Project state

- **Initial scaffolding.** Only 3 main sources exist: `Main.scala`, `BackendServer.scala`, `config/Database.scala`.
- **No routes, services, or repositories yet.** Test imports `HelloWorld` and `BackendRoutes` that don't exist.
- **Database-first.** Full schema in `V1__create_initial_schema.sql` with Snowflake IDs (`next_id()`), 6 modules (IAM, clinics, patients, appointments, templates, reports), and PostgreSQL enums.

## Testing quirks

- Tests need a Postgres instance (no H2 in-memory override currently — `doobie-h2` is declared but unused).
- No HTTP route tests can pass until `HelloWorld` / `BackendRoutes` are implemented.

## Commands

```sh
sbt compile           # compile only
sbt ~compile          # continuous compile on file change
sbt console           # Scala 3 REPL with project deps
sbt "scalafixAll"     # run scalafix (not configured yet)
```

## Gotchas

- sbt-tpolecat is active: compiler is strict (warnings as errors, unused params, etc.). Fix warnings, don't suppress.
- Assembly merge strategy discards `module-info.class`; `app.jar` is the output name.
- `.env` is gitignored but tracked in `.specify/` — don't commit secrets.
- Generated IDs are Snowflake (BIGINT), not UUIDs. Use `next_id()` from PostgreSQL, not auto-increment.
