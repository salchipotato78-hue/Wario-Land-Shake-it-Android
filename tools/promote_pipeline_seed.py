#!/usr/bin/env python3
"""Builds runtime/assets/pipeline/initial_pipeline_cache.db from a pipeline cache written by the engine.

The engine writes its cache (initial_pipeline_cache.db on the PC build, pipeline_cache.db in the app's runtime folder
on Android) every time it compiles a shader. A seed is the subset of those rows that the current build can load:

  - rows whose config_version is the current one for their shader type (GX and Clear), and
  - rows whose config_size is the size the current build writes. A layout change keeps the version
    the same in some edits, so the size is checked too. The most common size among current rows is used.

Everything else is dropped, so the seed is never silently useless: check_pipeline_seed.py passes on the output.

Usage:
  python3 tools/promote_pipeline_seed.py <cache.db> [--out runtime/assets/pipeline/initial_pipeline_cache.db] [--dry-run]

The source file is never modified.
"""
import argparse
import re
import shutil
import sqlite3
import sys
import tempfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GX_HEADER = ROOT / "aurora/lib/gx/pipeline.hpp"
CLEAR_HEADER = ROOT / "aurora/lib/gfx/clear.hpp"
DEFAULT_OUT = ROOT / "runtime/assets/pipeline/initial_pipeline_cache.db"

TYPE_CLEAR = 0
TYPE_GX = 1


def read_version(path: Path, name: str) -> int:
    match = re.search(rf"{name}\s*=\s*(\d+)", path.read_text(encoding="utf-8"))
    if not match:
        sys.exit(f"No se encontró {name} en {path}")
    return int(match.group(1))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("source", type=Path, help="pipeline cache written by the engine")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--dry-run", action="store_true", help="report what would be kept, write nothing")
    args = parser.parse_args()

    if not args.source.is_file():
        sys.exit(f"No existe {args.source}")
    versions = {
        TYPE_GX: read_version(GX_HEADER, "GXPipelineConfigVersion"),
        TYPE_CLEAR: read_version(CLEAR_HEADER, "ClearPipelineConfigVersion"),
    }

    src = sqlite3.connect(f"file:{args.source}?mode=ro", uri=True)
    rows = src.execute("SELECT type, config_version, config_size FROM pipeline_cache").fetchall()
    src.close()

    total = len(rows)
    current = [(t, s) for t, v, s in rows if t in versions and v == versions[t]]
    if not current:
        sys.exit(f"Ninguna fila de {total} tiene la versión actual (GX {versions[TYPE_GX]}, "
                 f"Clear {versions[TYPE_CLEAR]}). Genera la caché con este build antes de promoverla.")

    expected_size = {}
    for t in versions:
        sizes = Counter(s for tt, s in current if tt == t)
        if sizes:
            expected_size[t] = sizes.most_common(1)[0][0]
            if len(sizes) > 1:
                print(f"  aviso: tipo {t} tiene tamaños de config mezclados {dict(sizes)}; se usa {expected_size[t]}")

    kept = Counter()
    dropped = Counter()
    for t, v, s in rows:
        if t in versions and v == versions[t] and s == expected_size.get(t):
            kept[t] += 1
        else:
            dropped[(t, v)] += 1

    print(f"Origen: {args.source} ({total} filas)")
    for t, name in ((TYPE_GX, "GX"), (TYPE_CLEAR, "Clear")):
        print(f"  {name}: versión actual {versions[t]}, tamaño {expected_size.get(t, '-')}, "
              f"se conservan {kept[t]}")
    if dropped:
        print("  descartadas:")
        for (t, v), n in sorted(dropped.items()):
            print(f"    tipo {t}, versión {v}: {n}")

    if args.dry_run:
        print("(dry-run: no se escribe nada)")
        return 0

    # Copy the whole file so the schema and indexes are exactly the engine's, then delete what is not kept.
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "seed.db"
        shutil.copy2(args.source, work)
        conn = sqlite3.connect(work)
        keep_clauses = []
        params = []
        for t in versions:
            keep_clauses.append("(type = ? AND config_version = ? AND config_size = ?)")
            params += [t, versions[t], expected_size.get(t, -1)]
        conn.execute(f"DELETE FROM pipeline_cache WHERE NOT ({' OR '.join(keep_clauses)})", params)
        conn.commit()
        conn.execute("VACUUM")
        conn.close()
        shutil.copy2(work, args.out)

    check = sqlite3.connect(f"file:{args.out}?mode=ro", uri=True)
    final = check.execute("SELECT COUNT(*) FROM pipeline_cache").fetchone()[0]
    check.close()
    if final == 0:
        sys.exit("La semilla quedó vacía; no debería pasar. Revisa el origen.")
    print(f"Escrito {args.out} con {final} filas. Verifica con: python3 tools/check_pipeline_seed.py")
    print("Recuerda subir RUNTIME_STAMP en WarioApplication.java para que los teléfonos vuelvan a copiarla.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
