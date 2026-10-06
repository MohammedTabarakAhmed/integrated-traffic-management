# Economic licensing authority mock (partner #9, ICD-04)

A WireMock stub of the commercial licence lookup `GET /v1/licences/{licenceNo}` (docs/ICD.md ICD-04), used by
traffic-file-service in local development and smoke tests (Gate C "partner mocks"). Each region has its own
authority; the mock serves two under path prefixes, mapped in compose as licence sources `DXB_DED` (`/dxb`) and
`AUH_ADDED` (`/auh`).

| Source | Licence | Answer |
|---|---|---|
| DXB_DED | CN-1001234 | Rapid Transport LLC, active until 2030-12-31 |
| DXB_DED | CN-1005678 | Fleet Establishment, active until 2029-06-30 |
| DXB_DED | CN-2000001 | expired 2020-01-31 (ITMS refuses) |
| DXB_DED | CN-3000001 | cancelled (ITMS refuses) |
| DXB_DED | CN-9999999 | 503: authority unavailable |
| AUH_ADDED | CN-7000001 | Capital Company, active until 2031-03-31 |
| any | anything else | 404: unknown |

All data is invented.
