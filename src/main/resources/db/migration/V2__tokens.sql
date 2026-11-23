-- The tokens handed out at sign-in and refresh (api/openapi.yaml, AuthTokens). They are credentials, so only their
-- SHA-256 is kept. An access token is checked on every call; a refresh token is used once and then gone.
create table access_tokens (
    token_hash bytea primary key,
    user_id text not null references users (id) on delete cascade,
    expires_at timestamptz not null
);

create index access_tokens_user_id on access_tokens (user_id);

create table refresh_tokens (
    token_hash bytea primary key,
    user_id text not null references users (id) on delete cascade,
    expires_at timestamptz not null
);

create index refresh_tokens_user_id on refresh_tokens (user_id);
