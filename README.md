# EasySubway Backend

EasySubway Backend helps riders find journeys that fit their mobility needs. It powers nationwide urban-rail routing, train and real-time information, accessibility facility reports, and the tools operators use to keep the service reliable.

## What it offers

- **Accessible journey planning** — Finds nationwide urban-rail journeys with Journey V3, including ITX-Cheongchun guidance, using walking pace, mobility needs, transfer limits, and step-free requirements.
- **Real-time train information** — Provides station arrivals and available train positions.
- **Nationwide train search** — Searches passenger-train stations and one-way or round-trip services, including ITX-Maum and ITX-Saemaeul.
- **Facility reports** — Accepts reports with optional photos and lets riders check or confirm the result.
- **Operations tools** — Helps authorized operators review station, facility, accessibility, timetable, routing, and release data.
- **Service insights** — Tracks routing feedback, recurring facility problems, notifications, and data-collection health.

## How the service works

- Journey V3 is the only route-calculation authority. The mobile app's offline packs support maps and station search, but they never calculate routes.
- Every journey uses the active, verified route bundle and returns its data identity with the result.
- Accessibility is part of the routing decision, not an extra label added afterward.
- If required data, real-time information, or identity evidence is missing, stale, or incompatible, the API returns a clear typed error. It never substitutes a local calculation, legacy route, previous artifact, or placeholder result.
- Short-lived bearer sessions protect Journey V3 requests.
- Facility reports can be sent without a photo. Anonymous riders use their report credential to check and confirm a result, while signed-in riders can access their own reports directly.
- Data changes, releases, provider controls, and administrative features are available only to authorized operators.

## APIs and contact

Production server addresses and operator access are not published here. The versioned API contracts are available in this repository:

- [Real-time API](contracts/api/realtime-api.openapi.yaml)
- [Train search API](contracts/api/train-api.openapi.yaml)
- [Facility report API](contracts/api/report-api.openapi.yaml)
- [Journey V3 API](contracts/api/journey-v3.openapi.yaml)

For app integration or contract questions, contact [aquila@aquilaxk.site](mailto:aquila@aquilaxk.site).
