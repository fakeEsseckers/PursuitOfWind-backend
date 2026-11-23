-- The latest forecast fetched for a place (api/openapi.yaml, SpotForecast), kept so that riders looking at spots there
-- within the hour do not each cost a request to the source, and so that there is something to show when the source
-- does not answer. A place is a latitude and longitude rounded to a hundredth of a degree, about a kilometre, and the
-- grids of the weather models are no finer: every spot on a beach, whoever added it, shares one forecast. hours is
-- the contract's JSON for every hour the source gave; a call takes its slice.
create table forecasts (
    place text primary key,
    fetched_at timestamptz not null,
    hours jsonb not null
);

create index forecasts_by_age on forecasts (fetched_at);
