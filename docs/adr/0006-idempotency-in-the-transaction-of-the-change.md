# 0006. Idempotency in the transaction of the change

- Status: Accepted
- Date: 2026-11-23

## Context

The contract lets the app send an `Idempotency-Key`, a UUID, with every call that changes something for a signed-in user, so that a call can be retried when its answer was lost. A retry with the same key must not change anything twice and must get the first answer, marked `Idempotent-Replayed: true`; the same key with another body is `422 IDEMPOTENCY_KEY_REUSED`.

The app's in-app fake backend does this in memory, one request at a time. A server takes requests at the same moment, on more than one instance, and can stop between the change and its record.

## Decision

- **A handler that changes something runs inside one database transaction and returns its answer** (`changing`, `Answer`). It does not write to the call: the answer is written after the transaction has committed.
- **The key is claimed, and the answer kept, in that same transaction.** A row for (user, method, path, key) is inserted first; the change runs; the answer is stored in the row; all of it commits or none of it does. There is no moment at which the change is there and its record is not.
- **Two calls with one key at the same moment cannot both run.** The second insert waits for the first transaction, then finds the row and replays its answer.
- **The request is recognised by the SHA-256 of its body.** Another body under the same key is refused.
- **A key belongs to one user, method and path, for 24 hours**, as in the fake. Keys older than that are removed, a batch at a time, whenever a key is claimed.
- **Only a call that succeeded is kept.** A call that failed has changed nothing; its transaction rolls back, key included, and a retry runs again.
- **A key that is not a UUID is `422 VALIDATION_FAILED`.** A call without a key just runs.

## Alternatives considered

- **Keeping failed answers too, as the fake does** — a retry would then be told what was true then, not what is true now, and a corrected request under the same key would be refused. Running it again is never wrong, because the failed call changed nothing.
- **Recording the answer after the commit** — a crash between the two leaves a change that a retry repeats, which is the one case the key exists for.
- **A lock per key outside the database** — works on one instance only.
- **Natural idempotency only** (`PUT` replaces, so a repeat is harmless) — true for the profile, false for taking a seat or creating a ride; one mechanism for every change is simpler to reason about.

## Consequences

- Positive: a retried call is safe whatever was lost, the answer or the server.
- Positive: handlers that change something share one shape, and cannot answer before their change is committed.
- Negative: such a handler cannot suspend, call another service or read the call directly; what it needs from the call is handed to it.
- Negative: where the fake replays a failed answer, the server runs the call again. The outcomes differ only when something changed in between.
- Revisit when: a change has to call another service (a payment, a push) inside its handler.
