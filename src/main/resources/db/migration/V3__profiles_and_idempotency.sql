-- What the decision engine has to know about a rider (api/openapi.yaml, RiderProfile). One per user, replaced as a
-- whole. The ranges of the fields are the contract's and are checked before a row gets here.
create table profiles (
    user_id text primary key references users (id) on delete cascade,
    display_name text not null,
    weight_kg double precision not null,
    level text not null check (level in ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    quiver_m2 double precision[] not null,
    min_wind_mps double precision,
    max_wind_mps double precision,
    min_session_minutes integer not null,
    max_drive_minutes integer not null,
    riding_from_hour integer not null,
    riding_to_hour integer not null,
    wind_unit text not null check (wind_unit in ('KNOTS', 'METERS_PER_SECOND', 'KILOMETERS_PER_HOUR', 'BEAUFORT')),
    updated_at timestamptz not null
);

-- The answer to a call that changed something, kept for a day under the key the app sent with it (Idempotency-Key),
-- so that a retry gets the same answer and changes nothing twice. The row is written in the transaction of the change
-- itself: either both are there or neither.
create table idempotency_keys (
    user_id text not null references users (id) on delete cascade,
    method text not null,
    path text not null,
    key uuid not null,
    request_hash bytea not null check (octet_length(request_hash) = 32),
    status integer not null,
    body text,
    created_at timestamptz not null,
    primary key (user_id, method, path, key)
);

create index idempotency_keys_by_age on idempotency_keys (created_at);
