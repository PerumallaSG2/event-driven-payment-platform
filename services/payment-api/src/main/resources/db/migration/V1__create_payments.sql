-- The table Hibernate's ddl-auto=update used to create; from now on Flyway owns the schema and Hibernate only validates.
CREATE TABLE payments (
    id         UUID           PRIMARY KEY,
    amount     NUMERIC(19, 2) NOT NULL CHECK (amount > 0),
    currency   VARCHAR(3)     NOT NULL,
    status     VARCHAR(20)    NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL
);
