#!/usr/bin/env python3
"""Small, credential-free-on-disk ZenTao synchronizer.

ZenTao 22.6's IP entry uses a dynamic MD5 login challenge.  This client keeps
credentials in environment variables only and creates interface cases through
the product's normal form.  It writes a local manifest so retries are idempotent
from the automation side; no case or password is stored in source code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import html
import mimetypes
import os
import re
import sys
import time
from pathlib import Path
from typing import Any

import requests


class ZenTaoClient:
    def __init__(self, base_url: str, username: str, password: str, product_id: int = 1):
        self.base = base_url.rstrip("/")
        self.username = username
        self.password = password
        self.product_id = product_id
        self.session = requests.Session()
        self.session.trust_env = False  # 内网地址直连，绕过系统/环境代理
        self.login()

    def _request_with_retry(self, method: str, url: str, *, retries: int = 3, **kwargs) -> requests.Response:
        """Retry transient network failures without hiding a real HTTP response.

        The desktop proxy occasionally drops long-running HTTP requests.  A
        bounded retry here lets a repair run continue while preserving the
        original response for application-level errors.
        """
        last_error: Exception | None = None
        for attempt in range(1, retries + 1):
            try:
                return self.session.request(method, url, **kwargs)
            except (requests.Timeout, requests.ConnectionError) as exc:
                last_error = exc
                if attempt == retries:
                    break
                time.sleep(min(2 * attempt, 5))
        raise RuntimeError(f"request failed after {retries} attempts: {method} {url}: {last_error}") from last_error

    @staticmethod
    def _session_lost(response: "requests.Response") -> bool:
        """判定会话是否失效。

        不能用「页面文本包含"用户登录"」——用例标题/步骤里出现该词会误判，
        进而对已登录会话重复登录（禅道返回非 JSON 页面，直接崩）。改判 URL 是否被重定向到登录页。
        """
        url = str(getattr(response, "url", ""))
        return "/user-login" in url or "登录已超时" in response.text

    def login(self) -> None:
        page = self._request_with_retry("GET", self.base + "/", timeout=20)
        page.raise_for_status()
        rand = self._request_with_retry("GET", self.base + "/user-refreshRandom.html", timeout=20).text.strip()
        password = hashlib.md5((hashlib.md5(self.password.encode()).hexdigest() + rand).encode()).hexdigest()
        response = self._request_with_retry(
            "POST",
            page.url,
            data={
                "account": self.username,
                "password": password,
                "passwordStrength": 1,
                "referer": "/",
                "verifyRand": rand,
                "keepLogin": 1,
                "captcha": "",
            },
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": page.url},
            timeout=20,
        )
        try:
            result = response.json()
        except ValueError as exc:
            raise RuntimeError(f"ZenTao login did not return JSON: HTTP {response.status_code}") from exc
        if result.get("result") != "success":
            raise RuntimeError(f"ZenTao login failed: {result.get('message', 'unknown error')}")
        check = self._request_with_retry("GET", self.base + f"/testcase-browse-{self.product_id}.html", timeout=20)
        if self._session_lost(check):
            raise RuntimeError("ZenTao session was not established")

    def _post_case(self, case: dict[str, str]) -> None:
        url = self.base + f"/testcase-create-{self.product_id}-0-0.html"
        priority = {"P0": "1", "P1": "2", "P2": "3", "P3": "4"}.get(case["priority"], "3")
        kind = "interface"
        stage = "smoke" if case["priority"] == "P0" else "feature"
        data = {
            "product": str(self.product_id),
            "module": "0",
            "story": "",
            "scene": "0",
            "type": kind,
            "stage": stage,
            "title": case["title"],
            "pri": priority,
            "precondition": case["precondition"].replace("<br>", "\n"),
            "steps[1]": case["steps"],
            "stepType[1]": "step",
            "expects[1]": f"预期HTTP={case['expected_http']}；预期业务码={case['expected_code']}；断言={case['assertions']}",
            "keywords": f"API-AUTO,{case['case_id']}",
            "fileList": "",
            "script": "",
        }
        response = self._request_with_retry(
            "POST",
            url,
            data=data,
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": url},
            timeout=30,
        )
        if "登录已超时" in response.text or '"load":"login"' in response.text:
            self.login()
            response = self._request_with_retry("POST", url, data=data, headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=30)
        if response.status_code != 200 or '"result":"success"' not in response.text:
            raise RuntimeError(f"case {case['case_id']} create failed: HTTP {response.status_code} {response.text[:300]}")

    def _edit_case(self, case: dict[str, str], remote: dict[str, Any]) -> None:
        case_id = int(remote["case_id"])
        url = self.base + f"/testcase-edit-{case_id}-false-0.html"
        page = self._request_with_retry("GET", url, timeout=30)
        if self._session_lost(page):
            self.login()
            page = self._request_with_retry("GET", url, timeout=30)
        uid_match = re.search(r'name="uid"[^>]*value="([^"]+)"', page.text)
        uid = uid_match.group(1) if uid_match else ""
        priority = {"P0": "1", "P1": "2", "P2": "3", "P3": "4"}.get(case["priority"], "3")
        data = {
            "product": str(self.product_id), "module": "0", "story": "", "scene": "0",
            "type": "interface", "stage[]": "smoke" if case["priority"] == "P0" else "feature",
            "title": case["title"], "pri": priority, "status": "normal",
            "precondition": case["precondition"].replace("<br>", "\n"),
            "steps[1]": case["steps"], "stepType[1]": "step",
            "expects[1]": f"预期HTTP={case['expected_http']}；预期业务码={case['expected_code']}；断言={case['assertions']}",
            "keywords": f"API-AUTO,{case['case_id']}", "uid": uid, "case": str(case_id),
            "version": str(remote.get("version", 1)), "comment": "", "script": "", "fileList": "[]",
        }
        response = self._request_with_retry("POST", url, data=data, headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=30)
        if "登录已超时" in response.text or '"load":"login"' in response.text:
            self.login()
            response = self._request_with_retry("POST", url, data=data, headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=30)
        if response.status_code != 200 or '"result":"success"' not in response.text:
            raise RuntimeError(f"case {case['case_id']} repair failed: HTTP {response.status_code} {response.text[:500]}")

    def case_id_map(self) -> dict[str, dict[str, Any]]:
        """Read all API-AUTO cases and map stable IDs to ZenTao IDs."""
        records = self.case_records()
        return {
            stable_id: {
                "case_id": int(record["id"].removeprefix("case_")),
                "version": int(record.get("version", 1)),
                "title": record.get("title", ""),
                "keywords": record.get("keywords", ""),
            }
            for stable_id, record in records.items()
        }

    def case_records(self) -> dict[str, dict[str, Any]]:
        """Parse ZenTao's embedded table records and key only by keywords.

        The stable key is the exact ``API-AUTO,<stable-id>`` keyword.  The
        numeric case id is read from the record's ``id`` field only after that
        keyword has selected the record; titles are never used for matching.
        """
        url = self.base + f"/testcase-browse-{self.product_id}--all-0--sort_asc,id_desc-0-1000-1-0-testcase-0.html"
        response = self._request_with_retry("GET", url, timeout=30)
        if self._session_lost(response):
            self.login()
            response = self._request_with_retry("GET", url, timeout=30)
        response.raise_for_status()
        text = html.unescape(response.text)
        found: dict[str, dict[str, Any]] = {}
        decoder = json.JSONDecoder()
        cursor = 0
        while True:
            start = text.find('{"id":"case_', cursor)
            if start < 0:
                break
            try:
                record, end = decoder.raw_decode(text, start)
            except json.JSONDecodeError:
                cursor = start + 1
                continue
            cursor = end
            if not isinstance(record, dict) or not str(record.get("id", "")).startswith("case_"):
                continue
            keywords = str(record.get("keywords", ""))
            prefix = "API-AUTO,"
            if not keywords.startswith(prefix):
                continue
            stable_id = keywords[len(prefix):].split(",", 1)[0].strip()
            if stable_id:
                found[stable_id] = record
        return found

    def record_case_result(self, stable_id: str, case: dict[str, Any], outcome: str, detail: str) -> dict[str, Any]:
        # skipped 不能写 n/a：实测禅道会把 n/a 呈现为 pass，造成"未执行=通过"的假绿。
        # 统一写 blocked，与已执行结果区分开。
        result = {"passed": "pass", "pass": "pass", "failed": "fail", "fail": "fail",
                  "blocked": "blocked", "skipped": "blocked"}.get(outcome, "blocked")
        case_id = int(case["case_id"])
        version = int(case.get("version", 1))
        url = self.base + f"/testtask-runCase-0-{case_id}-{version}.html"
        response = self._request_with_retry(
            "POST",
            url,
            data={"result[0]": result, "real[0]": detail[:8000], "case": case_id, "version": version},
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": url},
            timeout=30,
        )
        if "登录已超时" in response.text or '"load":"login"' in response.text:
            self.login()
            response = self._request_with_retry("POST", url, data={"result[0]": result, "real[0]": detail[:8000], "case": case_id, "version": version}, headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=30)
        if response.status_code != 200 or '"result":false' in response.text:
            raise RuntimeError(f"result sync failed for {stable_id}: HTTP {response.status_code} {response.text[:500]}")
        return {"stable_id": stable_id, "zentao_case_id": case_id, "result": result}

    def verify_last_run_results(self, synced: list[dict[str, Any]],
                                pre: dict[str, str] | None = None) -> dict[str, Any]:
        """回读 lastRunResult：写入成功后从禅道重读并逐条核对。

        - pass/fail/blocked：必须等于写入值
        - n/a（skipped）：禅道不更新该字段，要求"保持写入前的原值"（pre 快照）
        - 同一用例一轮内多次写入：以最后一次为准
        """
        if not synced:
            return {"checked": 0, "ok": 0, "mismatch": [], "absent": []}
        pre = pre or {}
        records = self.case_records()
        # 同一 stable_id 以最后一次写入为准
        latest: dict[str, str] = {}
        for item in synced:
            latest[item["stable_id"]] = item["result"]
        ok, mismatch, absent = 0, [], []
        for stable_id, expected in latest.items():
            record = records.get(stable_id)
            if record is None:
                absent.append(stable_id)
                continue
            actual = str(record.get("lastRunResult", "")).strip().lower()
            if expected == "n/a":
                verified = actual == pre.get(stable_id, actual)
            else:
                verified = actual == expected
            if verified:
                ok += 1
            else:
                mismatch.append({"stable_id": stable_id, "expected": expected,
                                 "actual": actual or "(空)", "before": pre.get(stable_id, "?"),
                                 "lastRunDate": str(record.get("lastRunDate", ""))})
        return {"checked": len(latest), "ok": ok, "mismatch": mismatch, "absent": absent}

    def bug_records(self) -> list[dict[str, Any]]:
        """解析禅道 Bug 列表（browse 页内嵌 data 数组），返回 [{id,title,keywords,status}]。"""
        url = self.base + f"/bug-browse-{self.product_id}--all-0--sort_asc,id_desc-0-200-1-0-bug-0.html"
        response = self._request_with_retry("GET", url, timeout=30)
        if self._session_lost(response):
            self.login()
            response = self._request_with_retry("GET", url, timeout=30)
        response.raise_for_status()
        text = html.unescape(response.text)
        marker = '"data":['
        start = text.find(marker)
        if start < 0:
            return []
        try:
            records, _ = json.JSONDecoder().raw_decode(text[start + len(marker) - 1:])
        except json.JSONDecodeError:
            return []
        return [r for r in records if isinstance(r, dict) and "id" in r and "title" in r]

    def _upload_attachment(self, uid: str, path: Path) -> dict[str, Any]:
        url = self.base + f"/file-ajaxUpload-{uid}.html"
        mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
        with path.open("rb") as stream:
            response = self._request_with_retry(
                "POST", url, files={"imgFile": (path.name, stream, mime)}, data={"uid": uid},
                headers={"X-Requested-With": "XMLHttpRequest", "Referer": self.base + f"/bug-create-{self.product_id}-0.html"},
                timeout=60,
            )
        if response.status_code != 200:
            raise RuntimeError(f"attachment upload failed: {path.name} HTTP {response.status_code}")
        try:
            payload = response.json()
        except ValueError as exc:
            raise RuntimeError(f"attachment upload returned non-JSON: {path.name}") from exc
        if payload.get("error") not in (0, "0") or not payload.get("url"):
            raise RuntimeError(f"attachment upload rejected: {path.name} {payload}")
        match = re.search(r"/file-read-(\d+)", str(payload.get("url")))
        return {"id": int(match.group(1)) if match else None, "name": path.name, "url": payload.get("url")}

    def create_bug(self, title: str, steps: str, attachment_paths: list[Path], severity: str = "3", priority: str = "3") -> dict[str, Any]:
        page_url = self.base + f"/bug-create-{self.product_id}-0.html"
        page = self._request_with_retry("GET", page_url, timeout=30)
        if self._session_lost(page):
            self.login()
            page = self._request_with_retry("GET", page_url, timeout=30)
        uid_match = re.search(r'name="uid"[^>]*value="([^"]+)"', page.text)
        uid = uid_match.group(1) if uid_match else hashlib.md5(f"{title}-{time.time()}".encode()).hexdigest()[:12]
        data = {
            "product": str(self.product_id), "module": "0", "project": "0", "execution": "0",
            "openedBuild[]": "trunk", "assignedTo": os.getenv("ZENTAO_BUG_ASSIGNED_TO", "dev"),
            "title": title[:255], "type": "codeerror", "severity": severity, "pri": priority,
            "steps": steps[:30000], "story": "0", "task": "0", "case": "0", "result": "0", "testtask": "0",
            "fromCase": "0", "caseVersion": "0", "uid": uid, "fileList": "[]",
            "keywords": "API-AUTO,automated-failure",
        }
        # 附件与表单同请求 multipart 提交（禅道 saveUpload 读取 $_FILES['files']）
        opened = []
        files_payload = []
        try:
            for path in attachment_paths:
                if not path.exists():
                    continue
                stream = path.open("rb")
                opened.append(stream)
                mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
                files_payload.append(("files[]", (path.name, stream, mime)))
            response = self._request_with_retry(
                "POST", page_url, data=data, files=files_payload or None,
                headers={"X-Requested-With": "XMLHttpRequest", "Referer": page_url}, timeout=120,
            )
        finally:
            for stream in opened:
                stream.close()
        if "登录已超时" in response.text or '"load":"login"' in response.text:
            self.login()
            response = self._request_with_retry("POST", page_url, data=data, headers={"X-Requested-With": "XMLHttpRequest", "Referer": page_url}, timeout=60)
        if response.status_code != 200 or '"result":false' in response.text:
            raise RuntimeError(f"bug create failed: HTTP {response.status_code} {response.text[:500]}")
        # 建单响应不含 id（{"result":"success","load":...}），从列表按标题回查（取最新一条）
        bug_id = None
        try:
            matches = [rec for rec in self.bug_records() if rec.get("title") == title]
            if matches:
                bug_id = max(int(rec["id"]) for rec in matches)
        except Exception:
            pass
        return {"bug_id": bug_id, "title": title,
                "attachments": [str(p) for p in attachment_paths if p.exists()]}

    def update_bug(self, bug_id: int, title: str | None = None, steps: str | None = None,
                   comment: str = "") -> dict[str, Any]:
        """按表单回放方式更新已上传 Bug（标题/正文/备注）。"""
        url = self.base + f"/bug-edit-{bug_id}.html"
        page = self._request_with_retry("GET", url, timeout=30)
        if self._session_lost(page) or '"load":"login"' in page.text:
            self.login()
            page = self._request_with_retry("GET", url, timeout=30)
        t = html.unescape(page.text)
        data: dict[str, str] = {}
        for tag in re.findall(r"<input\b[^>]*>", t):
            name_m = re.search(r'name="([^"]+)"', tag)
            if not name_m:
                continue
            name = name_m.group(1)
            if not re.match(r"^[A-Za-z_][A-Za-z0-9_\[\]]*$", name):
                continue
            typ = (re.search(r'type="([^"]+)"', tag).group(1) if re.search(r'type="([^"]+)"', tag) else "text")
            if typ in ("checkbox", "radio") and "checked" not in tag:
                continue
            if typ in ("submit", "button", "file", "reset"):
                continue
            val = re.search(r'value="([^"]*)"', tag)
            data[name] = val.group(1) if val else ""
        for name, content in re.findall(r"<textarea\b[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</textarea>", t, re.S):
            data[name] = content
        for name, body in re.findall(r"<select\b[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</select>", t, re.S):
            sel = re.search(r'<option[^>]*selected[^>]*value="([^"]*)"', body) or \
                re.search(r'<option[^>]*value="([^"]*)"[^>]*selected', body)
            data[name] = sel.group(1) if sel else ""
        # picker 型字段（ZUI 组件不走表单回放）：从页面内嵌 JSON 取当前值兜底
        for key, default in (("openedBuild", "trunk"), ("assignedTo", "dev")):
            if f"{key}[]" not in data and key not in data:
                m = re.search(rf'"{key}":"([^"]*)"', t)
                data[f"{key}[]"] = (m.group(1) if m and m.group(1) else default)
        if title:
            data["title"] = title
        if steps:
            data["steps"] = steps
        if comment:
            data["comment"] = comment
        response = self._request_with_retry(
            "POST", url, data=data,
            headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=60)
        if "登录已超时" in response.text or '"load":"login"' in response.text:
            self.login()
            response = self._request_with_retry(
                "POST", url, data=data,
                headers={"X-Requested-With": "XMLHttpRequest", "Referer": url}, timeout=60)
        if response.status_code != 200 or '"result":false' in response.text or '"result":"fail"' in response.text:
            raise RuntimeError(f"bug update failed: HTTP {response.status_code} {response.text[:300]}")
        return {"bug_id": bug_id, "ok": True, "response": response.text[:120]}

    def create_cases(self, cases: list[dict[str, str]], manifest_path: Path, dry_run: bool = False) -> dict[str, Any]:
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
        created = skipped = 0
        for index, case in enumerate(cases, 1):
            case_id = case["case_id"]
            if case_id in manifest:
                skipped += 1
                continue
            if dry_run:
                print(f"DRY-RUN {index}/{len(cases)} {case_id} {case['title']}")
                continue
            title = case["title"]
            case = {**case, "title": title}
            self._post_case(case)
            manifest[case_id] = {"title": title, "product_id": self.product_id, "created_at": int(time.time())}
            manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
            time.sleep(0.15)
            created += 1
            if created % 20 == 0:
                print(f"created={created} skipped={skipped} total={len(cases)}")
        return {"created": created, "skipped": skipped, "total": len(cases), "manifest": str(manifest_path)}

    def repair_cases(
        self,
        cases: list[dict[str, str]],
        repair_manifest_path: Path,
        dry_run: bool = False,
        start_index: int = 1,
        max_cases: int | None = None,
    ) -> dict[str, Any]:
        """Repair ZenTao's dynamic StepsEditor fields with checkpoints."""
        mapping = self.case_id_map()
        repair_manifest_path.parent.mkdir(parents=True, exist_ok=True)
        try:
            checkpoint = json.loads(repair_manifest_path.read_text(encoding="utf-8"))
        except (FileNotFoundError, json.JSONDecodeError):
            checkpoint = {}
        repaired = skipped = missing = failed = 0
        end_index = len(cases) if max_cases is None else min(len(cases), start_index - 1 + max_cases)
        for index, case in enumerate(cases, 1):
            if index < start_index or index > end_index:
                continue
            # 指纹覆盖全部"会被推送到禅道"的字段（含预期/断言），否则字段变更不会触发重推
            # 指纹必须覆盖"实际推送到禅道的内容"（precondition 的 <br> 在推送时转成换行，
            # 若用原始值算指纹，转换规则变更不会触发重推）
            fingerprint = hashlib.md5("\x1f".join([
                case["title"], case["precondition"].replace("<br>", "\n"), case["steps"],
                case.get("expected_http", ""), case.get("expected_code", ""), case.get("assertions", ""),
            ]).encode("utf-8")).hexdigest()
            prior = checkpoint.get(case["case_id"], {})
            if prior.get("status") == "repaired" and prior.get("fp") == fingerprint:
                skipped += 1
                continue
            remote = mapping.get(case["case_id"])
            if not remote:
                missing += 1
                checkpoint[case["case_id"]] = {"status": "missing", "index": index, "updated_at": int(time.time())}
                repair_manifest_path.write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2), encoding="utf-8")
                continue
            try:
                if not dry_run:
                    self._edit_case(case, remote)
                    time.sleep(0.15)
                checkpoint[case["case_id"]] = {
                    "status": "repaired" if not dry_run else "dry-run", "index": index,
                    "zentao_case_id": remote["case_id"], "fp": fingerprint, "updated_at": int(time.time()),
                }
                repaired += 1
            except Exception as exc:
                failed += 1
                checkpoint[case["case_id"]] = {
                    "status": "failed", "index": index,
                    "zentao_case_id": remote["case_id"], "error": str(exc)[:1000],
                    "updated_at": int(time.time()),
                }
                print(f"repair failed {index}/{len(cases)} {case['case_id']}: {exc}")
            repair_manifest_path.write_text(json.dumps(checkpoint, ensure_ascii=False, indent=2), encoding="utf-8")
            processed = repaired + skipped + missing + failed
            if processed % 20 == 0:
                print(f"repaired={repaired} skipped={skipped} failed={failed} missing={missing} total={len(cases)}")
        return {
            "repaired": repaired, "skipped": skipped, "failed": failed,
            "missing": missing, "total": len(cases), "mapping_size": len(mapping),
            "checkpoint": str(repair_manifest_path), "range": [start_index, end_index],
        }


