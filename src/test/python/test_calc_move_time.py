import os
import sys
import types
import unittest
from pathlib import Path

# Ensure configuration is deterministic for tests before importing the module.
os.environ.setdefault("BOT_MOVE_TIME", "0.5")
os.environ.setdefault("BOT_BULLET_MOVE_TIME", "0.4")
os.environ.setdefault("BOT_ABSOLUTE_MIN_THINK", "0.05")
os.environ.setdefault("BOT_ADAPTIVE_FLOOR_FRACTION", "0.06")
os.environ.setdefault("BOT_ADAPTIVE_FLOOR_INCREMENT_FRACTION", "0.35")
os.environ.setdefault("BOT_ADAPTIVE_FLOOR_FRACTION_BULLET", "0.1")
os.environ.setdefault("BOT_ADAPTIVE_FLOOR_INCREMENT_FRACTION_BULLET", "0.55")


def _install_stub(name: str, attrs: dict | None = None) -> types.ModuleType:
    module = types.ModuleType(name)
    if attrs:
        for key, value in attrs.items():
            setattr(module, key, value)
    sys.modules[name] = module
    return module


# Provide light-weight stubs for heavy optional dependencies used during module import.
requests_module = _install_stub("requests")
adapters_module = _install_stub("requests.adapters")


class _HTTPAdapter:  # pragma: no cover - simple import stub
    pass


adapters_module.HTTPAdapter = _HTTPAdapter
requests_module.adapters = adapters_module

berserk_module = _install_stub("berserk")
berserk_module.exceptions = types.SimpleNamespace(ResponseError=Exception, ApiError=Exception)
berserk_module.TokenSession = object  # pragma: no cover - import stub
berserk_module.Client = object  # pragma: no cover - import stub

chess_module = _install_stub("chess")
engine_module = _install_stub("chess.engine")


class _DummySimpleEngine:  # pragma: no cover - import stub
    pass


engine_module.SimpleEngine = _DummySimpleEngine
engine_module.EngineError = Exception
engine_module.EngineTerminatedError = Exception
engine_module.Limit = lambda **kwargs: kwargs  # pragma: no cover - import stub
chess_module.engine = engine_module
chess_module.WHITE = True
chess_module.Board = object  # pragma: no cover - import stub

urllib3_module = _install_stub("urllib3")
urllib3_util = _install_stub("urllib3.util")
urllib3_retry = _install_stub("urllib3.util.retry")


class _Retry:  # pragma: no cover - import stub
    def __init__(self, *args, **kwargs):
        pass


urllib3_retry.Retry = _Retry

MODULE_DIR = Path(__file__).resolve().parents[2] / "main" / "resources" / "py"
if str(MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(MODULE_DIR))

import importlib

lichess_bot = importlib.import_module("lichess_bot")


class CalcMoveTimeTests(unittest.TestCase):
    def test_floor_remains_at_move_time_with_plenty_of_time(self):
        state = {"wtime": 600000, "winc": 2000, "moves": ""}
        think, floor = lichess_bot.calc_move_time(state, True, 600.0, 2.0)
        self.assertGreaterEqual(think, floor)
        self.assertAlmostEqual(floor, lichess_bot.MOVE_TIME, places=3)

    def test_floor_scales_down_when_low_on_time(self):
        state = {"wtime": 2000, "winc": 0, "moves": ""}
        think, floor = lichess_bot.calc_move_time(state, True, 180.0, 0.0)
        self.assertGreaterEqual(think, floor)
        self.assertLess(floor, lichess_bot.MOVE_TIME)
        self.assertGreaterEqual(floor, lichess_bot.ABSOLUTE_MIN_THINK_TIME)

    def test_bullet_floor_respects_increment(self):
        state = {"wtime": 1500, "winc": 1000, "moves": "e2e4 e7e5"}
        think, floor = lichess_bot.calc_move_time(state, True, 60.0, 1.0)
        self.assertGreaterEqual(think, floor)
        # Increment should help the adaptive floor stay above the absolute minimum.
        self.assertGreater(floor, lichess_bot.ABSOLUTE_MIN_THINK_TIME)


if __name__ == "__main__":
    unittest.main()
