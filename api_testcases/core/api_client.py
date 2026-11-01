"""HTTP 客户端封装：Session 复用、自动重试、超时、Idempotency-Key、Allure 报告集成"""
import uuid
import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

from config.settings import settings

try:
    import allure  # type: ignore
    _ALLURE_AVAILABLE = True
except ImportError:
    _ALLURE_AVAILABLE = False


def _safe_json_dumps(data, max_bytes=8192):
    """安全序列化 JSON 数据，超出大小时截断"""
    try:
        import json
        text = json.dumps(data, ensure_ascii=False, indent=2)
        if len(text) > max_bytes:
            text = text[:max_bytes] + "\n... (truncated)"
        return text
    except (TypeError, ValueError):
        return str(data)[:max_bytes]


def _headers_to_text(headers, max_bytes=4096):
    """将 headers 字典转为文本，脱敏 Authorization"""
    lines = []
    for k, v in headers.items():
        if k.lower() == "authorization":
            v = v[:20] + "..." if len(v) > 20 else v
        lines.append(f"  {k}: {v}")
    text = "\n".join(lines)
    return text[:max_bytes]


def _attach_request_response(resp: requests.Response):
    """将请求/响应详情附加到 Allure 报告中"""
    if not _ALLURE_AVAILABLE:
        return

    # ---------- 请求信息 ----------
    req = resp.request
    req_body = ""
    if req.body:
        try:
            body_str = req.body.decode("utf-8", errors="replace")
            try:
                import json
                parsed = json.loads(body_str)
                req_body = json.dumps(parsed, ensure_ascii=False, indent=2)
            except (json.JSONDecodeError, ValueError):
                req_body = body_str
        except Exception:
            req_body = str(req.body)[:4096]
    if len(req_body) > 8192:
        req_body = req_body[:8192] + "\n... (truncated)"

    request_text = (
        f"Method: {req.method}\n"
        f"URL: {req.url}\n\n"
        f"Headers:\n{_headers_to_text(req.headers)}\n\n"
        f"Body:\n{req_body if req_body else '(empty)'}"
    )
    allure.attach(
        request_text,
        name="Request",
        attachment_type=allure.attachment_type.TEXT,
    )

    # ---------- 响应信息 ----------
    resp_body = ""
    try:
        resp_body = _safe_json_dumps(resp.json())
    except Exception:
        resp_body = resp.text[:8192]

    response_text = (
        f"Status: {resp.status_code} {resp.reason}\n"
        f"Time: {resp.elapsed.total_seconds():.3f}s\n\n"
        f"Headers:\n{_headers_to_text(resp.headers)}\n\n"
        f"Body:\n{resp_body if resp_body else '(empty)'}"
    )
    allure.attach(
        response_text,
        name="Response",
        attachment_type=allure.attachment_type.TEXT,
    )


class ApiClient:
    """API 客户端

    Args:
        base_url: API 基础地址
        token: Bearer Token
        timeout: 请求超时（秒）
        allure_logging: 是否自动将请求/响应详情写入 Allure 报告
    """

    def __init__(
        self,
        base_url: str = None,
        token: str = None,
        timeout: int = None,
        allure_logging: bool = True,
    ):
        self.base_url = (base_url or settings.base_url).rstrip("/")
        self.timeout = timeout or settings.timeout
        self._allure_logging = allure_logging and _ALLURE_AVAILABLE
        self.last_response = None
        self.session = requests.Session()
        # 目标均为内网地址：禁用环境/系统代理（macOS 系统代理会拦内网请求，造成偶发非 JSON 响应）
        self.session.trust_env = False
        self._setup_retry()
        self.session.headers.update({
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Client-Version": "1.0.0",
            "X-Request-Id": str(uuid.uuid4()),
        })
        if token:
            self.set_token(token)

    def _setup_retry(self):
        retry = Retry(
            total=2,
            backoff_factor=0.5,
            status_forcelist=[500, 502, 503, 504],
            allowed_methods=["GET", "HEAD", "OPTIONS"],
        )
        adapter = HTTPAdapter(max_retries=retry, pool_connections=10, pool_maxsize=20)
        self.session.mount("http://", adapter)
        self.session.mount("https://", adapter)

    def set_token(self, token: str):
        self.session.headers["Authorization"] = f"Bearer {token}"

    def clear_token(self):
        self.session.headers.pop("Authorization", None)

    def _url(self, path: str) -> str:
        return f"{self.base_url}{path}"

    # -------------- HTTP 方法（含 Allure 集成） --------------

    def get(self, path: str, params: dict = None, **kwargs) -> requests.Response:
        step_name = f"GET {path}"
        if params:
            step_name += f"?{_safe_json_dumps(params)}"
        if self._allure_logging and _ALLURE_AVAILABLE:
            with allure.step(step_name):
                resp = self.session.get(self._url(path), params=params, timeout=self.timeout, **kwargs)
                _attach_request_response(resp)
                self.last_response = resp
                return resp
        resp = self.session.get(self._url(path), params=params, timeout=self.timeout, **kwargs)
        self.last_response = resp
        return resp

    def post(self, path: str, json: dict = None, idempotency_key: str = None, **kwargs) -> requests.Response:
        headers = kwargs.pop("headers", {})
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        if self._allure_logging and _ALLURE_AVAILABLE:
            with allure.step(f"POST {path}"):
                resp = self.session.post(self._url(path), json=json, headers=headers, timeout=self.timeout, **kwargs)
                _attach_request_response(resp)
                self.last_response = resp
                return resp
        resp = self.session.post(self._url(path), json=json, headers=headers, timeout=self.timeout, **kwargs)
        self.last_response = resp
        return resp

    def put(self, path: str, json: dict = None, **kwargs) -> requests.Response:
        if self._allure_logging and _ALLURE_AVAILABLE:
            with allure.step(f"PUT {path}"):
                resp = self.session.put(self._url(path), json=json, timeout=self.timeout, **kwargs)
                _attach_request_response(resp)
                self.last_response = resp
                return resp
        resp = self.session.put(self._url(path), json=json, timeout=self.timeout, **kwargs)
        self.last_response = resp
        return resp

    def delete(self, path: str, **kwargs) -> requests.Response:
        if self._allure_logging and _ALLURE_AVAILABLE:
            with allure.step(f"DELETE {path}"):
                resp = self.session.delete(self._url(path), timeout=self.timeout, **kwargs)
                _attach_request_response(resp)
                self.last_response = resp
                return resp
        resp = self.session.delete(self._url(path), timeout=self.timeout, **kwargs)
        self.last_response = resp
        return resp

    def close(self):
        self.session.close()
