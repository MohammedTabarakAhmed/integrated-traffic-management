# Messaging gateway mock (partner #34, ICD-05 draft)

WireMock stub of the SMS, email and push providers used by `notification-service` in local development and tests
(Gate C). Each channel is served under its own prefix (`/sms`, `/email`, `/push`) and accepts every message:
`POST /<channel>/v1/messages` answers `202` with a generated `messageId`. Destinations ending in `0000` (SMS) or
starting with `fail@` (email) are refused with `503`, so retries and FAILED status can be exercised. Delivery receipts
are not sent by the mock; tests post them to `POST /v1/delivery-receipts`.
