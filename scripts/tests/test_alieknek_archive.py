"""The bot's game archive: recording finished games, the one-time Lichess import, opponent statistics, the
opening experience, and the network file served for the app's network view."""
import importlib
import json
import struct
import sys
import threading
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import pytest

PY_DIR = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "py"
if str(PY_DIR) not in sys.path:
    sys.path.insert(0, str(PY_DIR))

chess = pytest.importorskip("chess")


@pytest.fixture()
def svc(tmp_path, monkeypatch):
    module = importlib.import_module("alieknek_service")
    monkeypatch.setattr(module, "DATA_DIR", tmp_path)
    monkeypatch.setattr(module, "_archive", {"games": {}, "opponents": {}, "experience": {}, "replies": {},
                                             "ours": {}, "version": 0})
    monkeypatch.setattr(module, "_net", {"loaded": False, "bin": None, "meta": None})
    monkeypatch.setattr(module, "_backfill", {"lastAttempt": 0.0})
    monkeypatch.setattr(module, "_status", {"state": "idle", "game": None, "recent": [], "engine": {}})
    monkeypatch.setenv("ALIEKNEK_NAME", "Alieknek")
    return module


def play(svc, game_id, moves, winner, bot_white=True, opponent="somebot"):
    """Drives the live-game hooks the way lichess_bot.py does and finishes the game."""
    me = {"id": "alieknek", "name": "Alieknek", "rating": 2500}
    opp = {"id": opponent, "name": opponent.capitalize(), "rating": 2400, "title": "BOT"}
    event = {"white": me if bot_white else opp, "black": opp if bot_white else me, "rated": True,
             "speed": "blitz", "clock": {"initial": 180000, "increment": 2000}}
    svc.game_started(game_id, event, "alieknek")
    board = chess.Board()
    for i, uci in enumerate(moves):
        if (board.turn == chess.WHITE) == bot_white:
            svc.engine_info({"depth": 12, "score": chess.engine.PovScore(chess.engine.Cp(20 + i), chess.WHITE)})
        board.push_uci(uci)
        svc.game_update(game_id, board, {"status": "started"})
    svc.game_update(game_id, board, {"status": "mate" if winner else "draw", "winner": winner})
    svc.game_finished(game_id)


import chess.engine  # noqa: E402  (after importorskip)

ITALIAN = ["e2e4", "e7e5", "g1f3", "b8c6", "f1c4", "f8c5"]


def test_finished_games_are_archived_with_moves_and_evals(svc, tmp_path):
    play(svc, "game0001", ITALIAN, "white")
    lines = (tmp_path / "games.jsonl").read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["id"] == "game0001" and record["result"] == "win" and record["color"] == "white"
    assert record["moves"] == ITALIAN
    assert record["clock"] == {"initial": 180, "increment": 2} and record["speed"] == "blitz"
    assert record["opponent"]["id"] == "somebot" and record["opponent"]["rating"] == 2400
    # one evaluation per bot move: plies 0, 2, 4 searched
    assert [e[0] for e in record["evals"]] == [0, 2, 4] and record["evals"][0][3] == 12


def test_engine_uncertainty_is_kept_live_and_per_move(svc, tmp_path):
    me = {"id": "alieknek", "name": "Alieknek", "rating": 2500}
    opp = {"id": "somebot", "name": "Somebot", "rating": 2400}
    svc.game_started("game0009", {"white": me, "black": opp, "clock": {}}, "alieknek")
    score = chess.engine.PovScore(chess.engine.Cp(35), chess.WHITE)
    svc.engine_info({"depth": 14, "score": score, "string": "unc 0.0614"})
    assert svc._status["engine"]["unc"] == pytest.approx(0.061)
    assert svc._status["game"]["evals"][-1] == [0, 35, None, 14, 0.061]
    # older engines (no info string) and other info strings: no uncertainty
    svc.engine_info({"depth": 14, "score": score})
    svc.engine_info({"depth": 14, "score": score, "string": "Syzygy tablebases loaded"})
    assert svc._status["game"]["evals"][-2][4] is None and svc._status["engine"]["unc"] is None


