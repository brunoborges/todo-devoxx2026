CREATE TABLE categories (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	name VARCHAR NOT NULL,
	name_key VARCHAR GENERATED ALWAYS AS (LOWER(TRIM(name))),
	CONSTRAINT uq_categories_name UNIQUE (name_key),
	CONSTRAINT ck_categories_name CHECK (
		name = TRIM(name) AND name <> '' AND LOWER(name) <> 'uncategorized'
	)
);

CREATE TABLE todos (
	id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
	name VARCHAR NOT NULL,
	description VARCHAR,
	due_at TIMESTAMP(9) WITH TIME ZONE,
	category_id BIGINT,
	completed BOOLEAN NOT NULL DEFAULT FALSE,
	CONSTRAINT ck_todos_name CHECK (name = TRIM(name) AND name <> ''),
	CONSTRAINT fk_todos_category FOREIGN KEY (category_id) REFERENCES categories (id)
);

CREATE INDEX ix_todos_category ON todos (category_id);
CREATE INDEX ix_todos_order ON todos (completed, due_at, id);
