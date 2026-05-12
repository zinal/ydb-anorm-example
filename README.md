# Anorm Examples

Comprehensive examples of using the [Anorm](https://github.com/playframework/anorm) library with relational databases.

## Project Structure

```
pg/     — Anorm + PostgreSQL example with integration tests
```

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

### Prerequisites

- **JDK 11+**
- **sbt** (install via `brew install sbt` on macOS, or see [sbt setup](https://www.scala-sbt.org/download.html))
- **Docker** (the test starts a PostgreSQL 16 container automatically)

### Building

```bash
cd pg
sbt compile
```

### Running Tests

```bash
cd pg
sbt test
```

The test suite (`AnormPgSpec`) starts a PostgreSQL 16 Alpine container, populates the schema and seed data before each test, and runs 45 test cases that exercise every demo module.

### macOS Note (Colima)

If you use [Colima](https://github.com/abiosoft/colima) instead of Docker Desktop, the Testcontainers Ryuk sidecar may fail to mount the Docker socket. Disable Ryuk by setting an environment variable:

```bash
TESTCONTAINERS_RYUK_DISABLED=true sbt test
```

Alternatively, add the setting to `~/.testcontainers.properties`:

```properties
ryuk.disabled=true
```

### Schema

The example uses four tables: `departments`, `employees`, `projects`, and `employee_projects`. The full DDL is in `pg/src/main/resources/schema.sql` and seed data in `pg/src/main/resources/data.sql`.