def test_opponent_statistics_and_determinism(svc):
    play(svc, "game0001", ITALIAN, "white")
    play(svc, "game0002", ITALIAN, "black")
    play(svc, "game0003", ITALIAN[:4] + ["d2d4", "e5d4"], None)
    [row] = svc.opponents()
    assert (row["games"], row["wins"], row["draws"], row["losses"]) == (3, 1, 1, 1)
    assert row["score"] == pytest.approx(0.5)
    assert row["core"]["games"] == 3  # recorded now, i.e. after the core engine's start
    # games 2 and 3 repeat the opponent's first two replies (e5, Nc6) each time; game 2 also repeats Bc5
    assert row["repeated"] == 5 and row["sameMove"] == 5 and row["determinism"] == 1.0


def test_experience_counts_results_per_move(svc):
    play(svc, "game0001", ITALIAN, "white")
    play(svc, "game0002", ITALIAN, "black")
    board = chess.Board()
    for uci in ITALIAN[:4]:
        board.push_uci(uci)
    exp = svc.experience(board, "SomeBot")
    assert exp["moves"] == {"f1c4": [1, 0, 1]}
    board.push_uci("f1c4")
    assert svc.experience(board, "somebot")["opponentMoves"] == {"f8c5": 2}
    assert svc.experience(board, "stranger")["opponentMoves"] == {}


def test_load_archive_merges_export_and_bot_records(svc, tmp_path):
    base = {"src": "lichess", "speed": "blitz", "rated": True, "clock": {"initial": 180, "increment": 2},
            "color": "white", "rating": 2400, "status": "mate", "moves": ITALIAN}
    exported = [dict(base, id="old00001", endedAt=1_000, result="loss",
                     opponent={"id": "somebot", "name": "SomeBot", "title": "BOT", "rating": 2300}),
                dict(base, id="game0001", endedAt=2_000, result="loss",
                     opponent={"id": "somebot", "name": "SomeBot", "title": "BOT", "rating": 2300})]
    (tmp_path / "games-lichess.jsonl").write_text("".join(json.dumps(g) + "\n" for g in exported), encoding="utf-8")
    own = dict(base, src="bot", id="game0001", endedAt=2_000, result="win",
               opponent={"id": "somebot", "name": "SomeBot", "title": "BOT", "rating": 2310})
    (tmp_path / "games.jsonl").write_text(json.dumps(own) + "\n" + '{"id": "broken', encoding="utf-8")
    assert svc.load_archive() == 2
    [row] = svc.opponents()
    # the bot's own record of game0001 (a win) replaces the exported one; the cut-off line is skipped
    assert (row["games"], row["wins"], row["losses"]) == (2, 1, 1)
    assert row["core"]["games"] == 0  # both before the core engine


def test_backfill_imports_the_history_once(svc, tmp_path, monkeypatch):
    exported = {"id": "abcd1234", "rated": True, "perf": "blitz", "speed": "blitz", "status": "resign",
                "winner": "white", "createdAt": 5, "lastMoveAt": 9,
                "players": {"white": {"user": {"id": "alieknek", "name": "Alieknek"}, "rating": 2450, "ratingDiff": 5},
                            "black": {"user": {"id": "otherbot", "name": "OtherBot", "title": "BOT"}, "rating": 2350}},
                "clock": {"initial": 180, "increment": 2}, "moves": "e4 e5 Nf3 Nc6",
                "opening": {"name": "King's Knight Opening"}}
    calls = []

    def fake_get(url, accept="application/json", timeout=20):
        calls.append(url)
        return (json.dumps(exported) + "\n").encode("utf-8")

    monkeypatch.setattr(svc, "_get", fake_get)
    assert svc.backfill_archive() == 1
    record = json.loads((tmp_path / "games-lichess.jsonl").read_text(encoding="utf-8"))
    assert record["moves"] == ["e2e4", "e7e5", "g1f3", "b8c6"]
    assert record["opponent"] == {"id": "otherbot", "name": "OtherBot", "title": "BOT", "rating": 2350}
    assert record["result"] == "win" and record["clock"] == {"initial": 180, "increment": 2}
    assert svc.opponents()[0]["id"] == "otherbot"
    assert svc.backfill_archive() == 0 and len(calls) == 1  # never again once the file exists


