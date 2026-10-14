CREATE TABLE t_order (id BIGINT, status INT, updated_at DATETIME, PRIMARY KEY (id));
CREATE INDEX idx_status ON t_order(status);
