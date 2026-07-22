CREATE TABLE orders (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT      NOT NULL,
    quantity    INTEGER     NOT NULL CHECK (quantity > 0),
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
