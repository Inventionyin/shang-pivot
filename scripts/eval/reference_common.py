"""Shared helpers for catalog-driven reference-cart case generation.

Pure stdlib so it can run in GitHub Actions without extra dependencies.
"""
from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

AUTO_VERSION = "v36-refcart-auto"
AUTO_SUITE = "reference-cart-auto"
AUTO_PLATFORM_VERSION = "v36auto"


def normalize(value: str | None) -> str:
    if not value:
        return ""
    return re.sub(r"[\s\W_]+", "", str(value), flags=re.UNICODE).lower()


def fragment(name: str) -> str:
    """Stable card fragment: first token for spaced names, first 4 chars otherwise."""
    if not name:
        return ""
    parts = name.split()
    if len(parts) > 1 and len(parts[0]) >= 2:
        return parts[0]
    return name[:4] if len(name) > 4 else name


def case_id(spu_id: int | str) -> str:
    return f"REF-{spu_id}-named-add"


def expected_for(product: dict[str, Any]) -> tuple[list[str], list[str], dict[str, Any]]:
    """Return (sourceFragments, answerKeywords, cartExpect) for one catalog product."""
    name = str(product.get("name") or "")
    price = product.get("priceMin")
    if price is None:
        price = 0
    price_value = float(price)
    skus = product.get("skus") or []
    exact_sku = any(normalize(str(sku.get("title") or "")) == normalize(name) for sku in skus)
    multi = int(product.get("skuCount") or len(skus) or 1) > 1

    if multi and not exact_sku:
        return ["sku:"], ["规格"], {}
    if price_value >= 500:
        return ["cart-confirm:"], ["请确认"], {}
    return ["cart:"], ["购物车"], {"titleFragment": fragment(name), "quantity": 1}


def checks_for(product: dict[str, Any], sources: list[str], keywords: list[str],
               cart: dict[str, Any]) -> list[dict[str, Any]]:
    checks: list[dict[str, Any]] = [
        {"type": "source_contains", "expected": source} for source in sources
    ]
    checks.append({"type": "max_sources", "expected": 1})
    checks.append({"type": "card_contains", "expected": fragment(str(product.get("name") or ""))})
    checks.extend({"type": "contains", "expected": keyword} for keyword in keywords)
    if cart:
        cart_check = dict(cart)
        cart_check["title"] = cart.get("titleFragment") or cart.get("title") or ""
        checks.append({"type": "cart_quantity", "expected": cart_check})
    checks.append({"type": "degraded_false", "expected": True})
    return checks


def build_case(product: dict[str, Any]) -> dict[str, Any]:
    name = str(product.get("name") or "")
    spu_id = product.get("spuId")
    sources, keywords, cart = expected_for(product)
    if cart:
        cart = dict(cart)
        cart["title"] = cart.get("titleFragment") or ""
    cid = case_id(spu_id)
    forbid = ["已为您加入", "即将加入"] if sources == ["sku:"] else []
    return {
        "name": f"{cid} · 全目录点名加购：{name}",
        "sourceId": cid,
        "datasetVersion": AUTO_VERSION,
        "inputType": "text",
        "command": f"把{name}加进购物车",
        "turns": [f"把{name}加进购物车"],
        "payload": {
            "sourceId": cid,
            "datasetVersion": AUTO_VERSION,
            "suite": AUTO_SUITE,
            "spuId": spu_id,
            "categoryName": product.get("categoryName") or "",
            "businessRule": f"在售 SPU「{name}」点名加入购物车，必须命中该商品",
        },
        "expected": {
            "sourceFragments": sources,
            "maxSourceCount": 1,
            "cardFragments": [fragment(name)],
            "cart": [cart] if cart else [],
            "answerKeywords": keywords,
            "forbidPatterns": forbid,
        },
        "checks": checks_for(product, sources, keywords, cart),
        "tags": [f"reference:{cid}", f"suite:{AUTO_SUITE}", f"spu:{spu_id}"],
        "priority": "P0",
        "enabled": True,
        "version": AUTO_PLATFORM_VERSION,
    }


def load_json(path: str | Path) -> Any:
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_jsonl(path: str | Path, rows: list[dict[str, Any]]) -> None:
    target = Path(path)
    if target.parent:
        target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(
        "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
        encoding="utf-8",
    )


def read_jsonl(path: str | Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            rows.append(json.loads(line))
    return rows


def catalog_products(catalog: dict[str, Any]) -> list[dict[str, Any]]:
    products = catalog.get("products") if isinstance(catalog, dict) else catalog
    return [p for p in (products or []) if str(p.get("status") or "ON") == "ON"]
