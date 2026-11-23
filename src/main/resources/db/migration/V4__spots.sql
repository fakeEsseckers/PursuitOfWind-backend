-- The catalog of spots (api/openapi.yaml, Spot). Riders add spots themselves, and every rider sees every spot. The
-- sectors of a spot are not kept: the decision engine works them out from the direction of its water, so a spot
-- always has the sectors the current rules give it.
--
-- The catalog is read in pages in the order of the ids, so that order is the plain order of their characters
-- (collate "C") on every server, whatever its locale.
create table spots (
    id text collate "C" primary key,
    name text not null,
    lat double precision not null,
    lon double precision not null,
    time_zone text not null,
    water_bearing_deg double precision not null,
    created_by text not null references users (id),
    created_at timestamptz not null
);

-- The spots a rider follows (MySpot): how long the drive is, and whether it is a favourite.
create table my_spots (
    user_id text not null references users (id) on delete cascade,
    spot_id text collate "C" not null references spots (id) on delete cascade,
    drive_minutes integer not null,
    favorite boolean not null,
    primary key (user_id, spot_id)
);
