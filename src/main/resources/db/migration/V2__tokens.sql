-- The tokens handed out at sign-in and refresh (api/openapi.yaml, AuthTokens). They are credentials, so only their
-- SHA-256 is kept. An access token is checked on every call; a refresh token is used once and then gone.
--
-- id says which of a user's tokens are the newest (only a few are kept), issued_at is there for the day someone asks
-- what was handed out when.
create table access_tokens (
    token_hash bytea primary key check (octet_length(token_hash) = 32),
    id bigint generated always as identity,
    user_id text not null references users (id) on delete cascade,
    issued_at timestamptz not null,
    expires_at timestamptz not null
);

create index access_tokens_by_user on access_tokens (user_id, id);
create index access_tokens_by_expiry on access_tokens (expires_at);

create table refresh_tokens (
    token_hash bytea primary key check (octet_length(token_hash) = 32),
    id bigint generated always as identity,
    user_id text not null references users (id) on delete cascade,
    issued_at timestamptz not null,
    expires_at timestamptz not null
);

create index refresh_tokens_by_user on refresh_tokens (user_id, id);
create index refresh_tokens_by_expiry on refresh_tokens (expires_at);
