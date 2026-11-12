#!/usr/bin/env python3
"""Checks that runtime/assets/pipeline/initial_pipeline_cache.db matches the current pipeline config.

Rows whose config_version differs from GXPipelineConfigVersion (aurora/lib/gx/pipeline.hpp) are
ignored at boot, so a stale seed silently makes every shader compile on first use.
Exit code 1 when any row is stale, so this can run in CI or before a release build.
"""
import re
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HEADER = ROOT / "aurora/lib/gx/pipeline.hpp"
SEED = ROOT / "runtime/assets/pipeline/initial_pipeline_cache.db"


def current_version() -> int:
    match = re.search(r"GXPipelineConfigVersion\s*=\s*(\d+)", HEADER.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"No se encontró GXPipelineConfigVersion en {HEADER}")
    return int(match.group(1))


def main() -> int:
    version = current_version()
    conn = sqlite3.connect(SEED)
    rows = conn.execute(
        "SELECT config_version, COUNT(*) FROM pipeline_cache GROUP BY config_version ORDER BY config_version"
    ).fetchall()
    total = sum(count for _, count in rows)
    stale = sum(count for v, count in rows if v != version)
    print(f"Versión actual: {version}. Filas en la semilla: {total}. Obsoletas: {stale}.")
    for v, count in rows:
        print(f"  config_version {v}: {count} filas{'' if v == version else '  <- obsoleta'}")
    if stale:
        print("La semilla está obsoleta: regenérala desde el teléfono (ver CAMBIOS_PGO.md).")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
