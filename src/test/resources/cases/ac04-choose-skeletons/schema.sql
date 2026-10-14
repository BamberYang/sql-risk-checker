CREATE TABLE t_order (id BIGINT, create_time DATETIME, PRIMARY KEY (id));
CREATE INDEX idx_create_time ON t_order(create_time);
