# EasySubway Backend

### Reliable route discovery, real-time train updates, and facility reports for EasySubway riders.

The backend delivers mobility-adapted routing, live transit feeds, and fault reporting to the mobile app, giving operators clear oversight of data quality.

<br>

[ English ] | [ 🇰🇷 한국어 ](./README.ko.md)

<br>

## What it does

- **Route planning**: Computes barrier-free paths using departure stations, arrival stations, schedules, and step-free constraints.
- **Live train tracking**: Streams arrival times and active train positions to the app.
- **Timetable search**: Looks up national passenger trains, stations, and schedule variants.
- **Facility reporting**: Accepts community incident reports with optional photos and provides verifiable receipt codes.
- **Data verification**: Audits station structures, track topologies, and candidate DataPack releases before they reach riders.
- **Observability**: Keeps watch over route feedback, recurring elevator outages, and live transit provider connections.

## Current scope

- Provides client API endpoints and authenticated operator screens. Management tools are not publicly accessible.
- Live subway arrival feeds integrate with Seoul TOPIS.
- Production route planning is handled by the Journey V3 server using the RAPTOR algorithm with short-lived session tokens. The server relies on verified route bundles. If data is expired, missing, or mismatched, it returns an explicit error instead of falling back to stale approximations.
- Incident reporting works with or without photos. Anonymous reports use pre-signed upload URLs.
- Data modifications and releases require authenticated operator credentials.

## API contracts

Server addresses and operator credentials are not public. Endpoint specifications, headers, and schemas are defined in our contract files:

- [Realtime API Contract](contracts/api/realtime-api.openapi.yaml)
- [Train API Contract](contracts/api/train-api.openapi.yaml)
- [Report API Contract](contracts/api/report-api.openapi.yaml)
- [Journey V3 Route API Contract](contracts/api/journey-v3.openapi.yaml)

Contact: [aquila@aquilaxk.site](mailto:aquila@aquilaxk.site)
