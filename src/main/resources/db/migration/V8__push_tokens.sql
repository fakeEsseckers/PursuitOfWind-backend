-- Where a rider's device is reached with a push notification (api/openapi.yaml, PushTokenRequest): the token the
-- platform's push service gave the installation. A user is one installation, so there is one token per user, the
-- last one the app sent. The server sends no pushes yet; it keeps the token for when it does.
--
-- Unlike the tokens of this API, a push token is kept as it is: it lets nobody in here, and the push service has to
-- be given it back.
create table push_tokens (
    user_id text primary key references users (id) on delete cascade,
    platform text not null check (platform in ('ANDROID', 'IOS')),
    token text not null,
    updated_at timestamptz not null
);