def tiny_net(hidden=32, buckets=2):
    header = b"ALNN" + struct.pack("<6i", 2, hidden, 255, 256, 400, buckets)
    body = struct.pack(f"<{768 * hidden + hidden + 2 * hidden * buckets}h",
                       *([1] * (768 * hidden + hidden + 2 * hidden * buckets)))
    return header + body + struct.pack(f"<{buckets}i", *([0] * buckets))


def test_network_file_and_meta_are_served_with_etag(svc, tmp_path, monkeypatch):
    net = tiny_net()
    (tmp_path / "net.bin").write_bytes(net)
    monkeypatch.setenv("ALIEKNEK_NNUE_FILE", str(tmp_path / "net.bin"))
    server = ThreadingHTTPServer(("127.0.0.1", 0), svc._Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_address[1]}/alieknek"
    try:
        with urllib.request.urlopen(base + "/nnue.json") as r:
            meta = json.loads(r.read())
            etag = r.headers["ETag"]
        assert (meta["hidden"], meta["buckets"], meta["qa"], meta["scale"]) == (32, 2, 255, 400)
        assert meta["params"] == 768 * 32 + 32 + 2 * 32 * 2 + 2 and meta["bytes"] == len(net)
        with urllib.request.urlopen(base + "/nnue.bin") as r:
            assert r.read() == net
        request = urllib.request.Request(base + "/nnue.bin", headers={"If-None-Match": etag})
        with pytest.raises(urllib.error.HTTPError) as not_modified:
            urllib.request.urlopen(request)
        assert not_modified.value.code == 304
    finally:
        server.shutdown()


def test_king_bucket_network_meta(svc):
    hidden, buckets, kb = 16, 8, 8
    bucket_map = bytes([0, 1, 2, 3, 3, 2, 1, 0] + [4] * 8 + [6] * 8 + [7] * 40)
    header = b"ALNN" + struct.pack("<6i", 3, hidden, 255, 256, 400, buckets) + struct.pack("<2i", kb, 1) + bucket_map
    body = bytes(2 * (768 * kb * hidden + hidden + 2 * hidden * buckets)) + bytes(4 * buckets)
    meta = svc._network_meta(header + body)
    assert (meta["version"], meta["kingBuckets"], meta["mirror"], meta["inputs"]) == (3, 8, True, 768 * 8)
    assert meta["kingBucketMap"][:8] == [0, 1, 2, 3, 3, 2, 1, 0]
    assert meta["params"] == 768 * 8 * hidden + hidden + 2 * hidden * buckets + buckets
    # a plain net in the v3 format (king buckets 1) is the old 768-input layout
    plain = b"ALNN" + struct.pack("<6i", 3, hidden, 255, 256, 400, buckets) + struct.pack("<2i", 1, 0) + bytes(64)
    assert svc._network_meta(plain + bytes(10))["inputs"] == 768


def test_no_network_means_404(svc, tmp_path, monkeypatch):
    monkeypatch.delenv("ALIEKNEK_NNUE_FILE", raising=False)
    monkeypatch.setenv("BOT_JAR_DIR", str(tmp_path))
    assert svc.network() == (None, None)


