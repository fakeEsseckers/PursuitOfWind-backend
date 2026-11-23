-- A ride a crew member offers to a session window (api/openapi.yaml, Ride). What the driver posted does not change;
-- who has a seat and whether the ride is still on do, and version counts those changes. A change to a ride is a
-- change in its crew and holds the crew (V6), so riders after the last free seat are served one after the other.
--
-- position is the order rides were offered in: rides that leave at the same moment are listed in it.
create table rides (
    id text collate "C" primary key,
    crew_id text collate "C" not null references crews (id) on delete cascade,
    driver_id text not null references users (id),
    spot_id text collate "C" not null references spots (id),
    window_start timestamptz not null,
    window_end timestamptz not null,
    depart_at timestamptz not null,
    meeting_point text not null,
    seats_total integer not null check (seats_total between 1 and 8),
    status text not null check (status in ('SCHEDULED', 'CANCELLED')),
    version integer not null,
    created_at timestamptz not null,
    position bigint generated always as identity
);

create index rides_by_crew on rides (crew_id, window_end);

-- Who has a seat in a ride (Ride.passengers). position is the order the seats were taken in. As with the members of
-- a crew, a seat does not go with its user: a ride changes only through a change that is told in the feed.
create table ride_passengers (
    ride_id text collate "C" not null references rides (id) on delete cascade,
    user_id text not null references users (id),
    position bigint generated always as identity,
    primary key (ride_id, user_id)
);
