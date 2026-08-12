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
| Auth | jwt-circe 11.0.4 (wired: `TokenService`, HS256) |
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
- `JWT_SECRET` (JWT signing key; `.env` is gitignored)

Wiring (`BackendServer.run`): `TokenService.make(jwtSecret)` → `RegistrationService.make(tokenService, ...)`
and `AuthMiddleware.make(tokenService, userRepo)`.

## Conventions

### Layering (per bounded context: `domain`, `repository`, `service`, `dto`, `controller`)

- **Repository**: `trait XRepository[F[_]]` + `object XRepository.make(transactor)`. Fallible ops
  return `F[Either[RepoError, A]]` with a sealed `RepoError` ADT. See
  `iam/repository/UserRepository.scala`.
- **Service**: `trait XService[F[_]]` + `object XService.make(deps...)`. The only layer that
  coordinates multiple repositories. See `iam/service/RegistrationService.scala`.
- **Controller**: `object XController.routes[F](service): HttpRoutes[F]` — thin DTO parsing +
  delegation, no business logic. See `iam/controller/AuthController.scala`.
- **Wiring**: everything is constructed once in `BackendServer.run` and passed down. Layers never
  construct each other; no globals/singletons.

### Auth / Tokens (`shared/auth`)

- `TokenService.make(secretKey)` (HS256): `issuer = "api"`, `subject = userId`, claims
  `CustomClaims(userId, role, tokenType)`. Access TTL 900s, refresh TTL 604800s.
- `createAccessToken[F]` / `createRefreshToken[F]` force `tokenType` = `"access"` / `"refresh"`.
- `validateToken[F](token, expectedType)` enforces signature + expiry + token type. Always pass
  the explicit expected type (e.g., `validateToken[F](token, "access")`).
- Secret comes from `JWT_SECRET` env/`.env` and is passed as a constructor param — never hardcoded.
- `AuthMiddleware.make[F](tokenService, userRepo)` loads the user by `claims.userId.toLong`
  (`UserRepository.findById`), not by email.

### Secrets / env

- `.env` (gitignored) holds `DB_*`, `MINIO_*`, `JWT_SECRET`; compose passes it via `env_file`.
- Local `sbt ~reStart` does NOT load `.env` — `JWT_SECRET` falls back to `changeme-dev-secret`
  unless exported.

## Project state

- **IAM/auth is implemented** (`iam/`): `AuthController`, `RegistrationService`,
  `UserRepository`, `RefreshTokenRepository`, plus `shared/auth` (`TokenService`,
  `AuthMiddleware`, `PasswordHasher`).
- **API docs implemented** (`docs/`): `OpenApiSpec` builds an OpenAPI 3.0.1 document (circe) and
  `DocsController` serves it at `/api/v1/docs/openapi.json`, a Swagger UI shell at `/api/v1/docs`,
  and the `swagger-ui` webjar static assets at `/api/v1/docs/swagger-ui/*`. `DocsControllerSpec`
  is pure (no DB).
- **Tests exist for IAM**: `JwtServiceSpec` (pure), `UserRepositorySpec`,
  `RefreshTokenRepositorySpec`, `AuthControllerSpec` (E2E, needs Postgres).
- **Database-first.** Schema in `V1__create_initial_schema.sql` with Snowflake IDs (`next_id()`),
  6 modules (IAM, clinics, patients, appointments, templates, reports), PostgreSQL enums.

## Testing quirks

- Repository/E2E tests need a Postgres instance (`DB_URL`/`DB_USER`/`DB_PASSWORD`).
- `JwtServiceSpec` is pure and needs no DB.

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
- `.env` is gitignored — never commit `JWT_SECRET` or DB credentials.
- Generated IDs are Snowflake (BIGINT), not UUIDs. Use `next_id()` from PostgreSQL, not auto-increment.
