CREATE TABLE IF NOT EXISTS items (
  id      TEXT PRIMARY KEY,          -- time-sortable, newest = largest
  kind    TEXT NOT NULL,             -- note | link | image | audio | video | file
  text    TEXT,                      -- note body or file caption
  name    TEXT,                      -- file name
  mime    TEXT,
  size    INTEGER,
  key     TEXT,                      -- R2 object key (files only)
  src     TEXT,                      -- who dumped it: android, desktop, cli, link:<label>
  created INTEGER NOT NULL           -- epoch ms
);
CREATE INDEX IF NOT EXISTS items_kind ON items(kind, id);

CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING fts5(text, name, content='items', content_rowid='rowid');
CREATE TRIGGER IF NOT EXISTS items_ai AFTER INSERT ON items BEGIN
  INSERT INTO items_fts(rowid, text, name) VALUES (new.rowid, new.text, new.name);
END;
CREATE TRIGGER IF NOT EXISTS items_ad AFTER DELETE ON items BEGIN
  INSERT INTO items_fts(items_fts, rowid, text, name) VALUES ('delete', old.rowid, old.text, old.name);
END;
CREATE TRIGGER IF NOT EXISTS items_au AFTER UPDATE ON items BEGIN
  INSERT INTO items_fts(items_fts, rowid, text, name) VALUES ('delete', old.rowid, old.text, old.name);
  INSERT INTO items_fts(rowid, text, name) VALUES (new.rowid, new.text, new.name);
END;

CREATE TABLE IF NOT EXISTS links (
  token    TEXT PRIMARY KEY,
  scope    TEXT NOT NULL,            -- item | drop | read | full
  item     TEXT,                     -- for scope=item
  label    TEXT,
  expires  INTEGER,                  -- epoch ms, NULL = never
  max_uses INTEGER,                  -- NULL = unlimited
  uses     INTEGER NOT NULL DEFAULT 0,
  max_bytes INTEGER,                 -- upload budget for drop/full links, NULL = unlimited
  bytes    INTEGER NOT NULL DEFAULT 0,
  created  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS uploads (
  id      TEXT PRIMARY KEY,
  key     TEXT NOT NULL,
  r2id    TEXT NOT NULL,
  name    TEXT NOT NULL,
  mime    TEXT NOT NULL,
  text    TEXT,
  src     TEXT,
  owner   TEXT,                      -- link token that started it, NULL = owner
  created INTEGER NOT NULL
);
