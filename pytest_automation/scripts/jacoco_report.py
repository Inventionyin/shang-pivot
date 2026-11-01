#!/usr/bin/env python3
"""生成 JaCoCo 运行时覆盖率摘要，并注入报告 ext/data/jacoco.json。

前置：
  bash scripts/start_backend_jacoco.sh --build
  pytest -m api ...                          # 让探针采集
  bash scripts/jacoco_dump.sh --reset

用法：
  python3 scripts/jacoco_report.py
  python3 scripts/jacoco_report.py --exec artifacts/jacoco/jacoco.exec --report-dir artifacts/allure-report
"""
from __future__ import annotations

import argparse
import csv
import json
import io
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent
JACOCO_VERSION = "0.8.12"
CLI = ROOT / ".tools" / "jacoco" / f"org.jacoco.cli-{JACOCO_VERSION}-nodeps.jar"
JAR = REPO / "backend" / "shang-server" / "target" / "shang-server-0.0.1-SNAPSHOT.jar"
OUT = ROOT / "artifacts" / "jacoco"


def extract_classes(dest: Path) -> Path:
    classes = dest / "classes"
    if classes.exists():
        import shutil
        shutil.rmtree(classes)
    classes.mkdir(parents=True, exist_ok=True)
    def extract(archive: zipfile.ZipFile, prefix: str = "") -> None:
        for name in archive.namelist():
            if not name.endswith(".class"):
                continue
            relative = name[len(prefix):] if prefix and name.startswith(prefix) else name
            if not relative.startswith("com/shangpivot/"):
                continue
            target = classes / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))

    with zipfile.ZipFile(JAR) as jar:
        extract(jar, "BOOT-INF/classes/")
        # 业务模块以嵌套 jar 形式位于 BOOT-INF/lib（shang-*.jar）
        for name in jar.namelist():
            if name.startswith("BOOT-INF/lib/shang-") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(jar.read(name))) as nested:
                    extract(nested)
    return classes


def source_roots() -> list[Path]:
    return sorted((REPO / "backend").glob("*/src/main/java"))


def run(cmd: list[str]) -> None:
    subprocess.run(cmd, check=True, cwd=ROOT)


def parse_csv(path: Path) -> dict:
    totals = {"line_covered": 0, "line_missed": 0,
              "branch_covered": 0, "branch_missed": 0,
              "instruction_covered": 0, "instruction_missed": 0}
    packages: dict[str, dict] = {}
    with path.open(encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            pkg = row["PACKAGE"]
            bucket = packages.setdefault(pkg, {"line_covered": 0, "line_missed": 0,
                                               "classes": 0})
            for key_csv, key in (
                ("LINE_COVERED", "line_covered"),
                ("LINE_MISSED", "line_missed"),
                ("BRANCH_COVERED", "branch_covered"),
                ("BRANCH_MISSED", "branch_missed"),
                ("INSTRUCTION_COVERED", "instruction_covered"),
                ("INSTRUCTION_MISSED", "instruction_missed"),
            ):
                value = int(row.get(key_csv) or 0)
                totals[key] += value
                if key.startswith("line_"):
                    bucket[key] += value
            bucket["classes"] += 1
    for bucket in packages.values():
        covered, missed = bucket["line_covered"], bucket["line_missed"]
        bucket["line_rate"] = round(covered / (covered + missed), 4) if covered + missed else 0.0
    covered = totals["line_covered"]
    missed = totals["line_missed"]
    totals["line_rate"] = round(covered / (covered + missed), 4) if covered + missed else 0.0
    totals["packages"] = dict(sorted(packages.items(), key=lambda kv: kv[1]["line_rate"]))
    return totals


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--exec", dest="execfile", default=str(OUT / "jacoco.exec"))
    parser.add_argument("--report-dir", default=str(ROOT / "artifacts" / "allure-report"))
    parser.add_argument("--dest", default=str(OUT))
    args = parser.parse_args()

    execfile = Path(args.execfile)
    dest = Path(args.dest)
    if not execfile.exists():
        raise SystemExit(f"缺少覆盖率数据：{execfile}；先 start_backend_jacoco.sh + jacoco_dump.sh")
    if not CLI.exists():
        raise SystemExit(f"缺少 JaCoCo CLI：{CLI}")
    if not JAR.exists():
        raise SystemExit(f"缺少后端 jar：{JAR}")
    dest.mkdir(parents=True, exist_ok=True)
    classes = extract_classes(dest)

    cmd = [
        "java", "-jar", str(CLI), "report", str(execfile),
        "--classfiles", str(classes),
        "--csv", str(dest / "jacoco.csv"),
        "--xml", str(dest / "jacoco.xml"),
        "--html", str(dest / "html"),
    ]
    for src in source_roots():
        cmd += ["--sourcefiles", str(src)]
    run(cmd)

    summary = parse_csv(dest / "jacoco.csv")
    summary.update({
        "generatedAt": subprocess.run(
            ["date", "+%Y-%m-%d %H:%M:%S"], capture_output=True, text=True
        ).stdout.strip(),
        "exec": str(execfile.relative_to(ROOT)),
        "jar": str(JAR.relative_to(REPO)),
        "html": str((dest / "html" / "index.html").relative_to(ROOT)),
    })
    (dest / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    ext = Path(args.report_dir) / "ext" / "data"
    ext.mkdir(parents=True, exist_ok=True)
    (ext / "jacoco.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    print(f"[done] 行覆盖率 {summary['line_rate']:.2%}（{summary['line_covered']}/{summary['line_covered'] + summary['line_missed']}）"
          f" -> {dest / 'summary.json'}")


if __name__ == "__main__":
    main()
