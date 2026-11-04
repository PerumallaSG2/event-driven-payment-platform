-- ledger-service's and payment-processor's databases (payment-api uses POSTGRES_DB=payments). Runs once, on an
-- empty data volume; on an older volume: docker exec payment-postgres createdb -U app processor
CREATE DATABASE ledger OWNER app;
CREATE DATABASE processor OWNER app;