def parse_inventory(path: Path) -> list[dict[str, str]]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.startswith("| ") or line.startswith("| 用例ID") or line.startswith("|---"):
            continue
        cells = [c.strip().replace("\\|", "|") for c in line.split("|")[1:-1]]
        if len(cells) != 14 or not cells[0]:
            continue
        cases.append({
            "case_id": cells[0], "priority": cells[1], "kind": cells[2], "title": cells[3],
            "precondition": cells[4], "method_url": cells[5], "headers": cells[6],
            "payload": cells[7], "steps": cells[8].replace("<br>", "\n"),
            "expected_http": cells[9], "expected_code": cells[10], "assertions": cells[11],
            "cleanup": cells[12], "notes": cells[13],
        })
    return cases


def load_results(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    return payload.get("results", [])


def evidence_files(result: dict[str, Any]) -> list[Path]:
    """可上传的证据附件（禅道格式白名单内：json 转存为 .evidence.txt 后附加）。"""
    evidence = Path(result["evidence_json"]) if result.get("evidence_json") else None
    if not evidence:
        return []
    files: list[Path] = []
    if evidence.exists():
        txt = evidence.with_suffix(".evidence.txt")
        if not txt.exists():
            try:
                txt.write_text(evidence.read_text(encoding="utf-8")[:60000], encoding="utf-8")
            except Exception:
                pass
        if txt.exists():
            files.append(txt)
    files += [p for p in (evidence.with_suffix(".steps.txt"), evidence.with_suffix(".png")) if p.exists()]
    return files


_CASE_ROWS: dict[str, dict[str, str]] | None = None


def _case_rows() -> dict[str, dict[str, str]]:
    """稳定 ID → 清单行（延迟加载，供 Bug 内容生成）。"""
    global _CASE_ROWS
    if _CASE_ROWS is None:
        inventory = Path(__file__).parents[1] / "test_cases_inventory.md"
        _CASE_ROWS = {row["case_id"]: row for row in parse_inventory(inventory)} if inventory.exists() else {}
    return _CASE_ROWS


def evidence_source(result: dict[str, Any]) -> Path | None:
    """原始证据 JSON（用于提取 longrepr，不用于上传）。"""
    return Path(result["evidence_json"]) if result.get("evidence_json") else None


def sync_results(client: ZenTaoClient, results: list[dict[str, Any]]) -> dict[str, Any]:
    mapping = client.case_id_map()
    # 预读 lastRunResult（禅道对 n/a 不回写该字段，校验 n/a 时要求"保持原值"）
    planned = {sid for r in results for sid in r.get("case_ids", []) if sid in mapping}
    pre: dict[str, str] = {}
    try:
        for sid, rec in client.case_records().items():
            if sid in planned:
                pre[sid] = str(rec.get("lastRunResult", "")).strip().lower()
    except Exception:
        pre = {}
    synced, missing = [], []
    for result in results:
        outcome = result.get("outcome", "skipped")
        detail = f"pytest nodeid={result.get('nodeid')} outcome={outcome} duration={result.get('duration_seconds', 0):.3f}s"
        for stable_id in result.get("case_ids", []):
            case = mapping.get(stable_id)
            if not case:
                missing.append(stable_id)
                continue
            synced.append(client.record_case_result(stable_id, case, outcome, detail))
    # 回读校验：写入后从禅道重读 lastRunResult，确认与本次写入一致（闭环验证）
    verify = client.verify_last_run_results(synced, pre)
    return {"synced": synced, "missing_case_ids": sorted(set(missing)),
            "mapping_size": len(mapping), "verify": verify}


def _summarize_symptom(longrepr: str) -> str:
    if "too many 500 error responses" in longrepr:
        return "接口多次返回 500"
    if "INSUFFICIENT_STOCK" in longrepr:
        return "库存不足（环境）"
    m = re.search(r"AssertionError:\s*(.{0,80})", longrepr)
    if m:
        text = re.sub(r"\s+", " ", m.group(1)).strip()
        if text.startswith("assert "):
            text = text[len("assert "):]
        text = re.split(r"[:：{[]", text)[0].strip()
        return ("断言不符：" + text[:36]) if text else "断言不符（详见正文）"
    m = re.search(r"(预期 \d+，实际 \d+)", longrepr)
    if m:
        return "断言不符：" + m.group(1)
    return "自动化断言失败"


def _extract_actual(longrepr: str) -> str:
    picked = []
    for line in longrepr.splitlines():
        if line.lstrip().startswith("E ") or "AssertionError" in line or "ResponseError" in line:
            picked.append(line.strip())
        if len(picked) >= 6:
            break
    text = "\n".join(picked) or "\n".join(longrepr.splitlines()[:4])
    text = re.sub(r"/Users/[^\s:]+", "<本机路径>", text)
    return text[:600] + ("…" if len(text) > 600 else "")


def compose_bug_content(stable_id: str | None, nodeid: str, longrepr: str,
                        case_rows: dict[str, dict[str, str]], base_url: str = "") -> tuple[str, str]:
    """按测试用例规范生成 Bug 标题与正文（前置/步骤/预期/实际/运行信息）。"""
    row = case_rows.get(stable_id) if stable_id else None
    symptom = _summarize_symptom(longrepr)
    if row:
        title = f"[自动化] {row['title']}（{symptom}）"
    else:
        title = f"[自动化] {nodeid.rsplit('::', 1)[-1]}（{symptom}）"
    precondition = (row["precondition"].replace("<br>", "\n") if row
                    else f"环境：BASE_URL={base_url or '（见运行信息）'}；账号：运行时注入（不落盘）；数据：见测试步骤")
    test_steps = row["steps"].replace("<br>", "\n") if row else f"1. 执行 pytest 用例：{nodeid}"
    expected = (f"HTTP={row['expected_http']}；code={row['expected_code']}\n断言：{row['assertions']}"
                if row else "见对应禅道用例的预期列")
    body = "\n".join([
        "【前置条件】", precondition, "",
        "【测试步骤】", test_steps, "",
        "【预期结果】", expected, "",
        "【实际结果】", _extract_actual(longrepr), "",
        "【运行信息】",
        f"执行时间：{time.strftime('%Y-%m-%d %H:%M:%S')}；环境：{base_url or '-'}",
        f"nodeid：{nodeid}",
        f"关联禅道用例：{stable_id}" if stable_id else "关联禅道用例：未映射",
        "说明：本 Bug 由自动化失败自动创建，附脱敏请求/响应与证据附件；修复后重跑全量回归，结果自动回写用例并闭环。",
    ])
    return title[:110], body


# 环境/数据类失败特征：不作为产品缺陷建单（避免噪音淹没真实缺陷）
ENV_NOISE = re.compile(
    r"INSUFFICIENT_STOCK|CART_EMPTY|RetryError|Max retries exceeded|ConnectionError|"
    r"Connection refused|SKU_REQUIRED|令牌失效|UNAUTHORIZED.*令牌")


def create_bugs(client: ZenTaoClient, results: list[dict[str, Any]], manifest_path: Path, dry_run: bool = False) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {}
    created, skipped, environmental = [], [], []
    for result in results:
        if result.get("outcome") not in {"failed", "fail"}:
            continue
        evidence = evidence_files(result)
        source = evidence_source(result)
        longrepr = ""
        if source and source.exists():
            try:
                longrepr = json.loads(source.read_text(encoding="utf-8")).get("longrepr", "")
            except Exception:
                longrepr = source.read_text(encoding="utf-8")[:5000]
        stable = "|".join(result.get("case_ids", [])) or result.get("nodeid", "unknown")
        # 指纹按"用例/节点"去重（不用失败文本，避免同一问题的措辞变化重复建单）
        fingerprint = hashlib.sha256(f"{stable}|{result.get('nodeid', '')}".encode()).hexdigest()[:20]
        if fingerprint in manifest:
            skipped.append(fingerprint)
            continue
        if ENV_NOISE.search(longrepr):
            environmental.append({"nodeid": result.get("nodeid"), "fingerprint": fingerprint,
                                  "reason": ENV_NOISE.search(longrepr).group(0)[:40]})
            continue
        stable_id = (result.get("case_ids") or [None])[0]
        rows = _case_rows()
        title, steps = compose_bug_content(
            stable_id, result.get("nodeid", ""), longrepr, rows, os.getenv("BASE_URL", ""))
        row = rows.get(stable_id) if stable_id else None
        level = {"P0": "2", "P1": "3", "P2": "4", "P3": "4"}.get((row or {}).get("priority", "P1"), "3")
        if dry_run:
            created.append({"fingerprint": fingerprint, "title": title, "attachments": [str(p) for p in evidence]})
            continue
        bug = client.create_bug(title, steps, evidence, severity=level, priority=level)
        manifest[fingerprint] = bug
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
        created.append(bug)
    return {"created": created, "skipped": skipped, "environmental": environmental,
            "failed_results": len([r for r in results if r.get('outcome') in {'failed', 'fail'}])}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=["upload-cases", "repair-cases", "sync-results", "create-bugs"])
    parser.add_argument("--inventory", type=Path, default=Path(__file__).parents[1] / "test_cases_inventory.md")
    parser.add_argument("--manifest", type=Path, default=Path("artifacts/zentao_case_manifest.json"))
    parser.add_argument("--repair-manifest", type=Path, default=Path("artifacts/zentao_repair_manifest.json"))
    parser.add_argument("--start-index", type=int, default=1)
    parser.add_argument("--max-cases", type=int, default=None)
    parser.add_argument("--results", type=Path, default=Path("artifacts/automation_results.json"))
    parser.add_argument("--bug-manifest", type=Path, default=Path("artifacts/zentao_bug_manifest.json"))
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--skip-lint", action="store_true", help="跳过上传前 lint 门禁（不推荐）")
    args = parser.parse_args()
    # lint 门禁：upload-cases / repair-cases 前必须通过（rule.md §11）
    if args.command in ("upload-cases", "repair-cases") and not args.skip_lint:
        import subprocess
        lint = Path(__file__).with_name("case_lint.py")
        rc = subprocess.run([sys.executable, str(lint), "--inventory", str(args.inventory)]).returncode
        if rc != 0:
            raise SystemExit(f"lint 门禁未通过（退出码 {rc}），已中止 {args.command}；修复后重试，或 --skip-lint 强制跳过")
    base = os.getenv("ZENTAO_BASE_URL", "http://111.170.33.2:60082")
    user = os.getenv("ZENTAO_USER")
    password = os.getenv("ZENTAO_PASSWORD")
    if not user or not password:
        raise SystemExit("Set ZENTAO_USER and ZENTAO_PASSWORD at runtime; credentials are never read from files")
    client = ZenTaoClient(base, user, password, int(os.getenv("ZENTAO_PRODUCT_ID", "1")))
    if args.command in {"upload-cases", "repair-cases"}:
        cases = parse_inventory(args.inventory)
        if not cases:
            raise SystemExit("No cases found in inventory")
        result = client.create_cases(cases, args.manifest, args.dry_run) if args.command == "upload-cases" else client.repair_cases(
            cases, args.repair_manifest, args.dry_run, args.start_index, args.max_cases
        )
    elif args.command == "sync-results":
        if not args.results.exists():
            raise SystemExit(f"Results file not found: {args.results}")
        result = sync_results(client, load_results(args.results))
    else:
        if not args.results.exists():
            raise SystemExit(f"Results file not found: {args.results}")
        result = create_bugs(client, load_results(args.results), args.bug_manifest, args.dry_run)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
