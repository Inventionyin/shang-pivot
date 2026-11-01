"""结构化日志配置"""
import logging
import sys


def setup_logger(name: str = "api_test", level: str = "INFO") -> logging.Logger:
    logger = logging.getLogger(name)
    logger.setLevel(level)

    if not logger.handlers:
        handler = logging.StreamHandler(sys.stdout)
        fmt = (
            '{"time":"%(asctime)s","level":"%(levelname)s",'
            '"module":"%(name)s","message":"%(message)s"}'
        )
        handler.setFormatter(logging.Formatter(fmt, datefmt="%Y-%m-%dT%H:%M:%S"))
        logger.addHandler(handler)

    return logger


logger = setup_logger()