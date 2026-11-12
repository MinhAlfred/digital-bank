CREATE TABLE customers (
                           id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                           auth_subject  VARCHAR(100) NOT NULL UNIQUE,
                           full_name     VARCHAR(200) NOT NULL,
                           email         VARCHAR(255) NOT NULL,
                           phone         VARCHAR(20),
                           status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                               CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
                           created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                           updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                           version       BIGINT       NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_customers_email ON customers (lower(email));