#!/usr/bin/env python3
"""Compare logical rows and regional placement in two local Docker geo DBs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[3]
ENTITIES = json.loads(Path(__file__).with_name("entities.json").read_text())


def snapshot(database):
    statements = ["BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;",
                  "SET LOCAL timezone='UTC'; SET LOCAL bytea_output='hex';"]
    for name, entity in sorted(ENTITIES.items()):
        order = ', '.join(entity['pk'])
        statements.append(f"SELECT '{name}', row_to_json(t)::text FROM public.{name} t ORDER BY {order};")
        statements.append(f"SELECT '{name}:placement', placement_region, count(*) FROM geo.data_{name} GROUP BY placement_region ORDER BY placement_region;")
    statements.append("COMMIT;")
    result = subprocess.run(["docker", "compose", "exec", "-T", "tserver-asia-1",
                             "/home/yugabyte/bin/ysqlsh", "-X", "-qAt", "-h", "localhost",
                             "-p", "5433", "-U", "yugabyte", "-d", database,
                             "-v", "ON_ERROR_STOP=1"], cwd=ROOT, input='\n'.join(statements),
                            text=True, capture_output=True, check=True)
    rows = {name: [] for name in ENTITIES}
    placement = []
    for line in result.stdout.splitlines():
        name, value = line.split('|', 1)
        if name.endswith(':placement'):
            placement.append(line)
        else:
            rows[name].append(value)
    return rows, placement


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source')
    parser.add_argument('restored')
    args = parser.parse_args()
    if args.source == args.restored:
        parser.error('source and restored databases must differ')
    source, source_placement = snapshot(args.source)
    restored, restored_placement = snapshot(args.restored)
    failed = False
    for name in sorted(ENTITIES):
        digest = hashlib.sha256('\n'.join(source[name]).encode()).hexdigest()
        ok = source[name] == restored[name]
        print('PASS' if ok else 'FAIL', name, 'rows=', len(source[name]), 'sha256=', digest)
        failed |= not ok
    ok = source_placement == restored_placement
    print('PASS' if ok else 'FAIL', 'regional row counts')
    raise SystemExit(1 if failed or not ok else 0)


if __name__ == '__main__':
    main()