def test_root_penalties_leave_lost_lines(svc):
    # lost twice against somebot with 3.Bc4 in the Italian move order, drew once with 1...e5 as Black
    play(svc, "game0001", ITALIAN, "black")
    play(svc, "game0002", ITALIAN, "black")
    board = chess.Board()
    for uci in ITALIAN[:4]:
        board.push_uci(uci)
    assert svc.root_penalties(board, "somebot") == {"f1c4": 80}
    assert svc.root_penalties(board, "stranger") == {}  # only two games overall: no global penalty yet
    play(svc, "game0003", ITALIAN, "black", opponent="thirdbot")
    assert svc.root_penalties(board, "stranger") == {"f1c4": svc.PENALTY_GLOBAL}  # 0/3 against everyone
    deep = chess.Board()
    for _ in range(svc.EXPERIENCE_PLIES // 4):
        for uci in ("g1f3", "g8f6", "f3g1", "f6g8"):
            deep.push_uci(uci)
    assert svc.root_penalties(deep, "somebot") == {}  # opening only


def test_drawn_lines_are_left_only_against_weaker_opponents(svc):
    play(svc, "game0001", ITALIAN, None)
    board = chess.Board()
    assert svc.root_penalties(board, "somebot", 2500, 2350) == {"e2e4": svc.PENALTY_DRAW_WEAKER}
    assert svc.root_penalties(board, "somebot", 2500, 2450) == {}


def test_expected_gain_prefers_opponents_we_beat_more_than_expected(svc):
    for i in range(4):
        play(svc, f"game000{i}", ITALIAN, "white")  # 4/4 against somebot
    play(svc, "game0009", ITALIAN, "black", opponent="nemesis")
    assert svc.expected_gain("somebot", 2500, 2500) == pytest.approx((4 * 1.0 + 4 * 0.5) / 8 - 0.5)
    assert svc.expected_gain("nemesis", 2500, 2500) < 0
    assert svc.expected_gain("unknown", 2500, 2500) == 0.0
    assert svc.expected_gain("somebot", None, 2500) == 0.0


START_960 = "5rkr/pppppppp/8/8/8/8/PPPPPPPP/5RKR w HFhf - 0 1"


def test_chess960_games_are_archived_counted_but_teach_no_openings(svc, tmp_path):
    me = {"id": "alieknek", "name": "Alieknek", "rating": 2500}
    opp = {"id": "somebot", "name": "Somebot", "rating": 2400, "title": "BOT"}
    svc.game_started("game0960", {"white": me, "black": opp, "rated": True, "speed": "blitz",
                                  "variant": {"key": "chess960"}, "initialFen": START_960,
                                  "clock": {"initial": 180000, "increment": 2000}}, "alieknek")
    board = chess.Board(START_960, chess960=True)
    for uci in ["g1f1", "g8f8", "e2e4"]:  # both castle queen side (king takes own rook)
        board.push_uci(uci)
        svc.game_update("game0960", board, {"status": "started"})
    assert svc._status["game"]["fen"].startswith("2kr3r/") and svc._status["game"]["variant"] == "chess960"
    svc.game_update("game0960", board, {"status": "resign", "winner": "white"})
    svc.game_finished("game0960")
    record = json.loads((tmp_path / "games.jsonl").read_text(encoding="utf-8").splitlines()[0])
    assert record["variant"] == "chess960" and record["perf"] == "chess960"
    assert record["initialFen"] == START_960 and record["moves"] == ["g1f1", "g8f8", "e2e4"]
    [row] = svc.opponents()
    assert row["games"] == 1 and row["wins"] == 1
    assert svc._archive["experience"] == {}  # Chess960 positions are no opening knowledge for standard chess
    assert svc.recent_games_by_speed(1) == {"chess960": 1}


def test_chess960_replay_starts_from_its_position(svc, monkeypatch):
    monkeypatch.setattr(svc, "_moves", {"abcd1234": "O-O-O O-O-O e4"})
    monkeypatch.setattr(svc, "_starts", {"abcd1234": START_960})
    monkeypatch.setattr(svc, "_lichess", {"ratings": {}, "games": [], "updated": None})
    replay = svc.replay("abcd1234")
    assert replay["ucis"] == ["g1f1", "g8f8", "e2e4"]
    assert replay["fens"][0].startswith("5rkr/") and replay["fens"][2].startswith("2kr3r/")
