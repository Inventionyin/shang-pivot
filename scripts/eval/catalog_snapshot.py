"""Export an on-sale catalog snapshot for reference-cart case generation.

Usage:
  python scripts/eval/catalog_snapshot.py \
    --base-url http://127.0.0.1:8086 \
    --admin-username admin --admin-password '***' \
    --out /tmp/catalog.json

Credentials are arguments/env only; never hard-coded.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def request_json(url: str, method: str = "GET", body: dict[str, Any] | None = None,
                 token: str = "") -> Any:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code} {url}: {exc.read()[:300]!r}") from exc
    if isinstance(payload, dict) and payload.get("code") not in (None, 0, "0"):
        raise RuntimeError(f"API error {url}: {payload.get('msg') or payload.get('message')}")
    return payload.get("data") if isinstance(payload, dict) else payload


def list_on_sale(base: str, token: str, page_size: int = 200) -> list[dict[str, Any]]:
    products: list[dict[str, Any]] = []
    page = 1
    while True:
        data = request_json(
            f"{base}/admin/products?page={page}&size={page_size}&status=ON", token=token)
        records = (data or {}).get("records") or []
        products.extend(records)
        total = int((data or {}).get("total") or 0)
        if not records or len(products) >= total:
            break
        page += 1
    return products


def detail(base: str, token: str, spu_id: int) -> dict[str, Any]:
    data = request_json(f"{base}/admin/products/{spu_id}", token=token) or {}
    spu = data.get("spu") or {}
    skus = data.get("skus") or []
    return {"spu": spu, "skus": skus}


def build_catalog(base: str, token: str) -> dict[str, Any]:
    rows = list_on_sale(base, token)
    products: list[dict[str, Any]] = []
    for row in rows:
        spu_id = int(row.get("id"))
        d = detail(base, token, spu_id)
        spu = d["spu"] or {}
        skus = [
            {
                "skuId": sku.get("id"),
                "title": sku.get("title") or "",
                "price": sku.get("price"),
                "stock": sku.get("stock"),
                "status": sku.get("status") or "ENABLED",
            }
            for sku in d["skus"]
            if str(sku.get("status") or "ENABLED") == "ENABLED"
        ]
        min_price = row.get("minPrice")
        if min_price is None and skus:
            prices = [float(s["price"]) for s in skus if s.get("price") is not None]
            min_price = min(prices) if prices else 0
        products.append({
            "spuId": spu_id,
            "name": spu.get("name") or row.get("name") or "",
            "categoryName": row.get("categoryName") or "",
            "status": spu.get("status") or row.get("status") or "ON",
            "priceMin": min_price if min_price is not None else 0,
            "priceMax": row.get("maxPrice"),
            "skuCount": len(skus),
            "skus": skus,
        })
    products.sort(key=lambda item: int(item["spuId"]))
    return {
        "baseUrl": base,
        "generatedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat(),
        "count": len(products),
        "products": products,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="导出在售商品目录快照")
    parser.add_argument("--base-url", default=os.getenv("SHANG_BASE_URL", "http://127.0.0.1:8086"))
    parser.add_argument("--admin-username", default=os.getenv("SHANG_ADMIN_USERNAME", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("SHANG_ADMIN_PASSWORD", ""))
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    if not args.admin_password:
        print("缺少管理员密码：--admin-password 或 SHANG_ADMIN_PASSWORD", file=sys.stderr)
        return 2
    base = args.base_url.rstrip("/")
    token = request_json(f"{base}/admin/auth/login", "POST",
                         {"username": args.admin_username, "password": args.admin_password})["accessToken"]
    catalog = build_catalog(base, token)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[catalog] wrote {out} count={catalog['count']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
