"""测试数据工厂：随机数据生成器"""
import random
import string
import uuid as _uuid


class DataFactory:
    """随机测试数据生成"""

    @staticmethod
    def random_email(prefix: str = "auto") -> str:
        suffix = "".join(random.choices(string.ascii_lowercase + string.digits, k=8))
        return f"{prefix}_{suffix}@test.com"

    @staticmethod
    def random_phone() -> str:
        return f"1{random.randint(30, 99)}{random.randint(10000000, 99999999)}"

    @staticmethod
    def random_string(length: int = 10) -> str:
        return "".join(random.choices(string.ascii_letters, k=length))

    @staticmethod
    def random_nickname() -> str:
        prefix = random.choice(["Tester", "QA", "Auto", "User"])
        suffix = "".join(random.choices(string.ascii_lowercase, k=4))
        return f"{prefix}_{suffix}"

    @staticmethod
    def random_int(min_val: int = 0, max_val: int = 1000) -> int:
        return random.randint(min_val, max_val)

    @staticmethod
    def uuid() -> str:
        return str(_uuid.uuid4())