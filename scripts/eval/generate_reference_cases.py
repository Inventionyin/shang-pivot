"""Generate one named-add case per on-sale SPU from a catalog snapshot.

Usage:
  python scripts/eval/generate_reference_cases.py \
    --catalog /tmp/catalog.json --out /tmp/reference-cart-auto.jsonl

The output is a separate suite (reference-cart-auto), so catalog growth never
rewrites the handcrafted 50-case behavior suite.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from reference_common import AUTO_SUITE, AUTO_VERSION, build_case, catalog_products, load_json, write_jsonl


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="按在售目录生成全量点名加购用例")
    parser.add_argument("--catalog", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)

    catalog = load_json(args.catalog)
    products = catalog_products(catalog)
    if not products:
        print("目录没有在售 SPU", file=sys.stderr)
        return 2
    rows = [build_case(product) for product in products]
    write_jsonl(args.out, rows)
    print(f"[reference-cases] wrote {args.out} count={len(rows)} "
          f"suite={AUTO_SUITE} version={AUTO_VERSION}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
