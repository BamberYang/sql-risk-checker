CREATE TABLE t_order (id BIGINT, status INT, amount DECIMAL(10,2), PRIMARY KEY (id));
CREATE TABLE t_plain (id BIGINT, amount VARCHAR(32), note VARCHAR(128));
