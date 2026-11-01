#!/usr/bin/env python3
"""从 PDF 中定位各章节起始页，生成目录页码映射 JSON。"""
from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

from pypdf import PdfReader


RADICALS = {
    "⻛": "风", "⻚": "页", "⻆": "角", "⻓": "长", "⻔": "门", "⻢": "马",
    "⻉": "贝", "⻋": "车", "⻅": "见", "⻄": "西", "⻘": "青", "⻩": "黄",
    "⻮": "齿", "⻰": "龙", "⻳": "龟", "⻜": "飞", "⻝": "食", "⻢": "马",
}


def norm(value: str) -> str:
    # Chrome 导出的中文可能使用康熙部首等兼容字符，NFKC + 常见部首回写后再比较
    text = unicodedata.normalize("NFKC", value or "")
    for source, target in RADICALS.items():
        text = text.replace(source, target)
    return re.sub(r"\s+", "", text)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", required=True)
    parser.add_argument("--titles", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    reader = PdfReader(args.pdf)
    pages = [norm(page.extract_text() or "") for page in reader.pages]
    titles = json.loads(Path(args.titles).read_text(encoding="utf-8"))

    body_start = next((i for i, text in enumerate(pages) if "1.1测试结论" in text), None)
    if body_start is None:
        print("ERROR: 未找到正文起始页（1.1 测试结论）", file=sys.stderr)
        sys.exit(1)

    toc_page = next((i for i, text in enumerate(pages) if "目录" in text and "执行摘要" in text), None)

    mapping: dict[str, int] = {}
    for item in titles:
        target = norm(item["title"])
        found = None
        for index, text in enumerate(pages):
            if index == 0 or index == toc_page:
                continue
            if target in text:
                found = index + 1
                break
        if found:
            mapping[item["sec"]] = found
        else:
            print(f"WARN: 未定位 {item['title']}", file=sys.stderr)

    Path(args.output).write_text(json.dumps(mapping, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"pages={len(pages)} toc_page={None if toc_page is None else toc_page + 1} body={body_start + 1}")
    print(json.dumps(mapping, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
