# 0007. Forecasts from Open-Meteo, kept per place

- Status: Accepted
- Date: 2026-11-23

## Context

The contract's forecast is hourly and per weather model: each hour carries the wind of every model that has it (`ecmwf`, `icon`, `gfs`), precipitation, temperature, a thunderstorm risk and whether the hour is in daylight. The decision engine in the app combines the models; their disagreement is the confidence of a verdict.

The owner chose Open-Meteo as the source. Its free tier is for non-commercial use (no subscription, no advertising) and allows under 10,000 calls a day, 5,000 an hour and 600 a minute for the whole server; a request for up to ten variables and two weeks counts as one call. Its data is licensed CC BY 4.0. The paid plan starts when the app starts to earn, not after.

Spots are added by riders, so one beach is in the catalog as many times as riders have added it.

## Decision

- **One adapter, behind an interface** (`ForecastSource`, `OpenMeteo`). It turns what the source gives for a place into the contract's hours and knows nothing else of the server.
- **Two requests per forecast, made together and both in UTC**, so that their hours are the same hours: the wind of the three models (`ecmwf_ifs025`, `icon_seamless`, `gfs_seamless`), and precipitation, temperature, weather code, CAPE and the sun times of the source's best match. One day back and ten ahead. Each is one call by the source's counting.
- **What the source stamps at the end of an hour is read from there.** Its rain and its strongest gust are those of the hour before the stamp; the contract names an hour by its start, so both come from the stamp one hour later. An hour without that stamp, the last of a model's range or of the answer, is not given.
- **A model with nothing for an hour is left out of that hour; an hour no model covers, or without rain and temperature, is left out altogether**, as the contract describes for the end of the models' range. It is a gap in the hours a call asked for: later hours do not fill it.
- **`thunderRisk` is worked out here**: HIGH when the source itself forecasts a thunderstorm for the hour (WMO weather codes 95 to 99), MEDIUM when CAPE is 1000 J/kg or more, LOW otherwise. HIGH is a NO-GO in the engine, so it is kept for what the model says will happen.
- **`isDaylight` follows the contract**: the hour starts at or after sunrise and ends no later than half an hour before sunset, by the source's sun times. Those come per UTC day, as a sunrise and the sunset that follows it. A day without a night comes as sunrise at its start and sunset at its end, so stretches of light that touch are one; a day without a sun comes as one moment and lights no hour.
- **A forecast is kept per place for one hour** (`forecasts`, the contract's hours as JSON). A place is a latitude and longitude rounded to a hundredth of a degree, about a kilometre. Every spot there shares the forecast, whoever added the spot, so the number of requests grows with the places people look at, not with the people or the spots.
- **The source is asked once at a time for a place**: calls that come while it is being asked wait for that answer, whether it is a forecast or a failure. The fetch belongs to the server, not to the call that set it off. After a failure, of the source or of keeping what it gave, the place is left alone for five minutes.
- **When the source does not answer, the forecast that is there is given**, however old; `generatedAt` says how old. A place with no forecast at all is `503 SERVICE_UNAVAILABLE`, and `Retry-After` says what is left of the five minutes. An answer without a single hour of wind and weather is a failure too, and replaces nothing.
- **A forecast nobody has asked about for a week is deleted.**
- **No scheduler yet.** Forecasts are fetched when somebody asks. Fetching ahead belongs with the server working out windows itself and sending pushes.
- **Attribution**: whatever shows a forecast names Open-Meteo; the README does, and the app has to.

## Alternatives considered

- **Passing the source's answer through and letting the app map it** — ties the app to one source and puts thunder and daylight rules on every client.
- **A forecast kept per spot** — every rider who adds the beach again costs its own requests, and a spot that moves needs its forecast thrown away. The models' grids are no finer than a place.
- **Days in the spot's time zone** — the two requests must name the same hours, and a place is shared by spots whose time zones are whatever their riders entered. In UTC nothing depends on that.
- **Sun times computed by the server** — independent of the source, at the price of a second copy of the algorithm the app's fake backend has.
- **A cache in memory only** — lost at every deploy, which is exactly when many riders would ask at once, and not shared between instances.
- **Fetching every followed spot on a schedule** — costs requests for places nobody looks at; it becomes right when the server evaluates windows for push notifications.
- **Asking the source again on every call while it fails** — the calls that fail are the ones the daily allowance can least afford, and a source that answers `429` is asking for the opposite.
- **CAPE alone for HIGH** — unstable air without a trigger is common on good summer days; it would turn sessions into NO-GO that the model itself calls dry.

## Consequences

- Positive: the app gets real hours in the contract's shape, and a source can be replaced or joined by another behind one interface.
- Positive: a place costs two calls an hour while somebody looks at it, 48 a day if somebody always does. The daily allowance covers about two hundred such places; the hourly and the per-minute ones are reached only by 2,500 places new in one hour, or 300 in one minute.
- Negative: a forecast can be up to an hour older than the source's newest, and older when the source is down.
- Negative: spots up to about a kilometre apart get the same forecast, also where a headland lies between them.
- Negative: what is in memory is per process, the fetch under way and the pause after a failure: two instances can each fetch a place in the same hour, and each ask a failing source once in five minutes.
- Negative: the thunder rule is a judgement, not the source's; it needs a second look with real summer data.
- Negative: the adapter is tested against recorded answers of the real service, not against the service: a change of its format shows up as forecasts that stop renewing.
- Revisit when: the server works out windows itself (a scheduler), the app starts to earn (the paid plan and its API key), the daily allowance comes into sight, or a second instance runs.
