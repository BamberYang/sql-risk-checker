CREATE TABLE t_order (id BIGINT, status INT, banned TINYINT, PRIMARY KEY (id));
CREATE INDEX idx_status ON t_order(status);
