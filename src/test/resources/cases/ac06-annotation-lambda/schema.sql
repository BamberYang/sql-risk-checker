CREATE TABLE t_order (id BIGINT, status INT, amount BIGINT, create_time DATETIME, PRIMARY KEY (id));
CREATE INDEX idx_status ON t_order(status);
CREATE INDEX idx_amount ON t_order(amount);
