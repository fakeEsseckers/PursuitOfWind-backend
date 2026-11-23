-- A user as other crew members see them (api/openapi.yaml, Person): under the name of their profile or, until they
-- have saved one, under a name made of the end of their id, as the app's in-app backend names such a rider.
create view people as
select u.id as user_id, coalesce(p.display_name, 'Rider ' || right(u.id, 4)) as display_name
from users u
left join profiles p on p.user_id = u.id;

-- A crew (Crew): riders who share rides. Whoever has its invite code can join, and every member is shown the code to
-- pass it on, so it is kept as it is given out, in upper case.
--
-- A change in a crew, someone joining it or a change to one of its rides, holds the crew's row until it commits, so
-- such changes happen one at a time and the feed below has them in the order they happened.
--
-- position is the order crews were made in: a rider's crews are listed oldest first, and two made in the same
-- instant in the order they were made.
create table crews (
    id text collate "C" primary key,
    name text not null,
    invite_code text not null unique,
    created_at timestamptz not null,
    position bigint generated always as identity
);

-- Who is in a crew, and since when (CrewMember). position is the order of joining: members are listed in it.
--
-- What is a user's alone goes when the user goes (their profile, their tokens). What others see of them does not: a
-- member cannot leave a crew without the crew being told, so a user who is in one is not deleted from under it.
create table crew_members (
    crew_id text collate "C" not null references crews (id) on delete cascade,
    user_id text not null references users (id),
    role text not null check (role in ('OWNER', 'MEMBER')),
    joined_at timestamptz not null,
    position bigint generated always as identity,
    primary key (crew_id, user_id)
);

create index crew_members_by_user on crew_members (user_id);

-- The feed of a crew: what happened in it (CrewEvent), as the contract's JSON, with the names people had at the time.
-- An event is never changed. Its number is its place in the feed and, as evt_<number>, its id: a reader gives the
-- last one they saw and gets what came after. A change takes its number while it holds the crew, so within a crew
-- the numbers are in the order the changes committed, and no event appears behind one a reader has already seen.
create sequence crew_event_numbers;

create table crew_events (
    number bigint primary key,
    crew_id text collate "C" not null references crews (id) on delete cascade,
    event jsonb not null
);

create index crew_events_by_crew on crew_events (crew_id, number);
