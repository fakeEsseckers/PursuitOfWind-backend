-- One user per app installation. The app signs in anonymously with an installation id it made once; signing in
-- again with the same id is the same user (api/openapi.yaml, POST /v1/auth/anonymous).
create table users (
    id text primary key,
    installation_id uuid not null unique,
    platform text not null check (platform in ('ANDROID', 'IOS')),
    created_at timestamptz not null
);
