# 0005. Opaque tokens, kept as hashes

- Status: Accepted
- Date: 2026-11-23

## Context

The contract signs an app installation in anonymously: the app sends an installation id it made once and gets an access token and a refresh token (`POST /v1/auth/anonymous`). Refresh tokens rotate (`POST /v1/auth/refresh`), and after any failed refresh the app signs in again with its installation id. The contract calls the installation id a long-lived credential.

The app's in-app fake backend already implements this, and the app's token handling was built against it: access tokens live for an hour; signing in again does not cancel the tokens handed out before.

## Decision

- **Tokens are random strings, not signed claims.** 256 random bits behind a prefix that says what the token is (`at_`, `rt_`). What a token means is a row in the database.
- **The database keeps the SHA-256 of every credential** (installation id, access token, refresh token), never the credential. No salt: each is long and random, and has to be found by its hash.
- **The fake's behaviour is the specification where the contract is silent.** An access token works for one hour. Signing in again is the same user with new tokens, and earlier tokens keep working until they expire or are used.
- **A refresh token works once.** It is removed and the next pair is issued in one transaction, so two calls with the same token cannot both succeed. The access token issued with it keeps working until it expires.
- **A refresh token left unused for 90 days stops working.** The app then signs in again, which the contract already asks of it after a failed refresh.
- **An installation id is compared as a UUID**, so its letter case does not matter, and one installation is one user for good.
- **A call without a working token is `401 UNAUTHORIZED` with a Bearer challenge**: `invalid_token` when a token was sent, a bare challenge when none was.
- **The token tables are kept small where tokens are issued, not by a job.** Whenever anyone gets tokens, that user keeps only their five newest of each kind (an app holds one pair at a time, so a client that signs in over and over fills nothing), and a batch of expired tokens, whoever's, is removed.
- **Answers are JSON whatever the call says it accepts.** The contract offers nothing else, and a call that has already used up a refresh token must not then fail over an `Accept` header.

## Alternatives considered

- **JWT access tokens** — no database read per call, at the price of a signing key to keep and rotate, and tokens that cannot be withdrawn before they expire. One indexed read per call costs nothing at this size.
- **A sign-in that cancels earlier tokens** — tidier, but an app that signs in twice at once (two requests racing after a failed refresh) would cancel its own first answer, and the fake does not do it.
- **Refresh tokens that never expire** — as in the fake, where the process is the lifetime; on a server the tokens of installations that are gone would work, and stay, forever.
- **A scheduled job that removes expired tokens** — the server has no scheduler yet, and removal at issue needs none. Tokens expire and are removed at the pace tokens are issued, which is the pace the tables grow.
- **Detecting reuse of a rotated refresh token and cancelling the whole family** — the right answer to a stolen token once accounts are worth stealing; an anonymous account can be re-entered with the installation id anyway.

## Consequences

- Positive: a copy of the database signs nobody in.
- Positive: a token is withdrawn by deleting a row.
- Negative: every authenticated call reads the database once.
- Negative: an app that somehow held more than five live pairs would find its oldest ones withdrawn.
- Negative: an installation id that leaks is the account. That is the contract's design; the app keeps the id in the platform's secure storage.
- Revisit when: accounts gain an identity beyond the installation (email, sign-in with a provider), or the read per call shows in measurements.
