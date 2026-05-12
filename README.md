# Anorm Examples

Comprehensive examples of using the [Anorm](https://github.com/playframework/anorm) library with relational databases.

## Project Structure

```
pg/     — Anorm + PostgreSQL example with integration tests
ydb/    — Anorm + YDB example with integration tests and retry support
```

---

## pg: Anorm + PostgreSQL

A self-contained sbt project demonstrating all major Anorm features against a real PostgreSQL instance running in Docker via [Testcontainers](https://github.com/testcontainers/testcontainers-scala).

### Features Covered

Each feature lives in its own module under `pg/src/main/scala/example/anorm/pg/`:

| Module | What it demonstrates |
|---|---|
| **BasicQueries** | SQL string interpolation, scalar queries, `single`, `singleOpt`, list retrieval |
| **RowParsers** | Custom `RowParser` construction, `~` combinator, `map`, `flatten`, joined and aggregate parsers |
| **ParameterBinding** | Named parameters via `on()`, `NamedParameter` instances, multi-value `IN` clauses, optional parameters, LIKE patterns |
| **ColumnMappings** | Custom `Column[T]` and `ToStatement[T]` for user-defined types, value-level transforms, column aliasing |
| **InsertUpdateDelete** | `executeInsert()` with generated keys, `executeUpdate()`, typed key parsers, PostgreSQL `ON CONFLICT` upsert |
| **StreamingResults** | `fold` for aggregation, `foldWhile` for early termination, `withResult` / `Cursor` for row-by-row traversal |
| **AnormMacros** | `Macro.namedParser`, `ColumnNaming.SnakeCase`, `Macro.indexedParser`, aliased join projections |
| **Transactions** | Manual commit/rollback, business-rule validation inside a transaction, JDBC savepoints |
| **BatchOperations** | `BatchSql` for batch inserts and updates |
| **TimestampQueries** | Working with `TIMESTAMP` columns: fetching, inserting with explicit timestamps, range-based filtering, aggregate MIN/MAX |
| **FloatingPointQueries** | Working with `DOUBLE PRECISION` columns: nullable and non-null `Double` values, insert/select/update, range filtering, NULL handling, aggregates (AVG, SUM, MAX) |

### Prerequisites

- **JDK 11+**
- **sbt** (install via `brew install sbt` on macOS, or see [sbt setup](https://www.scala-sbt.org/download.html))
- **Docker** (the test starts a PostgreSQL 16 container automatically)

### Building & Running Tests

```bash
cd pg
sbt compile
sbt test
```

The test suite (`AnormPgSpec`) starts a PostgreSQL 16 Alpine container, populates the schema and seed data before each test, and runs 69 test cases that exercise every demo module.

### Schema

The example uses four tables: `departments`, `employees`, `projects`, and `employee_projects`. The full DDL is in `pg/src/main/resources/schema.sql` and seed data in `pg/src/main/resources/data.sql`.

---

## ydb: Anorm + YDB

A port of the PostgreSQL example targeting [YDB](https://ydb.tech/) — a distributed SQL database. Uses the [YDB JDBC Driver](https://github.com/ydb-platform/ydb-jdbc-driver) and adds a configurable retry layer for YDB's retryable exceptions.

### YDB-Specific Adaptations

| Area | PostgreSQL | YDB |
|---|---|---|
| ID generation | `SERIAL` / auto-increment | Explicit IDs (no auto-increment) |
| Upsert | `INSERT ... ON CONFLICT` | `UPSERT INTO` (YDB-native) |
| Column types | `NUMERIC`, `VARCHAR`, `TIMESTAMP` | `Double`, `Text`, `Date32`, `Timestamp64` |
| Savepoints | Supported | Not supported — use transaction retry |
| Generated keys | `executeInsert()` | `executeUpdate()` with explicit IDs |

### Features Covered

All Anorm feature modules from the PostgreSQL version are ported, plus two YDB-specific additions:

| Module | What it demonstrates |
|---|---|
| **YdbRetry** | Configurable retry with exponential backoff + jitter for `YdbRetryableException` (always retry) and `YdbConditionallyRetryableException` (idempotent-only) |
| **YdbColumnAdapters** | Custom `Column`/`ToStatement` adapters for `LocalDate` and `LocalDateTime`, bridging between YDB JDBC driver types (`java.time.Instant` for Timestamp64, `java.time.LocalDate` for Date32) and Anorm expectations |
| **BasicQueries** | SQL string interpolation, scalar queries, `single`, `singleOpt`, list retrieval |
| **RowParsers** | Custom `RowParser` construction, `~` combinator, `map`, `flatten`, joined and aggregate parsers |
| **ParameterBinding** | Named parameters via `on()`, `NamedParameter` instances, multi-value `IN` clauses, optional parameters, LIKE patterns |
| **ColumnMappings** | Custom `Column[T]` and `ToStatement[T]` for user-defined types, value-level transforms, column aliasing |
| **InsertUpdateDelete** | `UPSERT INTO`, `executeUpdate()`, explicit ID management, YDB-native upsert |
| **StreamingResults** | `fold` for aggregation, `foldWhile` for early termination, `withResult` / `Cursor` for row-by-row traversal |
| **AnormMacros** | `Macro.namedParser`, `ColumnNaming.SnakeCase`, `Macro.indexedParser`, aliased join projections |
| **Transactions** | Manual commit/rollback, business-rule validation; retry pattern demonstrated in tests |
| **BatchOperations** | `BatchSql` for batch upserts and updates |
| **TimestampQueries** | Working with `Timestamp64` columns: fetching, inserting with explicit timestamps, range-based filtering, aggregate MIN/MAX |
| **FloatingPointQueries** | Working with `Double` columns: nullable and non-null values, insert/select/update, range filtering, NULL handling, aggregates (AVG, SUM, MAX) |

### Retry Configuration

The `YdbRetry` utility catches standard JDBC exception types that the YDB driver inherits from:

- `SQLRecoverableException` ← `YdbRetryableException` — always safe to retry
- `SQLTransientException` ← `YdbConditionallyRetryableException` — safe only for idempotent operations

```scala
// Global defaults via implicit RetryConfig
implicit val cfg: RetryConfig = RetryConfig(
  maxRetries        = 10,
  initialBackoffMs  = 5,
  maxBackoffMs      = 1000,
  backoffMultiplier = 2.0,
  jitterFraction    = 0.1
)

// Idempotent read — retries on both exception families
YdbRetry.retry(idempotent = true) {
  withConnection { implicit c => BasicQueries.countDepartments() }
}

// Non-idempotent write — only unconditionally retryable errors trigger a retry
YdbRetry.retry() {
  withConnection { implicit c => InsertUpdateDelete.insertDepartment(...) }
}
```

### Prerequisites

- **JDK 11+**
- **sbt**
- **Docker** (the test starts a [local YDB](https://ydb.tech/docs/en/quickstart#install) container automatically)

### Building & Running Tests

```bash
cd ydb
sbt compile
sbt test
```

The test suite (`AnormYdbSpec`) starts a `ydbplatform/local-ydb` container following the [YDB Java SDK](https://github.com/ydb-platform/ydb-java-sdk/tree/master/tests) container initialization pattern (fixed port mapping, `Wait.forHealthcheck()`, hostname set to `localhost` for discovery), then runs 72 test cases covering all demo modules plus the retry utility.

### Schema

Same four-table schema as the PostgreSQL version, adapted for YDB types (`Int32`, `Text`, `Double`, `Date32`, `Timestamp64`, `Bool`). The `forceSignedDatetimes=true` JDBC URL property is required for Date32/Timestamp64 support. DDL is in `ydb/src/main/resources/schema.sql` and seed data in `ydb/src/main/resources/data.sql`.

---

## macOS Note (Colima)

If you use [Colima](https://github.com/abiosoft/colima) instead of Docker Desktop, the Testcontainers Ryuk sidecar may fail to mount the Docker socket. Disable Ryuk by setting an environment variable:

```bash
TESTCONTAINERS_RYUK_DISABLED=true sbt test
```

Alternatively, add the setting to `~/.testcontainers.properties`:

```properties
ryuk.disabled=true
```
