"""HTTP 客户端：Session 复用、超时、GET 重试、日志、Allure 附件。

对应 pytest-learning 第 15 集 ``framework/api_client.py`` 的企业级写法：
- 一个 client 绑定一个 base_url 和一个 token
- 所有请求都记录 method/path/status/耗时
- 失败时把请求/响应挂到 Allure，便于报告里直接定位
"""
from __future__ import annotations

import json
import time
import uuid
from urllib.parse import urlparse

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from config.settings import settings
from core.logger import get_logger

try:
    import allure  # type: ignore
except ImportError:  # pragma: no cover
    allure = None


log = get_logger("api")


def _truncate(text: str, limit: int = 8192) -> str:
    return text if len(text) <= limit else text[:limit] + "\n... (truncated)"


def _json_text(data) -> str:
    try:
        return _truncate(json.dumps(data, ensure_ascii=False, indent=2))
    except (TypeError, ValueError):
        return _truncate(str(data))


def _attach(resp: requests.Response, elapsed_ms: float) -> None:
    if allure is None:
        return
    req = resp.request
    path = urlparse(req.url).path or req.url
    req_body = ""
    if req.body:
        try:
            req_body = _truncate(req.body.decode("utf-8", errors="replace"))
        except Exception:
            req_body = _truncate(str(req.body))
    try:
        resp_body = _json_text(resp.json())
    except Exception:
        resp_body = _truncate(resp.text)

    safe_headers = {
        k: ("Bearer ***" if k.lower() == "authorization" else v)
        for k, v in req.headers.items()
    }
    allure.attach(
        f"{req.method} {req.url}\n\nHeaders: {safe_headers}\n\nBody:\n{req_body or '(empty)'}",
        name=f"请求 {req.method} {path}",
        attachment_type=allure.attachment_type.TEXT,
    )
    allure.attach(
        f"Status: {resp.status_code} {resp.reason}\n"
        f"Elapsed: {elapsed_ms:.0f}ms\n\nBody:\n{resp_body or '(empty)'}",
        name=f"响应 {resp.status_code} {req.method} {path}",
        attachment_type=allure.attachment_type.TEXT,
    )


class ApiClient:
    """绑定单一服务地址的 HTTP 客户端。"""

    def __init__(
        self,
        base_url: str,
        token: str = "",
        timeout: int = None,
        allure_logging: bool = True,
    ):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout or settings.timeout
        self._allure_logging = allure_logging and allure is not None
        self.last_response = None
        self.trace_ids: list[str] = []
        self.token = token

        self.session = requests.Session()
        # 目标多为内网地址：禁用系统代理，避免 macOS 代理把请求转走
        self.session.trust_env = False
        self.session.headers.update(
            {
                "Content-Type": "application/json",
                "Accept": "application/json",
                "X-Client-Version": "1.0.0",
            }
        )
        retry = Retry(
            total=2,
            backoff_factor=0.3,
            status_forcelist=[500, 502, 503, 504],
            allowed_methods=["GET", "HEAD", "OPTIONS"],
        )
        adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=20)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)
        if token:
            self.set_token(token)

    # ---------- 认证 ----------
    def set_token(self, token: str) -> None:
        self.token = token
        self.session.headers["Authorization"] = f"Bearer {token}"

    def clear_token(self) -> None:
        self.token = ""
        self.session.headers.pop("Authorization", None)

    # ---------- 请求 ----------
    def request(self, method: str, path: str, **kwargs) -> requests.Response:
        url = path if path.startswith("http") else f"{self.base_url}{path}"
        kwargs.setdefault("timeout", self.timeout)
        headers = kwargs.pop("headers", {})
        headers.setdefault("X-Request-Id", str(uuid.uuid4()))
        started = time.perf_counter()
        resp = self.session.request(method.upper(), url, headers=headers, **kwargs)
        elapsed_ms = (time.perf_counter() - started) * 1000
        self.last_response = resp
        log.info(
            "%s %s -> %s (%dms)",
            method.upper(),
            url.replace(self.base_url, ""),
            resp.status_code,
            elapsed_ms,
        )
        try:  # 用例→接口映射 + 每用例请求/响应快照（埋点失败不影响请求）
            from core import endpoint_recorder

            try:
                body = resp.json()
            except ValueError:
                body = None
            trace_candidate = None
            if isinstance(body, dict):
                trace_candidate = body.get("traceId")
            trace_candidate = trace_candidate or resp.headers.get("X-Trace-Id")
            if trace_candidate and trace_candidate not in self.trace_ids:
                self.trace_ids.append(str(trace_candidate))
                # 模块/会话级 client 会被大量用例复用，只保留最近的 traceId 避免无界增长
                del self.trace_ids[:-10]
            endpoint_recorder.record(
                method.upper(),
                url,
                resp.status_code,
                body,
                request_payload={
                    "query": kwargs.get("params"),
                    "body": kwargs.get("json"),
                    "raw": kwargs.get("data") if not isinstance(kwargs.get("data"), (bytes, bytearray)) else "<binary>",
                },
            )
        except Exception:  # noqa: BLE001
            pass
        if self._allure_logging:
            _attach(resp, elapsed_ms)
        return resp

    def get(self, path: str, params: dict = None, **kwargs) -> requests.Response:
        return self.request("GET", path, params=params, **kwargs)

    def post(self, path: str, json: dict = None, **kwargs) -> requests.Response:
        return self.request("POST", path, json=json, **kwargs)

    def put(self, path: str, json: dict = None, **kwargs) -> requests.Response:
        return self.request("PUT", path, json=json, **kwargs)

    def delete(self, path: str, **kwargs) -> requests.Response:
        return self.request("DELETE", path, **kwargs)

    def close(self) -> None:
        self.session.close()

    def __enter__(self) -> "ApiClient":
        return self

    def __exit__(self, *exc) -> None:
        self.close()
