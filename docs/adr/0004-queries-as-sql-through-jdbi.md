# 0004. Queries as SQL, through JDBI

- Status: Accepted
- Date: 2026-11-23

## Context

[ADR 0002](0002-postgresql-sql-migrations-and-tests-on-a-real-database.md) made the schema SQL in migrations and left the way queries are written to the first real one. Sign-in is that query, and it already shows what the server's rules need from the database: an insert that does nothing on a conflict and tells whether it did, and a delete that returns what it removed, so that a refresh token is consumed and the next pair issued in one transaction.

The owner of the project writes Kotlin for mobile and reads SQL in Room every day.

## Decision

- **Queries are SQL strings**, next to the code that uses them, with named parameters.
- **JDBI runs them.** `Database.transaction { handle -> … }` runs a block in one transaction on a pooled connection, off the request threads; an exception rolls it back. `Database.read { handle -> … }` runs one reading statement without a transaction around it. JDBI binds parameters, including UUIDs, byte arrays and timestamps, and maps results.
- **Rows are mapped by hand**, column by column, in the function that runs the query. No reflection, no annotations, no mapping of whole objects.
- **What is true in the database is checked there**: uniqueness by a constraint, "use once" by a `delete … returning`. A check in Kotlin before the write would race.

## Alternatives considered

- **Exposed (DSL)** — typed queries in Kotlin, but the tables are then declared twice, in the migrations and in Kotlin, and the two can drift; PostgreSQL features such as `on conflict … returning` arrive through extensions of the DSL.
- **jOOQ** — typed SQL generated from the schema, which needs a database at build time and a generation step in every build.
- **Plain JDBC** — nothing to add, and everything to write: positional parameters, result sets, transactions.
- **An ORM (Hibernate, Exposed DAO)** — hides exactly the statements the rules above depend on.

## Consequences

- Positive: the statement a rule relies on is the statement in the file; a reviewer reads one language for schema and queries.
- Positive: nothing is generated, and the build needs no database.
- Negative: a column renamed in a migration breaks a query only when a test runs it. Every query is run by a test on a real PostgreSQL, which is what makes this acceptable.
- Negative: mapping by hand is repetition, and grows with the number of columns.
- Revisit when: the number of queries makes hand-mapping the main source of mistakes.
