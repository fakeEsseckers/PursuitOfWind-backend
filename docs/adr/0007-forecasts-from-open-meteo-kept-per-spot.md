# 0007. Forecasts from Open-Meteo, kept per spot

- Status: Accepted
- Date: 2026-11-23

## Context

The contract's forecast is hourly and per weather model: each hour carries the wind of every model that has it (`ecmwf`, `icon`, `gfs`), precipitation, temperature, a thunderstorm risk and whether the hour is in daylight. The decision engine in the app combines the models; their disagreement is the confidence of a verdict.

The owner chose Open-Meteo as the source. Its free tier is for non-commercial use and allows under 10,000 calls a day for the whole server; its data is licensed CC BY 4.0. The paid plan starts when the app starts to earn (a subscription or ads), not after.

## Decision

- **One adapter, behind an interface** (`ForecastSource`, `OpenMeteo`). It turns what the source gives for a place into the contract's hours and knows nothing else of the server.
- **Two requests per forecast, made together**: the wind of the three models (`ecmwf_ifs025`, `icon_seamless`, `gfs_seamless`), and precipitation, temperature, weather code, CAPE and the sun times of the source's best match, with days in the spot's own time zone. One day back and ten ahead.
- **A model with nothing for an hour is left out of that hour; an hour no model covers, or without rain and temperature, is left out altogether**, as the contract describes for the end of the models' range.
- **`thunderRisk` is worked out here**: HIGH when the source itself forecasts a thunderstorm for the hour (WMO weather codes 95 to 99), MEDIUM when CAPE is 1000 J/kg or more, LOW otherwise. HIGH is a NO-GO in the engine, so it is kept for what the model says will happen.
- **`isDaylight` follows the contract**: the hour starts at or after sunrise and ends no later than half an hour before sunset, by the source's sun times.
- **A forecast is kept per spot for one hour** (`forecasts`, the contract's hours as JSON). Riders looking at one spot share it, so the number of requests grows with the spots people look at, not with the people. One fetch per spot at a time in a process.
- **When the source does not answer, the forecast that is there is given**, however old; `generatedAt` says how old. A spot with no forecast at all is `503 SERVICE_UNAVAILABLE` with `Retry-After`.
- **No scheduler yet.** Forecasts are fetched when somebody asks. Fetching ahead belongs with the server working out windows itself and sending pushes.
- **Attribution**: whatever shows a forecast names Open-Meteo; the README does, and the app has to.

## Alternatives considered

- **Passing the source's answer through and letting the app map it** — ties the app to one source and puts thunder and daylight rules on every client.
- **Sun times computed by the server** — independent of the source and right at the poles, at the price of a second copy of the algorithm the app's fake backend has. The source's times are good enough where people kite.
- **A cache in memory only** — lost at every deploy, which is exactly when many riders would ask at once, and not shared between instances.
- **Fetching every followed spot on a schedule** — costs requests for spots nobody looks at; it becomes right when the server evaluates windows for push notifications.
- **CAPE alone for HIGH** — unstable air without a trigger is common on good summer days; it would turn sessions into NO-GO that the model itself calls dry.

## Consequences

- Positive: the app gets real hours in the contract's shape, and a source can be replaced or joined by another behind one interface.
- Positive: at one request pair per spot per hour, the free tier's daily allowance covers a couple of hundred spots looked at round the clock.
- Negative: a forecast can be up to an hour older than the source's newest, and older when the source is down.
- Negative: two instances can each fetch the same spot once in the same hour.
- Negative: the thunder rule is a judgement, not the source's; it needs a second look with real summer data.
- Revisit when: the server works out windows itself (a scheduler), the app starts to earn (the paid plan and its API key), or the daily allowance comes into sight.
