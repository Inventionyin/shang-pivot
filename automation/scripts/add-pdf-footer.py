#!/usr/bin/env python3
"""给报告 PDF 添加页眉页脚与页码（封面除外）。"""
from __future__ import annotations

import argparse
import io
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from reportlab.lib.colors import HexColor
from reportlab.pdfgen import canvas

HEADER_LEFT = "Shang Pivot E2E Test Report"
HEADER_RIGHT = "INTERNAL"
FOOTER_LEFT = "SP-E2E-TR-20261027-01  ·  2026-10-27"
MARGIN_X = 42.5  # 15mm
TOP_Y = 24
BOTTOM_Y = 22


def overlay(width: float, height: float, page_no: int, total: int) -> io.BytesIO:
    buffer = io.BytesIO()
    c = canvas.Canvas(buffer, pagesize=(width, height))
    c.setStrokeColor(HexColor("#C9D3DF"))
    c.setLineWidth(0.4)
    c.line(MARGIN_X, height - TOP_Y + 6, width - MARGIN_X, height - TOP_Y + 6)
    c.line(MARGIN_X, BOTTOM_Y + 9, width - MARGIN_X, BOTTOM_Y + 9)

    c.setFillColor(HexColor("#7A8794"))
    c.setFont("Helvetica", 7.2)
    c.drawString(MARGIN_X, height - TOP_Y + 10, HEADER_LEFT)
    c.drawRightString(width - MARGIN_X, height - TOP_Y + 10, HEADER_RIGHT)

    c.setFont("Helvetica", 7.2)
    c.drawString(MARGIN_X, BOTTOM_Y, FOOTER_LEFT)
    c.drawRightString(width - MARGIN_X, BOTTOM_Y, f"Page {page_no} of {total}")
    c.save()
    buffer.seek(0)
    return buffer


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    reader = PdfReader(args.input)
    writer = PdfWriter()
    total = len(reader.pages)
    for index, page in enumerate(reader.pages):
        if index > 0:  # 封面不添加页眉页脚
            width = float(page.mediabox.width)
            height = float(page.mediabox.height)
            page.merge_page(PdfReader(overlay(width, height, index + 1, total)).pages[0])
        writer.add_page(page)

    Path(args.output).parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "wb") as handle:
        writer.write(handle)
    print(f"PDF written: {args.output} ({total} pages)")


if __name__ == "__main__":
    main()
