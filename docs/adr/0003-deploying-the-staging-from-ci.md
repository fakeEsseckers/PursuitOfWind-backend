# 0003. Deploying the staging from CI

- Status: Accepted
- Date: 2026-11-23

## Context

The staging runs on Railway: one service for the server and a PostgreSQL beside it. The owner wants every merged slice of the API to be reachable from a debug build of the app without a manual step.

Railway can build a service from a connected GitHub repository on every push. This repository cannot be built that way as it is: the `Dockerfile` packages a distribution that Gradle builds first, and that build needs a token for the client repository's GitHub Packages ([ADR 0001](0001-server-stack-and-shared-artifacts.md)).

## Decision

- **CI deploys, Railway does not pull.** The Railway service has no repository connected. The CI job that has checked a push to `main` (lint, tests, the image built and started against a PostgreSQL) uploads the build context to Railway with its CLI (`railway up`), and Railway builds the image from it and replaces the running deployment.
- **The upload is the tested distribution.** Only the `Dockerfile` and `build/install/server` are sent, the files the job has just built the image from. No source, no build tool and no token go to Railway.
- **One secret, optional.** `RAILWAY_TOKEN` is a project token of the Railway project: it can deploy to that project's environment and nothing else. Without it the job says so and deploys nothing, so the repository stays buildable for anyone.
- **Pull requests never deploy.** Only a push to `main`, after every check has passed in the same job.
- **What the service needs is set on Railway**, not in the repository: `DATABASE_URL` as a reference to the project's PostgreSQL. `PORT` is given by Railway.
- **The CLI version is pinned** in the workflow and moved on purpose.

## Alternatives considered

- **Railway's GitHub autodeploy with a build inside the image** — a multi-stage `Dockerfile` would need the GitHub Packages token inside Railway's build, and would deploy a commit whether or not its checks passed (or need Railway's "wait for CI" on top).
- **Publishing the image to a registry and pointing the service at it** — the image would have to be public, or the service on a plan that reads private registries; and Railway picks up a new image on its own schedule, not on merge.
- **`railway.toml` beside the code for health checks and restart policy** — Railway's documentation marks config as code as deprecated; those settings stay in the service's settings until they are needed in the repository.

## Consequences

- Positive: what reaches the staging is exactly what CI tested, and nothing reaches it that failed.
- Positive: the repository holds no address, name or credential of the deployment beyond the service's name.
- Negative: the job ends when Railway has built the image, not when the new deployment answers; a deployment that fails at start (a missing `DATABASE_URL`, a failed migration) shows on Railway, not in CI.
- Negative: Railway builds the image a second time; the job's own image exists only to prove the `Dockerfile` and the start.
- Revisit when: there is a production environment (it deploys from a release tag, not from every merge), or the job should wait for the deployment's health.
