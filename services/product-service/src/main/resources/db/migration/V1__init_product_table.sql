CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(255)   NOT NULL,
    description     VARCHAR(2000),
    price           NUMERIC(12, 2) NOT NULL CHECK (price >= 0),
    stock_quantity  INTEGER        NOT NULL CHECK (stock_quantity >= 0),
    version         BIGINT         NOT NULL DEFAULT 0
);
