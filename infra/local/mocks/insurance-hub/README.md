# Insurance hub mock (partner #11, ICD-02 draft)

WireMock stub of the insurers' policy query used by `vehicle-service` in local development (Gate C).
`GET /v1/policies?vin=&asOf=` answers one ACTIVE policy valid from 2020 to 2099 for the VIN asked about, except
for VINs ending in `00000`, which get a policy that EXPIRED on 2020-12-31 (UAT-3), and VINs ending in `99999`,
for which the hub answers 503 (fail closed).
