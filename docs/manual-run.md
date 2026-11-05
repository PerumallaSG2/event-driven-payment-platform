# Manual end-to-end run against the real fraud-detection app

The three services ran next to the real [fraud-detection](https://github.com/PerumallaSG2/fraud-detection) v1.0.0
(no stub) on one 8 GB M2 MacBook, each with its own Docker infra. Each scenario was driven with `curl`, and a
reconciliation run followed each one. Run on 2026-11-04.

## Setup

| | Payment platform | fraud-detection |
|---|---|---|
| Postgres | 5434 | 5433 |
| Kafka (KRaft) | 9095 | 9094 |
| Redis | – | 6380 |
| Apps | payment-api 8081, payment-processor 8082, ledger-service 8083 | 8080 |

No host ports clash. kafka-ui and fraud-detection's monitoring profile were left off to save memory. Each JVM ran
from its jar with `-Xmx256m -XX:+UseSerialGC -XX:TieredStopAtLevel=1`. Each started in 4-5 s and stayed under
230 MB RSS. Ollama wasn't running, so fraud-detection used its rule-reasons fallback for explanations.

```bash
docker compose -f infra/docker-compose.yml up -d --wait postgres kafka
(cd ~/projects/fraud-detection && docker compose up -d --wait)
OLLAMA_URL=http://localhost:1 java $JOPTS -jar ~/projects/fraud-detection/target/fraud-detection-*.jar &
java $JOPTS -jar services/ledger-service/target/ledger-service-*.jar &
java $JOPTS -jar services/payment-api/target/payment-api-*.jar &
java $JOPTS -jar services/payment-processor/target/payment-processor-*.jar &
```

Each scenario used a new payer (100.00 USD deposited), a new merchant and a new card id, so only the intended fraud
rules could fire.

## Results

| # | Payment | Fraud decision | Saga outcome | Payer / merchant after | Hold | Reconciliation |
|---|---|---|---|---|---|---|
| 1 | 10.50, RESTAURANT, 13:15 local | APPROVE, score 0, no rules | **COMPLETED** | 89.50 / 10.50 | CAPTURED | CLEAN |
| 2 | 25.00, GAMBLING, 02:30 local | REVIEW, score 50 (RISKY_MERCHANT 30 + ODD_HOURS 20) | **PENDING_REVIEW**, then `review approve` → **COMPLETED** | 75.00 / 0.00 while in review; 75.00 / 25.00 after | OPEN, then CAPTURED | CLEAN (in review and after) |
| 3 | 25.00, GAMBLING, 02:30 local | REVIEW, score 50 (same rules) | **PENDING_REVIEW**, then `review reject` → **FAILED (FRAUD_REJECTED)** | 75.00 / 0.00 while in review; 100.00 / 0.00 after | OPEN, then RELEASED | CLEAN |
| 4 | 10.50, RESTAURANT, 13:15 local, fraud app stopped | none (connection refused, 3 attempts) | **FAILED (FRAUD_UNAVAILABLE)** about 1 s after the POST | 100.00 / 0.00 | RELEASED | CLEAN |

- The 02:30 timestamp (`-06:00`) reached fraud-detection with its offset unchanged, and its odd-hours rule fired on
  local time. fraud-detection's own record (`GET :8080/api/transactions/{id}`) matches the payment's `fraud` field.
- While a payment waited in review, its money sat in the HOLDS account (balance 25.00, one OPEN hold). It was
  neither paid to the merchant nor returned to the payer until the reviewer decided.
- After each scenario, the HOLDS balance was 0.00 and no holds were OPEN. Each reconciliation checked the payments
  that had reached a final state so far (1, 2, 3, 4) and found 0 mismatches.
- After the run, all five `*.DLT` topics were empty and no outbox rows were unsent in any service. No service
  logged an ERROR line.
