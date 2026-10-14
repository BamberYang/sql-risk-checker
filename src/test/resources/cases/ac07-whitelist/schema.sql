CREATE TABLE t_order (id BIGINT, code VARCHAR(32), status INT, updated_at DATETIME, PRIMARY KEY (id));
CREATE INDEX idx_code ON t_order(code);
