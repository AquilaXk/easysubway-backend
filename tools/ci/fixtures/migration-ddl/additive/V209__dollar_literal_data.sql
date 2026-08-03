INSERT INTO audit_messages(message) VALUES ($message$DROP TABLE legacy; CREATE TABLE fake_table(id bigint);$message$);
