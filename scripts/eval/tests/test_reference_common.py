from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from reference_common import build_case  # noqa: E402
from check_reference_coverage import main as coverage_main  # noqa: E402
from mine_failure_candidates import mask_pii  # noqa: E402


def product(spu_id, name, price, skus=None):
    skus = skus or []
    return {
        "spuId": spu_id,
        "name": name,
        "categoryName": "测试",
        "status": "ON",
        "priceMin": price,
        "skuCount": len(skus) or 1,
        "skus": skus,
    }


class ReferenceCaseTest(unittest.TestCase):
    def test_single_low_price_direct(self):
        case = build_case(product(1, "收纳盒", 69))
        self.assertEqual(case["expected"]["sourceFragments"], ["cart:"])
        self.assertEqual(case["expected"]["cart"][0]["quantity"], 1)

    def test_single_high_price_confirm(self):
        case = build_case(product(2, "冰箱", 4599))
        self.assertEqual(case["expected"]["sourceFragments"], ["cart-confirm:"])

    def test_multi_without_exact_sku_asks_spec(self):
        case = build_case(product(3, "手机 Pro", 5999, [
            {"title": "手机 Pro 128G", "price": 5999},
            {"title": "手机 Pro 256G", "price": 6999},
        ]))
        self.assertEqual(case["expected"]["sourceFragments"], ["sku:"])
        self.assertIn("规格", case["expected"]["answerKeywords"])

    def test_multi_with_original_sku_is_direct(self):
        case = build_case(product(4, "口红", 139, [
            {"title": "口红", "price": 139},
            {"title": "口红 豆沙", "price": 139},
        ]))
        self.assertEqual(case["expected"]["sourceFragments"], ["cart:"])

    def test_case_id_and_command(self):
        case = build_case(product(42, "测试商品", 100))
        self.assertEqual(case["sourceId"], "REF-42-named-add")
        self.assertEqual(case["turns"], ["把测试商品加进购物车"])

    def test_pii_masked(self):
        text = "手机号13900001111，身份证110101199001011234"
        masked = mask_pii(text)
        self.assertNotIn("13900001111", masked)
        self.assertNotIn("110101199001011234", masked)


class CoverageGateTest(unittest.TestCase):
    def _write(self, catalog, rows):
        tmp = Path(tempfile.mkdtemp())
        catalog_path = tmp / "catalog.json"
        dataset_path = tmp / "cases.jsonl"
        report_path = tmp / "report.md"
        catalog_path.write_text(json.dumps({"products": catalog}), encoding="utf-8")
        dataset_path.write_text(
            "\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n",
            encoding="utf-8")
        return catalog_path, dataset_path, report_path

    def test_complete_passes(self):
        products = [product(1, "收纳盒", 69), product(2, "冰箱", 4599)]
        rows = [build_case(p) for p in products]
        c, d, r = self._write(products, rows)
        self.assertEqual(coverage_main(["--catalog", str(c), "--dataset", str(d),
                                        "--report", str(r)]), 0)
        self.assertTrue(r.read_text(encoding="utf-8").startswith("# 全品类"))

    def test_missing_fails(self):
        products = [product(1, "收纳盒", 69), product(2, "冰箱", 4599)]
        rows = [build_case(products[0])]
        c, d, r = self._write(products, rows)
        self.assertEqual(coverage_main(["--catalog", str(c), "--dataset", str(d),
                                        "--report", str(r)]), 1)
        self.assertIn("缺失 SPU", r.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
