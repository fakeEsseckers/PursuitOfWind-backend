-- The latest forecast fetched for a spot (api/openapi.yaml, SpotForecast), kept so that riders looking at one spot
-- within the hour do not each cost a request to the source, and so that there is something to show when the source
-- does not answer. hours is the contract's JSON for every hour the source gave; a call takes its slice. The place
-- the forecast was fetched for is kept with it: a spot that moved needs a new one.
create table forecasts (
    spot_id text collate "C" primary key references spots (id) on delete cascade,
    lat double precision not null,
    lon double precision not null,
    fetched_at timestamptz not null,
    hours jsonb not null
);
