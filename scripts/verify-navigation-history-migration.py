"""Focused SQLite check of the actual migration SQL against Room's exported schemas.

Uses temporary synthetic databases only. Android tests separately validate opening
the migrated file through Room and its DAOs.
"""
import json
from pathlib import Path
import re
import sqlite3
import tempfile

root = Path(__file__).resolve().parents[1]
schema_dir = root / "app/schemas/com.example.autohmi.AutoHmiDatabase"
old = json.loads((schema_dir / "1.json").read_text(encoding="utf-8"))["database"]
new = json.loads((schema_dir / "2.json").read_text(encoding="utf-8"))["database"]
source = (root / "app/src/main/java/com/example/autohmi/AutoHmiDatabase.kt").read_text(encoding="utf-8")
migration_sql = re.findall(r'db\.execSQL\("([^"\n]+)"\)', source)
assert len(migration_sql) == 2
assert all(sql.startswith("CREATE ") for sql in migration_sql)


def create_exported_schema(connection, schema):
    for entity in schema["entities"]:
        table = entity["tableName"]
        connection.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            connection.execute(index["createSql"].replace("${TABLE_NAME}", table))
    for query in schema["setupQueries"]:
        connection.execute(query)


def columns(connection, table):
    return connection.execute(f"PRAGMA table_info('{table}')").fetchall()


with tempfile.TemporaryDirectory(prefix="autohmi-history-verification-") as directory:
    database_path = Path(directory) / "synthetic.db"
    connection = sqlite3.connect(database_path)
    create_exported_schema(connection, old)
    legacy = (42, 1000, 12.5, 31.25, "ECO")
    connection.execute("INSERT INTO trip_snapshots VALUES (?, ?, ?, ?, ?)", legacy)
    original_columns = columns(connection, "trip_snapshots")
    for sql in migration_sql:
        connection.execute(sql)
    assert connection.execute("SELECT * FROM trip_snapshots").fetchall() == [legacy]
    assert columns(connection, "trip_snapshots") == original_columns

    expected = sqlite3.connect(":memory:")
    create_exported_schema(expected, new)
    for entity in new["entities"]:
        table = entity["tableName"]
        assert columns(connection, table) == columns(expected, table), table
    assert connection.execute("PRAGMA index_info('index_completed_navigation_trips_completedAtMillis')").fetchall() == expected.execute(
        "PRAGMA index_info('index_completed_navigation_trips_completedAtMillis')").fetchall()
    expected.close()

    fields = "completionId, completedAtMillis, simulatedDurationSeconds, distanceMeters, averageSpeedKmh, source, completionStatus, destinationLabel, destinationGapMeters"
    insert = f"INSERT OR IGNORE INTO completed_navigation_trips ({fields}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
    record = ("same-session", 2000, 11.4, 95.0, 95.0 / 11.4 * 3.6, "DEMO", "COMPLETED", "Offline destination", 80.0)
    connection.execute(insert, record)
    connection.execute(insert, (record[0], 9999, *record[2:]))
    assert connection.execute("SELECT * FROM completed_navigation_trips").fetchall() == [record]
    for identifier, timestamp in (("newer", 3000), ("older", 1000)):
        connection.execute(insert, (identifier, timestamp, *record[2:]))
    rows = connection.execute("SELECT completionId FROM completed_navigation_trips ORDER BY completedAtMillis DESC, completionId DESC").fetchall()
    assert rows == [("newer",), ("same-session",), ("older",)]
    next_snapshot = connection.execute("INSERT INTO trip_snapshots(savedAtMillis, distanceKm, remainingEnergyKwh, drivingMode) VALUES(3000, 2.0, 29.0, 'NORMAL')").lastrowid
    assert next_snapshot > 42
    connection.execute("PRAGMA user_version=2")
    connection.commit()
    connection.close()

    reopened = sqlite3.connect(database_path)
    assert reopened.execute("SELECT * FROM trip_snapshots WHERE id=42").fetchone() == legacy
    assert reopened.execute("SELECT * FROM completed_navigation_trips WHERE completionId='same-session'").fetchone() == record
    reopened.execute("DELETE FROM trip_snapshots WHERE id=42")
    assert reopened.execute("SELECT COUNT(*) FROM completed_navigation_trips").fetchone()[0] == 3
    reopened.close()

print("PASS: actual 1->2 migration matches Room schema 2; legacy snapshots and IDs preserved.")
print("PASS: completion idempotency, newest-first ordering, reopen persistence and table independence.")
