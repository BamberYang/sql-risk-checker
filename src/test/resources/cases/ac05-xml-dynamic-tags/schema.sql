CREATE TABLE t_order (id BIGINT, name VARCHAR(64), status INT, create_time DATETIME, updated_at DATETIME, PRIMARY KEY (id));
CREATE INDEX idx_name ON t_order(name);
