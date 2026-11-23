# 0008. Changes in a crew happen one at a time, and its feed is their order

- Status: Accepted
- Date: 2026-11-23

## Context

The contract gives every crew a feed: what happened in it, oldest first. The app keeps the id of the last event it has seen and asks for what came after (`since`), page by page. An event about a ride carries the ride's version, and the app keeps the copy of a ride with the highest version.

That only works if nothing ever appears in the feed behind an event a reader has already seen. Two changes that take their places in the feed in one order and commit in the other break it: the reader who came between them is never told about the first.

The same crew is also where riders race: for the last free seat of a ride, or to join twice by tapping twice.

## Decision

- **A change in a crew holds the crew's row until it commits** (`select … for no key update` on `crews`): someone joining, and every change to one of the crew's rides. Changes in one crew therefore wait for each other; changes in different crews do not. The lock leaves the row's key alone, so reading the crew and inserting rows that refer to it are not held up.
- **An event takes its number while the crew is held**, from one sequence for all crews. Within a crew the numbers are in the order the changes committed.
- **An event is the contract's JSON, written once** (`crew_events.event`), with the names people had at that moment, as the contract describes. It is never changed.
- **An event's id is `evt_<number>`.** To the app it is opaque. A `since` that is not an event of that crew's feed is `410 FEED_RESET`, whatever it looks like.
- **Nothing is removed from a feed yet.** When events start to expire, a `since` older than the oldest is already answered the way the contract asks.
- **A member list is in the order of joining** (`crew_members.position`), names as they are now, through the view `people`: the profile's name or, until there is a profile, `Rider` and the end of the user's id, as the app's in-app backend does.
- **An invite code is `WIND-XXXX-XXXX`**: forty random bits from letters and digits that are not mistaken for each other, inside what the app accepts in an invitation link. It is kept as it is, because every member is shown it. What a rider typed that cannot be a code is `INVITE_INVALID` without a query.

## Alternatives considered

- **Order by the time of the event** — two changes in one millisecond have no order, and a clock set back puts new events behind old ones.
- **A counter per crew, incremented with the change** — orders the feed as well, but an event id would then need the crew in it to be unique, and the rides of a crew need the crew held before they are read anyway.
- **Serializable transactions with retries** — the database would find the conflicts, at the price of a retry loop around every change and failures that are hard to reproduce in a test.
- **Advisory locks** — the same waiting, keyed by a hash and visible nowhere in the schema.
- **Events in typed columns** — five shapes today, each a snapshot of something else (a ride, a member); the JSON is what is served.
- **The fake backend's four-character invite codes** — a million codes can be tried by one signed-in installation in an afternoon, and a hit shows a crew's members and where they meet.

## Consequences

- Positive: a reader who has seen an event has seen every event before it, and a ride's version in the feed never runs ahead of the ride.
- Positive: a race inside a crew is decided by who holds the crew first; the loser sees what the winner did.
- Negative: everything that changes in one crew is done in turn. Crews are a handful of friends; a crew of thousands would feel it.
- Negative: an event stays in the shape of the contract that wrote it. The contract only adds within `/v1`, so old events stay readable; a new major version needs a migration of the feed.
- Negative: an invite code cannot be changed or withdrawn; the contract has no operation for it.
- Revisit when: feeds need an end (retention), a crew grows beyond a group of friends, or the contract gets a way to renew an invite code.
