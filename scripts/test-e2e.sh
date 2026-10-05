#!/usr/bin/env bash
set -e

echo "=== 1. Keycloak Authentication ==="
TOKEN=$(curl -s -X POST http://localhost:8080/realms/payments/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=merchant-acme" \
  -d "client_secret=acme-secret" | grep -o '"access_token":"[^"]*"' | cut -d '"' -f4)
echo "Token acquired successfully (length: ${#TOKEN})"

IDEM_KEY="e2e-key-$RANDOM-$RANDOM"

echo -e "\n=== 2. Creating Payment with Idempotency Key ($IDEM_KEY) ==="
CREATE_RESP=$(curl -s -i -X POST http://localhost:8181/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"payerId": "11111111-1111-1111-1111-111111111111", "payeeId": "22222222-2222-2222-2222-222222222222", "amount": 250.00, "currency": "BRL"}')
echo "$CREATE_RESP"

PAYMENT_ID=$(echo "$CREATE_RESP" | grep -o '"id":"[^"]*"' | head -1 | cut -d '"' -f4)
echo "Created Payment ID: $PAYMENT_ID"
sleep 0.5

echo -e "\n=== 3. Verifying Redis Idempotency Store ==="
docker exec payments-redis redis-cli HGETALL "idemp:acme:$IDEM_KEY"

echo -e "\n=== 4. Testing Idempotent Replay (Same Key + Same Payload) ==="
curl -s -i -X POST http://localhost:8181/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"payerId": "11111111-1111-1111-1111-111111111111", "payeeId": "22222222-2222-2222-2222-222222222222", "amount": 250.00, "currency": "BRL"}' | head -n 14

echo -e "\n=== 5. Testing Idempotency Payload Mismatch (Same Key + Different Amount -> 422) ==="
curl -s -i -X POST http://localhost:8181/v1/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $IDEM_KEY" \
  -H "Content-Type: application/json" \
  -d '{"payerId": "11111111-1111-1111-1111-111111111111", "payeeId": "22222222-2222-2222-2222-222222222222", "amount": 999.00, "currency": "BRL"}'

echo -e "\n=== 6. State Transition: Authorize Payment with If-Match ==="
curl -s -i -X POST "http://localhost:8181/v1/payments/$PAYMENT_ID/authorize" \
  -H "Authorization: Bearer $TOKEN" \
  -H "If-Match: \"v0\""

echo -e "\n=== 7. Multi-Tenant BOLA Isolation: Globex trying to access Acme payment (Expect 404) ==="
GLOBEX_TOKEN=$(curl -s -X POST http://localhost:8080/realms/payments/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=merchant-globex" \
  -d "client_secret=globex-secret" | grep -o '"access_token":"[^"]*"' | cut -d '"' -f4)

curl -s -i -X GET "http://localhost:8181/v1/payments/$PAYMENT_ID" \
  -H "Authorization: Bearer $GLOBEX_TOKEN"

echo -e "\n=== 8. Checking PostgreSQL Outbox Events Table ==="
docker exec payments-postgres psql -U payments -d payments -c "SELECT id, event_type, status, merchant_id FROM outbox_events;"

echo -e "\n=== 9. Checking Apache Kafka payments.events Topic (CloudEvents Binary Mode) ==="
docker exec payments-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic payments.events \
  --from-beginning \
  --max-messages 2 \
  --timeout-ms 5000 \
  --property print.headers=true \
  --property print.key=true
