CREATE TABLE t_order (id BIGINT, status VARCHAR(16), PRIMARY KEY (id));
CREATE INDEX idx_status ON t_order(status);
