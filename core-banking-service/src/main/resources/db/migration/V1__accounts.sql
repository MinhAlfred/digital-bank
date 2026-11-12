CREATE TABLE currencies (
                            code        VARCHAR(3)  PRIMARY KEY,
                            minor_unit  SMALLINT NOT NULL CHECK (minor_unit BETWEEN 0 AND 4)
);
INSERT INTO currencies (code, minor_unit) VALUES ('VND', 0), ('USD', 2);

CREATE TABLE accounts (
                          id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                          account_number  VARCHAR(20)  NOT NULL UNIQUE,
                          customer_id     UUID,        -- tham chiếu logic sang customer-service, KHÔNG có FK
                          account_type    VARCHAR(20)  NOT NULL DEFAULT 'CUSTOMER'
                              CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
                          product_type    VARCHAR(20)  NOT NULL DEFAULT 'CHECKING'
                              CHECK (product_type IN ('CHECKING', 'SAVINGS')),
                          currency        VARCHAR(3)      NOT NULL REFERENCES currencies (code),
                          status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
                          balance         BIGINT       NOT NULL DEFAULT 0,
                          version         BIGINT       NOT NULL DEFAULT 0,
                          created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
                          closed_at       TIMESTAMPTZ,

                          CONSTRAINT ck_owner CHECK (
                              (account_type = 'CUSTOMER' AND customer_id IS NOT NULL) OR
                              (account_type = 'SYSTEM'   AND customer_id IS NULL)
                              ),
                          CONSTRAINT ck_balance_non_negative CHECK (account_type = 'SYSTEM' OR balance >= 0),
                          CONSTRAINT ck_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE INDEX ix_accounts_customer ON accounts (customer_id);

CREATE TABLE account_status_history (
                                        id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                        account_id  UUID        NOT NULL REFERENCES accounts (id),
                                        old_status  VARCHAR(20),
                                        new_status  VARCHAR(20) NOT NULL,
                                        reason      VARCHAR(500),
                                        changed_by  VARCHAR(100),
                                        changed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_status_history_account ON account_status_history (account_id, changed_at);

INSERT INTO accounts (account_number, account_type, currency, balance)
VALUES ('SYS-CASH-VND', 'SYSTEM', 'VND', 0);