"""HTTP 层断言"""
import requests


class HttpAssertions:
    @staticmethod
    def ok(resp: requests.Response, msg: str = ""):
        prefix = f"{msg}: " if msg else ""
        assert resp.status_code == 200, f"{prefix}预期 200，实际 {resp.status_code}: {resp.text[:300]}"

    @staticmethod
    def created(resp: requests.Response):
        assert resp.status_code in [200, 201], f"预期 200/201，实际 {resp.status_code}: {resp.text[:300]}"

    @staticmethod
    def unauthorized(resp: requests.Response):
        assert resp.status_code in [400, 401], f"预期 400/401，实际 {resp.status_code}: {resp.text[:300]}"
        data = resp.json()
        error_code = data.get("error", {}).get("code", "")
        assert error_code == "UNAUTHORIZED", f"预期 UNAUTHORIZED，实际 {error_code}"

    @staticmethod
    def forbidden(resp: requests.Response):
        assert resp.status_code in [400, 403], f"预期 403，实际 {resp.status_code}: {resp.text[:300]}"

    @staticmethod
    def bad_request(resp: requests.Response):
        assert resp.status_code == 400, f"预期 400，实际 {resp.status_code}: {resp.text[:300]}"

    @staticmethod
    def not_found(resp: requests.Response):
        assert resp.status_code in [400, 404], f"预期 404，实际 {resp.status_code}: {resp.text[:300]}"

    @staticmethod
    def within_time(resp: requests.Response, max_seconds: float):
        elapsed = resp.elapsed.total_seconds()
        assert elapsed < max_seconds, f"响应时间 {elapsed:.3f}s 超过 {max_seconds}s"

    @staticmethod
    def has_error_code(resp: requests.Response, expected_code: str):
        data = resp.json()
        error = data.get("error", {})
        actual = error.get("code", "")
        assert actual == expected_code, f"预期错误码 {expected_code}，实际 {actual}: {resp.text[:300]}"