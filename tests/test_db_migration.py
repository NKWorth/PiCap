import sqlite3
import tempfile
from pathlib import Path

from picap.db import Database


def main() -> None:
    td = Path(tempfile.mkdtemp())
    db_path = td / "old.db"
    conn = sqlite3.connect(db_path)
    conn.execute(
        """
        CREATE TABLE readings (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            captured_at TEXT NOT NULL,
            image_path TEXT NOT NULL,
            values_json TEXT NOT NULL,
            readings_json TEXT NOT NULL
        )
        """
    )
    conn.execute(
        "INSERT INTO readings(captured_at, image_path, values_json, readings_json) "
        "VALUES ('t', 'p', '{}', '[]')"
    )
    conn.commit()
    conn.close()

    Database(db_path)
    print("old ok")
    Database(td / "new.db")
    print("new ok")


if __name__ == "__main__":
    main()
