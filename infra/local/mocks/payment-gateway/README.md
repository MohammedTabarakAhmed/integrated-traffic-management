# Payment gateway mock (partner #20, ICD-03 draft)

WireMock stub of the payment gateway / national e-payment platform used by `payment-service` in local development
(Gate C). `POST /v1/payment-sessions` answers a session id and a redirect URL; `GET /v1/payments/{merchantRef}/status`
answers PENDING, so the pending-resolution job (FN-005) moves unpaid sessions to EXCEPTION after the window. Callbacks
are not sent by the mock: tests and `smoke.sh` post signed callbacks themselves (HMAC-SHA256 with the local key
`DEV_PAYMENT_CALLBACK_KEY` from `infra/local/.env`).
