# Identity register mock (partner #1, ICD-01)

A WireMock stub of the national identity register lookup `GET /v1/persons/{nationalIdNo}` (docs/ICD.md ICD-01), used
by traffic-file-service in local development and smoke tests until a partner sandbox exists (Gate C "partner mocks").

| National ID | Answer |
|---|---|
| 784199012345671 | citizen, male, born 1990-04-12 |
| 784198812345672 | resident (expires 2028-01-31), female, born 1988-09-03 |
| 784197012345673 | deceased: ITMS refuses to open a file (DL-010) |
| 999999999999999 | 503: register unavailable (ITMS answers 503 `REGISTER_UNAVAILABLE`) |
| anything else | 404: not known to the register |

All data is invented. The mock is not authenticated; the real interface uses mTLS and OAuth2 client credentials
(ICD common section, INT-004).
