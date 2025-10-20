#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Automated tuning loop for Chess-Engine BestMoveSearchTest (advanced, JVM-adaptive).

Highlights
----------
- Project-root autodetect (or --project-root override).
- Atomic writes, timestamped backups, and "best" checkpoint file.
- Temperature-controlled spectral search + simulated annealing to escape plateaus.
- Mutate *subset* of parameters per iteration (less thrashing), soft clamping.
- Reheating after repeated non-improvements.
- Robust Maven execution:
    * Pass-through extra args (no PowerShell quoting pain).
    * Explicit surefire:test goal.
    * Aggregates Surefire/Failsafe XML across *all* modules recursively.
    * Dumps stdout/stderr to logs/ on failure.
- Clear acceptance priority (successes ↑, failures ↓, errors ↓, duration ↓).
- JSONL logging per iteration; optional Git commit on improvement.

JVM & Engine parity with lichess_bot
------------------------------------
- Auto CPU plan -> searchThreads, lazySmpThreads, rootParallelLimit.
- ZGC by default (override via --jvm-gc).
- -XX:ActiveProcessorCount (planned CPUs), -XX:ConcGCThreads, -XX:CICompilerCount.
- -Xms/-Xmx auto (based on RAM & TT size) or explicit via flags.
- Pass engine system props into the test JVM:
    -Dchessengine.searchThreads, -Dchessengine.lazySmpThreads,
    -Dchessengine.rootParallelLimit, -Dchessengine.tt.mb, -Dchessengine.tuning.file, etc.
- All of the above are placed into Surefire's forked JVM via -DargLine="...".

Tablebase awareness
--------------------
- The loop inherits tablebase settings from `chessengine.syzygy.path`/`paths` and forwards them to the Surefire JVM.
- When tuning evaluation weights, keep the setting constant (or clear it) so Syzygy probes do not hide score deltas between candidates.

Parameter filtering & auto-freeze
---------------------------------
- New flags:
    --allow-pattern REGEX  (only mutate params whose normalized name matches)
    --deny-pattern  REGEX  (never mutate params matching this)
    --freeze-after  N      (freeze params after N non-improving mutations)
- Frozen parameters are skipped automatically; this shrinks the search space over time.

Typical usage (PowerShell)
--------------------------
python .\scripts\auto_tuning_loop.py `
  --project-root C:\Development\Chess-Engine `
  --mvn .\mvnw.cmd `
  --test julius.game.chessengine.ai.BestMoveSearchTest `
  --java-release 25 `
  --preview `
  --accept-worse `
  --accept-temp 0.08 `
  --mut-frac 0.30 `
  --noimp-reheat 12 `
  --reheat-factor 1.7 `
  --jvm-gc zgc `
  --tt-mb 1024 `
  --engine-threads auto `
  --lazy-threads auto `
  --root-par-limit auto `
  --xms auto `
  --xmx auto `
  --plan-concurrent 1 `
  --extra-maven-args -q

If the test lives in a submodule:
  --extra-maven-args -pl :engine -am

python .\scripts\auto_tuning_loop.py --project-root C:\Development\Chess-Engine --mvn .\mvnw.cmd --test julius.game.chessengine.ai.BestMoveSearchTest --java-release 25 --preview --accept-worse --accept-temp 0.08 --mut-frac 0.30 --noimp-reheat 12 --reheat-factor 1.7 --jvm-gc zgc --tt-mb 1024 --engine-threads auto --lazy-threads auto --root-par-limit auto --xms auto --xmx auto --plan-concurrent 1 --extra-maven-args -q
"""

from __future__ import annotations

import argparse
import dataclasses
import glob
import hashlib
import json
import math
import os
import random
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import statistics
import xml.etree.ElementTree as ET
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import DefaultDict, Dict, List, Optional, Set, Tuple


DEFAULT_SYZYGY_NATIVE = r"C:\\Development\\Chess-Engine\\target\\classes\\natives\\win-x86_64\\Release\\JSyzygy.dll"
DEFAULT_SYZYGY_PATHS = r"C:\\Syzygy"


def resolve_syzygy_from_env(env: Optional[Dict[str, str]] = None) -> Tuple[str, str]:
    source = env if env is not None else os.environ
    native = source.get("CHESSENGINE_SYZYGY_NATIVE") or DEFAULT_SYZYGY_NATIVE
    paths = (
            source.get("CHESSENGINE_SYZYGY_PATHS")
            or source.get("CHESSENGINE_SYZYGY_PATH")
            or DEFAULT_SYZYGY_PATHS
    )
    return native, paths

# ----------------------------
# Patterns
# ----------------------------

TEST_SUMMARY_PATTERN = re.compile(
    r"Tests run: (?P<total>\d+), Failures: (?P<failures>\d+), Errors: (?P<errors>\d+), Skipped: (?P<skipped>\d+)"
)

NUMERIC_VALUE_PATTERN = re.compile(
    r"(?P<key>[A-Za-z0-9_.]+):\s*(?P<value>-?\d+(?:\.\d+)?)\s*$"
)

BMSTAT_PATTERN = re.compile(r"\[BMSTAT\]\s*(\{.*\})")
BMSUM_PATTERN  = re.compile(r"\[BMSUM\]\s*(\{.*\})")


def normalize_key(key: str) -> str:
    trimmed = key.strip()
    lowered = trimmed.lower()
    return trimmed if trimmed == lowered else lowered


PARAM_MUTATION_HINTS: Dict[str, Dict[str, object]] = {
    "evaluation.blendscale": {
        "step": 16.0,
        "soft_min": 128.0,
        "soft_max": 768.0,
    },
    "material.pawnvalue": {
        "step": 1.0,
        "soft_min": 60.0,
        "soft_max": 160.0,
    },
    "material.knightvalue": {
        "step": 4.0,
        "soft_min": 240.0,
        "soft_max": 400.0,
    },
    "material.bishopvalue": {
        "step": 4.0,
        "soft_min": 260.0,
        "soft_max": 420.0,
    },
    "material.rookvalue": {
        "step": 8.0,
        "soft_min": 400.0,
        "soft_max": 640.0,
    },
    "material.queenvalue": {
        "step": 12.0,
        "soft_min": 720.0,
        "soft_max": 1120.0,
    },
    "material.bishoppairbonus": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 120.0,
    },
    "search.fpmargindepth1": {
        "step": 120.0,
        "soft_min": 0.0,
        "soft_max": 3200.0,
        "sentinel_probe": 0.08,
        "sentinels": [0.0],
    },
    "search.fpmargindepth2": {
        "step": 200.0,
        "soft_min": 0.0,
        "soft_max": 6400.0,
        "sentinel_probe": 0.08,
        "sentinels": [0.0],
    },
    "search.lmpbase": {
        "step": 2.5,
        "max_step": 6.0,
        "soft_min": 0.0,
        "soft_max": 40.0,
        "sentinel_probe": 0.06,
        "sentinels": [0.0],
    },
    "search.lmpperdepth": {
        "step": 1.0,
        "max_step": 3.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
        "sentinel_probe": 0.06,
        "sentinels": [0.0],
    },
    "search.fpmaxdepth": {
        "step": 1.0,
        "max_step": 3.0,
        "soft_min": 0.0,
        "soft_max": 16.0,
    },
    "search.lmpmaxdepth": {
        "step": 1.0,
        "max_step": 3.0,
        "soft_min": 0.0,
        "soft_max": 16.0,
    },
    "search.hmpminindex": {
        "step": 4.0,
        "max_step": 10.0,
        "soft_min": -1.0,
        "soft_max": 64.0,
        "sentinel_probe": 0.1,
        "sentinels": [-1.0],
    },
    "search.hmphistorymax": {
        "step": 240.0,
        "max_step": 640.0,
        "soft_min": -1.0,
        "soft_max": 4000.0,
        "sentinel_probe": 0.1,
        "sentinels": [-1.0],
    },
    "search.iidreducedepth": {
        "step": 1.0,
        "max_step": 2.0,
        "soft_min": 0.0,
        "soft_max": 6.0,
        "sentinel_probe": 0.05,
        "sentinels": [0.0],
    },
    "search.lmrprotectplymax": {
        "step": 1.0,
        "max_step": 2.0,
        "soft_min": 0.0,
        "soft_max": 6.0,
        "sentinel_probe": 0.05,
        "sentinels": [0.0],
    },
    "search.lmrprotectindexmax": {
        "step": 2.0,
        "max_step": 6.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
        "sentinel_probe": 0.05,
        "sentinels": [0.0],
    },
    "search.lmrcapgoodquiet": {
        "step": 2.0,
        "max_step": 6.0,
        "soft_min": 0.0,
        "soft_max": 64.0,
    },
    "search.lmrhistorybuckets": {
        "step": 1.0,
        "soft_min": 1.0,
        "soft_max": 12.0,
    },
    "search.lmrhistoryweightslope": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.5,
    },
    "search.lmrscaledivisor": {
        "step": 0.1,
        "soft_min": 0.5,
        "soft_max": 4.0,
    },
    "search.lmrdepthlogoffset": {
        "step": 0.1,
        "soft_min": 0.0,
        "soft_max": 10.0,
    },
    "search.lmrmovelogoffset": {
        "step": 0.1,
        "soft_min": 0.0,
        "soft_max": 10.0,
    },
    "search.maxcheckextensionstreak": {
        "step": 1.0,
        "max_step": 3.0,
        "soft_min": 0.0,
        "soft_max": 6.0,
    },
    "search.seeprunenearrootply": {
        "step": 1.0,
        "max_step": 2.0,
        "soft_min": 0.0,
        "soft_max": 16.0,
    },
    "search.historyreductionmax": {
        "step": 200.0,
        "max_step": 600.0,
        "soft_min": 0.0,
        "soft_max": 8000.0,
    },
    "search.rootstaticblend": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.rootstaticoverridecp": {
        "step": 10.0,
        "soft_min": 0.0,
        "soft_max": 800.0,
        "sentinels": [0.0],
    },
    "search.rootqueenattackbonuscp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 800.0,
    },
    "search.rootearlystopmargincp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 640.0,
    },
    "search.rootfutilitymargincp": {
        "step": 10.0,
        "soft_min": 0.0,
        "soft_max": 320.0,
    },
    "search.rootfutilityleadcp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 800.0,
    },
    "search.rootcapturevaluethresholdcp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 1024.0,
    },
    "search.rootcapturegainthresholdcp": {
        "step": 10.0,
        "soft_min": 0.0,
        "soft_max": 512.0,
    },
    "search.rootrunnerupmargincp": {
        "step": 5.0,
        "soft_min": 0.0,
        "soft_max": 200.0,
    },
    "search.rootdespairmargincp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 800.0,
    },
    "search.rootdespairminevals": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "search.rootdespairrunnerratio": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.rootdespairabsthresholdcp": {
        "step": 20.0,
        "soft_min": 0.0,
        "soft_max": 1200.0,
    },
    "search.rootdespairminevalsabs": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "search.roothopelessmargincp": {
        "step": 10.0,
        "soft_min": 0.0,
        "soft_max": 240.0,
    },
    "search.absplylimitmargin": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
    },
    "search.aspminspancp": {
        "step": 2.0,
        "soft_min": 4.0,
        "soft_max": 48.0,
    },
    "search.aspmaxspancp": {
        "step": 16.0,
        "soft_min": 64.0,
        "soft_max": 640.0,
    },
    "search.aspdefaultspancp": {
        "step": 4.0,
        "soft_min": 12.0,
        "soft_max": 256.0,
    },
    "search.asphistoryblend": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.aspmomentumstepcp": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 64.0,
    },
    "search.aspmomentumcap": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 32.0,
    },
    "search.aspfailureratio": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.aspbaseoffsetcp": {
        "step": 4.0,
        "soft_min": 0.0,
        "soft_max": 96.0,
    },
    "search.aspswingweight": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.aspvolatilityweight": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 2.0,
    },
    "search.aspdepthscale": {
        "step": 0.01,
        "soft_min": 0.0,
        "soft_max": 0.2,
    },
    "search.aspdepthpivot": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "search.aspfloorbasecp": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
    },
    "search.aspfloorvolweight": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.5,
    },
    "search.aspfloorstreakstepcp": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 32.0,
    },
    "search.aspbumpbasecp": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 64.0,
    },
    "search.aspbumpstreakcp": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 32.0,
    },
    "search.aspbumpdepthcp": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 16.0,
    },
    "search.aspfullwindowscale": {
        "step": 0.05,
        "soft_min": 1.0,
        "soft_max": 2.0,
    },
    "search.asplastspanscale": {
        "step": 0.05,
        "soft_min": 1.0,
        "soft_max": 2.0,
    },
    "search.aspfullwindowminmultiplier": {
        "step": 0.2,
        "soft_min": 1.0,
        "soft_max": 4.0,
    },
    "search.aspblendbaselineweight": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.aspblendcandidateweight": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.aspmaxretriesbase": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 10.0,
    },
    "search.aspmaxretriesvolthresholdhigh": {
        "step": 16.0,
        "soft_min": 0.0,
        "soft_max": 512.0,
    },
    "search.aspmaxretriesvolbonushigh": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "search.aspmaxretriesvolthresholdmed": {
        "step": 8.0,
        "soft_min": 0.0,
        "soft_max": 256.0,
    },
    "search.aspmaxretriesvolbonusmed": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 6.0,
    },
    "search.aspmaxretriesdepthoffset": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 16.0,
    },
    "search.aspmaxretriesdepthdivisor": {
        "step": 0.5,
        "soft_min": 1.0,
        "soft_max": 8.0,
    },
    "search.aspmaxretriesmomentumdivisor": {
        "step": 0.5,
        "soft_min": 1.0,
        "soft_max": 8.0,
    },
    "search.aspmaxretriesmin": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "search.aspmaxretriesmax": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "search.nullbasereduction": {
        "step": 0.1,
        "soft_min": 0.5,
        "soft_max": 8.0,
    },
    "search.nulldepthweight": {
        "step": 0.1,
        "soft_min": 0.5,
        "soft_max": 3.0,
    },
    "search.nullmaterialweight": {
        "step": 0.05,
        "soft_min": 0.2,
        "soft_max": 2.0,
    },
    "search.nullmobilityweight": {
        "step": 0.05,
        "soft_min": 0.1,
        "soft_max": 2.0,
    },
    "search.nulldepthcap": {
        "step": 1.0,
        "soft_min": 4.0,
        "soft_max": 24.0,
    },
    "search.nullmaterialcap": {
        "step": 1.0,
        "soft_min": 4.0,
        "soft_max": 24.0,
    },
    "search.nullmobilitycap": {
        "step": 2.0,
        "soft_min": 10.0,
        "soft_max": 64.0,
    },
    "search.nulllowmaterialthreshold": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "search.nulllowmobilitythreshold": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "search.nullverylowmobilitythreshold": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "search.nulllowmaterialpenalty": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.5,
    },
    "search.nullverylowmobilitypenalty": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.5,
    },
    "search.nullswingguardmincp": {
        "step": 64.0,
        "soft_min": 0.0,
        "soft_max": 2000.0,
    },
    "search.nullswingguarddivisor": {
        "step": 4.0,
        "soft_min": 8.0,
        "soft_max": 128.0,
    },
    "search.qsmaxdeltapawn": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 20.0,
    },
    "search.ttmainweight": {
        "step": 0.1,
        "soft_min": 0.5,
        "soft_max": 6.0,
    },
    "search.ttcaptureweight": {
        "step": 0.1,
        "soft_min": 0.25,
        "soft_max": 6.0,
    },
    "search.drawbias": {
        "step": 0.05,
        "soft_min": 0.0,
        "soft_max": 1.0,
    },
    "search.preferfastmate": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 1.0,
        "sentinels": [0.0, 1.0],
    },
    "search.tbtiebreak": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 1.0,
        "sentinels": [0.0, 1.0],
    },
    "pawnstructure.centerpawnbonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 40.0,
    },
    "pawnstructure.passedpawnbonus": {
        "step": 4.0,
        "soft_min": 20.0,
        "soft_max": 120.0,
    },
    "pawnstructure.connectedpawnbonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "pawnstructure.advancedpawnbonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "pawnstructure.passedpawnfreepathbonusperrank": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 32.0,
    },
    "pawnstructure.rookhalfopenfilebonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 40.0,
    },
    "pawnstructure.rookopenfilebonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 60.0,
    },
    "pawnstructure.islandpenalty": {
        "step": 1.0,
        "soft_min": -40.0,
        "soft_max": 0.0,
    },
    "pawnstructure.doubledpawnpenalty": {
        "step": 2.0,
        "soft_min": -80.0,
        "soft_max": 0.0,
    },
    "pawnstructure.isolatedpawnpenalty": {
        "step": 2.0,
        "soft_min": -60.0,
        "soft_max": 0.0,
    },
    "pawnstructure.blockedpawnpenalty": {
        "step": 2.0,
        "soft_min": -50.0,
        "soft_max": 0.0,
    },
    "pawnstructure.backwardpawnpenalty": {
        "step": 2.0,
        "soft_min": -60.0,
        "soft_max": 0.0,
    },
    "pawnstructure.ownkingblockspassedpawnpenalty": {
        "step": 12.0,
        "soft_min": -400.0,
        "soft_max": 0.0,
    },
    "activity.midgamemobilityknight": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.midgamemobilitybishop": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.midgamemobilityrook": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.midgamemobilityqueen": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "activity.midgamemobilityking": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamemobilityknight": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.endgamemobilitybishop": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.endgamemobilityrook": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "activity.endgamemobilityqueen": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 8.0,
    },
    "activity.endgamemobilityking": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.midgamecenterknight": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.midgamecenterbishop": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.midgamecenterrook": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.midgamecenterqueen": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.midgamecenterking": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamecenterknight": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamecenterbishop": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamecenterrook": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamecenterqueen": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "activity.endgamecenterking": {
        "step": 1.0,
        "soft_min": -4.0,
        "soft_max": 8.0,
    },
    "kingsafety.missingpawnshieldpenalty": {
        "step": 2.0,
        "soft_min": -80.0,
        "soft_max": 0.0,
    },
    "kingsafety.halfopenfilepenalty": {
        "step": 2.0,
        "soft_min": -80.0,
        "soft_max": 0.0,
    },
    "kingsafety.openfilepenalty": {
        "step": 3.0,
        "soft_min": -120.0,
        "soft_max": 0.0,
    },
    "kingsafety.queenattackedpenalty": {
        "step": 8.0,
        "soft_min": -240.0,
        "soft_max": 0.0,
    },
    "kingsafety.backrankweaknessmidgamepenalty": {
        "step": 10.0,
        "soft_min": -240.0,
        "soft_max": 0.0,
    },
    "kingsafety.backrankweaknessendgamepenalty": {
        "step": 8.0,
        "soft_min": -120.0,
        "soft_max": 0.0,
    },
    "kingsafety.defenderbonus": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 20.0,
    },
    "kingsafety.attackweightpawn": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 12.0,
    },
    "kingsafety.attackweightknight": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "kingsafety.attackweightbishop": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 24.0,
    },
    "kingsafety.attackweightrook": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 36.0,
    },
    "kingsafety.attackweightqueen": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
    },
    "threat.hangingpawnpenalty": {
        "step": 2.0,
        "soft_min": -50.0,
        "soft_max": 0.0,
    },
    "threat.hangingknightpenalty": {
        "step": 3.0,
        "soft_min": -90.0,
        "soft_max": 0.0,
    },
    "threat.hangingbishoppenalty": {
        "step": 3.0,
        "soft_min": -90.0,
        "soft_max": 0.0,
    },
    "threat.hangingrookpenalty": {
        "step": 4.0,
        "soft_min": -140.0,
        "soft_max": 0.0,
    },
    "threat.hangingqueenpenalty": {
        "step": 5.0,
        "soft_min": -180.0,
        "soft_max": 0.0,
    },
    "threat.pawnthreatknightpenalty": {
        "step": 2.0,
        "soft_min": -50.0,
        "soft_max": 0.0,
    },
    "threat.pawnthreatbishoppenalty": {
        "step": 2.0,
        "soft_min": -50.0,
        "soft_max": 0.0,
    },
    "threat.pawnthreatrookpenalty": {
        "step": 3.0,
        "soft_min": -80.0,
        "soft_max": 0.0,
    },
    "threat.pawnthreatqueenpenalty": {
        "step": 3.0,
        "soft_min": -100.0,
        "soft_max": 0.0,
    },
    "moveordering.category.tt": {
        "step": 1.0,
        "soft_min": 6.0,
        "soft_max": 8.0,
    },
    "moveordering.category.promotion": {
        "step": 1.0,
        "soft_min": 5.0,
        "soft_max": 7.0,
    },
    "moveordering.category.capturegood": {
        "step": 1.0,
        "soft_min": 4.0,
        "soft_max": 6.0,
    },
    "moveordering.category.captureequal": {
        "step": 1.0,
        "soft_min": 3.0,
        "soft_max": 5.0,
    },
    "moveordering.category.killer0": {
        "step": 1.0,
        "soft_min": 2.0,
        "soft_max": 4.0,
    },
    "moveordering.category.killer1": {
        "step": 1.0,
        "soft_min": 1.0,
        "soft_max": 3.0,
    },
    "moveordering.category.quiet": {
        "step": 1.0,
        "soft_min": 0.0,
        "soft_max": 2.0,
    },
    "moveordering.category.capturebad": {
        "step": 1.0,
        "soft_min": -1.0,
        "soft_max": 1.0,
    },
    "moveordering.killermovescore": {
        "step": 400.0,
        "soft_min": 2000.0,
        "soft_max": 20000.0,
    },
    "moveordering.promotionbonus": {
        "step": 80.0,
        "soft_min": 400.0,
        "soft_max": 1600.0,
    },
    "moveordering.killer0bonus": {
        "step": 5.0,
        "soft_min": 0.0,
        "soft_max": 640.0,
    },
    "moveordering.killer1bonus": {
        "step": 5.0,
        "soft_min": 0.0,
        "soft_max": 400.0,
    },
    "moveordering.countermovebonus": {
        "step": 40.0,
        "soft_min": 0.0,
        "soft_max": 1200.0,
    },
    "moveordering.capturemvvmultiplier": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
    },
    "moveordering.captureseemultiplier": {
        "step": 4.0,
        "soft_min": 0.0,
        "soft_max": 192.0,
    },
    "moveordering.captureseeclamp": {
        "step": 128.0,
        "soft_min": 512.0,
        "soft_max": 4096.0,
    },
    "moveordering.capturegoodbonus": {
        "step": 32.0,
        "soft_min": -2048.0,
        "soft_max": 2048.0,
    },
    "moveordering.captureequalbonus": {
        "step": 32.0,
        "soft_min": -2048.0,
        "soft_max": 2048.0,
    },
    "moveordering.capturebadbonus": {
        "step": 32.0,
        "soft_min": -4096.0,
        "soft_max": 1024.0,
    },
    "moveordering.capturelosingseepenalty": {
        "step": 8.0,
        "soft_min": -2048.0,
        "soft_max": 256.0,
        "sentinel_probe": 0.05,
        "sentinels": [0.0],
    },
    "moveordering.promotionseemultiplier": {
        "step": 2.0,
        "soft_min": 0.0,
        "soft_max": 48.0,
    },
    "moveordering.promotionseeclamp": {
        "step": 64.0,
        "soft_min": 128.0,
        "soft_max": 2048.0,
    },
    "moveordering.castlingbonus": {
        "step": 200.0,
        "soft_min": 0.0,
        "soft_max": 5000.0,
    },
    "moveordering.quiethistorymultiplier": {
        "step": 0.1,
        "soft_min": 0.0,
        "soft_max": 4.0,
    },
    "moveordering.quiethistorybonus": {
        "step": 32.0,
        "soft_min": -4096.0,
        "soft_max": 4096.0,
    },
    "moveordering.maxscore": {
        "step": 1048576.0,
        "soft_min": 1048576.0,
        "soft_max": 16777215.0,
    },
    "moveordering.historyscale": {
        "step": 0.1,
        "soft_min": 0.0,
        "soft_max": 4.0,
    },
    "moveordering.historydecaydivisor": {
        "step": 1.0,
        "soft_min": 1.0,
        "soft_max": 16.0,
    },
}

# ----------------------------
# Data classes
# ----------------------------

@dataclass
class ParamSpec:
    key: str
    default_value: Optional[float]
    min_value: Optional[float]
    max_value: Optional[float]


@dataclass
class NumericParameter:
    name: str
    line_index: int
    indent: str
    yaml_key: str
    raw_value: str
    normalized_name: str
    default_value: Optional[float] = None
    min_value: Optional[float] = None
    max_value: Optional[float] = None

    @property
    def is_float(self) -> bool:
        return "." in self.raw_value

    @property
    def decimals(self) -> int:
        if "." not in self.raw_value:
            return 0
        return len(self.raw_value.split(".")[1])

    @property
    def numeric_value(self) -> float:
        return float(self.raw_value)


@dataclass
class TestResult:
    total: int
    failures: int
    errors: int
    skipped: int
    stdout: str
    stderr: str
    duration_s: float
    metrics: Dict[str, float] = dataclasses.field(default_factory=dict)
    decision_stats: List[Dict] = dataclasses.field(default_factory=list)

    @property
    def successes(self) -> int:
        return self.total - self.failures - self.errors

    def summary(self) -> str:
        base = (
            f"Tests run: {self.total}, successes: {self.successes}, "
            f"failures: {self.failures}, errors: {self.errors}, skipped: {self.skipped}, "
            f"duration: {self.duration_s:.2f}s"
        )
        extras: List[str] = []
        avg_cp_loss = self.metrics.get("avgCpLoss") if self.metrics else None
        if avg_cp_loss is not None:
            extras.append(f"avgCpLoss: {avg_cp_loss:.2f} cp")
        top1_rate = self.metrics.get("top1Rate") if self.metrics else None
        if top1_rate is not None:
            extras.append(f"top1Rate: {top1_rate * 100:.1f}%")
        avg_nodes = self.metrics.get("avgNodes") if self.metrics else None
        if avg_nodes is not None:
            extras.append(f"avgNodes: {avg_nodes:.0f}")
        avg_duration = self.metrics.get("avgDurationMs") if self.metrics else None
        if avg_duration is not None:
            extras.append(f"avgDuration: {avg_duration:.1f} ms")
        p95_duration = self.metrics.get("durationMsP95") if self.metrics else None
        if p95_duration is not None:
            extras.append(f"p95Duration: {p95_duration:.1f} ms")
        max_duration = self.metrics.get("durationMsMax") if self.metrics else None
        if max_duration is not None:
            extras.append(f"maxDuration: {max_duration:.1f} ms")
        miss_rate = self.metrics.get("missRate") if self.metrics else None
        if miss_rate is not None:
            extras.append(f"missRate: {miss_rate * 100:.1f}%")
        if extras:
            base += " | " + ", ".join(extras)
        return base

    def metric_value(self, key: str) -> Optional[float]:
        if not self.metrics:
            return None
        value = self.metrics.get(key)
        if value is None:
            return None
        try:
            return float(value)
        except (TypeError, ValueError):
            return None

    def score_tuple(self, duration_metric: str = "durationMsP95") -> Tuple[float, float, float, float, float, float, float]:
        # higher is better for successes/top1Rate; lower better for failures/errors/cpLoss/duration metrics
        top1_rate = float(self.metrics.get("top1Rate", 0.0) or 0.0)
        avg_cp_loss = float(self.metrics.get("avgCpLoss", 0.0) or 0.0)
        duration_priority = self.metric_value(duration_metric)
        if duration_priority is None or math.isnan(duration_priority):
            duration_priority = self.metric_value("avgDurationMs")
        if duration_priority is None or math.isnan(duration_priority):
            duration_priority = self.duration_s * 1000.0
        return (
            float(self.successes),
            float(-self.failures),
            float(-self.errors),
            top1_rate,
            -avg_cp_loss,
            -float(duration_priority),
            -self.duration_s,
        )

# ----------------------------
# Utilities
# ----------------------------

def find_project_root(start: Path) -> Path:
    """Find the nearest ancestor directory containing a pom.xml."""
    cur = start.resolve()
    if cur.is_file():
        cur = cur.parent
    for path in [cur, *cur.parents]:
        if (path / "pom.xml").exists():
            return path
    return start

def atomic_write(path: Path, content: str) -> None:
    tmp_fd, tmp_path = tempfile.mkstemp(prefix=path.name + ".", dir=str(path.parent))
    try:
        with os.fdopen(tmp_fd, "w", encoding="utf-8", newline="\n") as f:
            f.write(content if content.endswith("\n") else content + "\n")
        os.replace(tmp_path, path)
    finally:
        try:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        except Exception:
            pass

def timestamp() -> str:
    return time.strftime("%Y%m%d-%H%M%S", time.localtime())

# ----------------------------
# CPU/RAM planning (parity with lichess_bot philosophy)
# ----------------------------

def _detect_cpus() -> Tuple[int, int]:
    logical = os.cpu_count() or 2
    physical = None
    try:
        import psutil  # optional
        physical = psutil.cpu_count(logical=False) or None
    except Exception:
        pass
    if physical is None:
        physical = max(1, logical // 2)
    return logical, physical

def _total_memory_bytes() -> Optional[int]:
    try:
        import psutil
        return int(psutil.virtual_memory().total)
    except Exception:
        # Windows fallback via environ (not reliable), else None
        return None

def _auto_thread_plan(max_concurrent_games: int = 1) -> Dict[str, int]:
    logical, physical = _detect_cpus()
    reserve = 2 + max(0, max_concurrent_games - 1)
    target_total = max(1, min(physical, (logical - reserve)))
    search_threads = min(3, max(1, target_total // 4))
    lazy_threads = max(1, target_total - search_threads)
    root_par_limit = max(24, min(96, search_threads * 12))
    return {
        "logical": logical,
        "physical": physical,
        "search": search_threads,
        "lazy": lazy_threads,
        "root_limit": root_par_limit,
        "planned_total": max(1, search_threads + lazy_threads),
    }

def _gc_flag(gc: str) -> str:
    g = (gc or "").lower()
    if g == "zgc":
        return "-XX:+UseZGC"
    if g == "shenandoah":
        return "-XX:+UseShenandoahGC"
    if g == "g1":
        return "-XX:+UseG1GC"
    # allow custom flag pass-through
    return gc

def _derive_jvm_flags(
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str] = None,
) -> List[str]:
    flags = [
        _gc_flag(jvm_gc),
        f"-Xms{xms}",
        f"-Xmx{xmx}",
        "-XX:+AlwaysPreTouch",
        "-XX:FlightRecorderOptions=stackdepth=256",
        f"-XX:ActiveProcessorCount={apc}",
    ]
    if conc_gc_threads is not None:
        flags.append(f"-XX:ConcGCThreads={conc_gc_threads}")
    if ci_compiler_count is not None:
        flags.append(f"-XX:CICompilerCount={ci_compiler_count}")
    if soft_max:
        flags.append(f"-XX:SoftMaxHeapSize={soft_max}")
    # pleasant ZGC tuning
    if jvm_gc.lower() == "zgc":
        flags.append("-XX:ZUncommitDelay=60")
    return flags

def _auto_heap(tt_mb: int) -> Tuple[str, str, Optional[str]]:
    """
    Simple heuristic:
      - base = max(2048MB, 3 * TT)
      - cap at 75% of RAM if psutil available
    """
    base_mb = max(2048, 3 * max(1, tt_mb))
    ram = _total_memory_bytes()
    soft = None
    if ram:
        ram_mb = ram // (1024 * 1024)
        hard_cap = max(1024, int(ram_mb * 0.75))
        base_mb = min(base_mb, hard_cap)
        # optional soft cap to let ZGC uncommit under memory pressure
        soft = f"{max(1024, int(ram_mb * 0.5))}m"
    return f"{base_mb}m", f"{base_mb}m", soft

# ----------------------------
# Optimizer
# ----------------------------

class SeedTuningOptimizer:
    """Encapsulates loading, mutating, and writing the tuning parameters."""

    def __init__(self, tuning_path: Path) -> None:
        self.tuning_path = tuning_path
        if not tuning_path.exists():
            raise FileNotFoundError(f"Cannot find tuning file at {tuning_path}")
        self.project_root = find_project_root(tuning_path.parent)
        self._param_specs: Dict[str, ParamSpec] = self._load_param_specs()
        self._initial_magnitudes: Dict[str, float] = {}
        self._missing_param_keys: List[str] = []
        self._step_scale: DefaultDict[str, float] = defaultdict(lambda: 1.0)
        self._last_mutated: Set[str] = set()

        # NEW: mutation outcome tracking + filters + freezing
        self._bad_streak: DefaultDict[str, int] = defaultdict(int)
        self._frozen: Set[str] = set()
        self._allow_re: Optional[re.Pattern] = None
        self._deny_re: Optional[re.Pattern] = None
        self._freeze_after: int = 8  # default; overridden by main()

    def backup(self, suffix: str) -> Path:
        dst = self.tuning_path.with_suffix(self.tuning_path.suffix + f".{suffix}.{timestamp()}.bak")
        shutil.copy2(self.tuning_path, dst)
        return dst

    def load(self) -> Tuple[List[str], Dict[str, NumericParameter]]:
        text = self.tuning_path.read_text(encoding="utf-8")
        lines = text.splitlines()

        numeric_parameters = self._extract_numeric_parameters(lines)
        evaluation_parameters = self._extract_evaluation_parameters(lines)

        combined: Dict[str, NumericParameter] = {}
        combined.update(evaluation_parameters)
        combined.update(numeric_parameters)

        if not combined:
            raise ValueError(
                "No tunable parameters found in seed-tunings.yaml (expected evaluation.modules.* or numericParameters entries)."
            )

        self._report_missing_parameters(combined)

        return lines, combined

    @staticmethod
    def _parse_number(token: str) -> float:
        cleaned = token.replace("_", "")
        lowered = cleaned.lower()
        if lowered.startswith("0x") or lowered.startswith("-0x"):
            try:
                return float(int(cleaned, 16))
            except ValueError:
                return 0.0
        try:
            value = float(cleaned)
        except ValueError:
            return 0.0
        return value

    def _load_param_specs(self) -> Dict[str, ParamSpec]:
        specs: Dict[str, ParamSpec] = {}
        param_file = self.project_root / "src/main/java/julius/game/chessengine/tuning/ParamId.java"
        if not param_file.exists():
            return specs

        pattern = re.compile(
            r"^[\t ]*[A-Z0-9_]+\(\"([^\"]+)\",\s*([-0-9A-Fa-f_xX]+)(?:,\s*([-0-9A-Fa-f_xX]+),\s*([-0-9A-Fa-f_xX]+))?\)",
            re.MULTILINE,
        )

        text = param_file.read_text(encoding="utf-8")
        for match in pattern.finditer(text):
            key = match.group(1)
            default_token = match.group(2)
            min_token = match.group(3)
            max_token = match.group(4)

            default_value = self._parse_number(default_token) if default_token else None
            min_value = self._parse_number(min_token) if min_token else None
            max_value = self._parse_number(max_token) if max_token else None

            normalized = normalize_key(key)
            specs[normalized] = ParamSpec(
                key=key,
                default_value=default_value,
                min_value=min_value,
                max_value=max_value,
            )

        return specs

    def _extract_numeric_parameters(self, lines: List[str]) -> Dict[str, NumericParameter]:
        numeric_parameters: Dict[str, NumericParameter] = {}
        in_numeric_block = False
        base_indent_len: Optional[int] = None

        for idx, line in enumerate(lines):
            stripped = line.strip()

            if not in_numeric_block:
                if stripped.startswith("numericParameters:"):
                    in_numeric_block = True
                    base_indent_len = len(line) - len(line.lstrip())
                continue

            indent_len = len(line) - len(line.lstrip())
            if stripped and base_indent_len is not None and indent_len <= base_indent_len:
                break

            match = NUMERIC_VALUE_PATTERN.match(stripped)
            if match:
                key = match.group("key")
                value = match.group("value")
                normalized = normalize_key(key)
                spec = self._param_specs.get(normalized)
                numeric_parameters[key] = NumericParameter(
                    name=key,
                    line_index=idx,
                    indent=line[: len(line) - len(line.lstrip())],
                    yaml_key=key,
                    raw_value=value,
                    normalized_name=normalized,
                    default_value=spec.default_value if spec else None,
                    min_value=spec.min_value if spec else None,
                    max_value=spec.max_value if spec else None,
                )

        return numeric_parameters

    def _extract_evaluation_parameters(self, lines: List[str]) -> Dict[str, NumericParameter]:
        evaluation_parameters: Dict[str, NumericParameter] = {}
        in_evaluation = False
        evaluation_indent: Optional[int] = None
        in_modules = False
        modules_indent: Optional[int] = None
        current_module: Optional[str] = None
        module_indent: Optional[int] = None

        for idx, line in enumerate(lines):
            stripped = line.strip()
            if not stripped:
                continue

            indent_len = len(line) - len(line.lstrip())

            if not in_evaluation:
                if stripped.startswith("evaluation:"):
                    in_evaluation = True
                    evaluation_indent = indent_len
                continue

            if evaluation_indent is not None and indent_len <= evaluation_indent:
                in_evaluation = False
                in_modules = False
                current_module = None
                continue

            if not in_modules:
                if stripped.startswith("modules:"):
                    in_modules = True
                    modules_indent = indent_len
                continue

            if modules_indent is not None and indent_len <= modules_indent:
                in_modules = False
                current_module = None
                continue

            if stripped.endswith(":") and ":" not in stripped[:-1]:
                current_module = stripped[:-1].strip()
                module_indent = indent_len
                continue

            if current_module and module_indent is not None and indent_len > module_indent:
                match = NUMERIC_VALUE_PATTERN.match(stripped)
                if match:
                    key = match.group("key")
                    value = match.group("value")
                    full_key = f"evaluation.modules.{current_module}.{key}"
                    normalized = normalize_key(full_key)
                    spec = self._param_specs.get(normalized)
                    evaluation_parameters[full_key] = NumericParameter(
                        name=full_key,
                        line_index=idx,
                        indent=line[: len(line) - len(line.lstrip())],
                        yaml_key=key,
                        raw_value=value,
                        normalized_name=normalized,
                        default_value=spec.default_value if spec else None,
                        min_value=spec.min_value if spec else None,
                        max_value=spec.max_value if spec else None,
                    )

        return evaluation_parameters

    def _report_missing_parameters(self, parameters: Dict[str, NumericParameter]) -> None:
        normalized_present = {normalize_key(name) for name in parameters.keys()}
        missing = sorted(key for key in self._param_specs.keys() if key not in normalized_present)
        self._missing_param_keys = missing
        if missing:
            print(
                "[warn] Missing tuning parameters in seed file:",
                ", ".join(missing),
            )

    def _derive_initial_scale(self, param: NumericParameter) -> float:
        value = abs(param.numeric_value)
        scales: List[float] = []
        if value > 0:
            scales.append(value)

        if param.default_value is not None and param.default_value != 0:
            scales.append(abs(param.default_value))

        span: Optional[float] = None
        if param.min_value is not None and param.max_value is not None:
            span = abs(param.max_value - param.min_value)
            if span > 0:
                if param.is_float:
                    scales.append(max(0.05, span / 24.0))
                else:
                    coarse = span / 28.0
                    if span >= 1024:
                        coarse = min(coarse, 512.0)
                    scales.append(max(1.0, coarse))

        hint = PARAM_MUTATION_HINTS.get(param.normalized_name)
        if hint:
            step_hint = hint.get("step")
            if isinstance(step_hint, (int, float)):
                scales.append(max(0.1, float(step_hint)))
            min_step = hint.get("min_step")
            if isinstance(min_step, (int, float)):
                scales.append(max(0.1, float(min_step)))

        if not scales:
            scales.append(1.0 if not param.is_float else 0.25)

        base = max(scales)
        if not param.is_float:
            base = max(base, 1.0)
        else:
            base = max(base, 0.1)

        return base

    def write(self, lines: List[str]) -> None:
        content = "\n".join(lines)
        atomic_write(self.tuning_path, content)

    def checkpoint_best(self, lines: List[str]) -> Path:
        dst = self.tuning_path.with_name(self.tuning_path.stem + ".best" + self.tuning_path.suffix)
        atomic_write(dst, "\n".join(lines))
        return dst

    @staticmethod
    def _phase_from_name(name: str) -> float:
        digest = hashlib.sha256(name.encode("utf-8")).digest()
        integer = int.from_bytes(digest[:8], byteorder="big", signed=False)
        return (integer % 1000) / 1000.0 * 2.0 * math.pi

    def perturb(
            self,
            lines: List[str],
            parameters: Dict[str, NumericParameter],
            iteration: int,
            rng: random.Random,
            temp_start: float,
            temp_min: float,
            temp_decay: float,
            spectral_base: float,
            mut_frac: float = 0.33,
            clamp_mult: float = 5.0,
    ) -> List[str]:
        """Adjust a random subset of numeric parameters with allow/deny/freeze filters."""
        updated_lines = list(lines)

        temperature = max(temp_min, temp_start * math.exp(-iteration / max(1e-9, temp_decay)))
        spectral_frequency = spectral_base + 0.07 * math.sin(iteration * 0.173)

        # Build candidate list with filters
        def allowed(norm_name: str) -> bool:
            if norm_name in self._frozen:
                return False
            if self._allow_re and not self._allow_re.search(norm_name):
                return False
            if self._deny_re and self._deny_re.search(norm_name):
                return False
            return True

        eligible_items = [(k, p) for k, p in parameters.items() if allowed(p.normalized_name)]
        if not eligible_items:
            print("[mut] No eligible parameters to mutate (all filtered/frozen).")
            return updated_lines

        eligible_names = [name for name, _ in eligible_items]
        k = max(1, int(round(len(eligible_names) * max(0.0, min(1.0, mut_frac)))))
        mutate_keys = set(rng.sample(eligible_names, k))
        self._last_mutated = set(mutate_keys)

        for name, param in parameters.items():
            value = param.numeric_value

            if name not in self._initial_magnitudes:
                self._initial_magnitudes[name] = self._derive_initial_scale(param)
            init_mag = self._initial_magnitudes[name]

            if name not in mutate_keys:
                updated_lines[param.line_index] = f"{param.indent}{param.yaml_key}: {param.raw_value}"
                continue

            magnitude = max(abs(value), init_mag)
            hint = PARAM_MUTATION_HINTS.get(param.normalized_name)
            if hint:
                step_hint = hint.get("step")
                if isinstance(step_hint, (int, float)):
                    magnitude = max(magnitude, float(step_hint))
                max_step = hint.get("max_step")
                if isinstance(max_step, (int, float)):
                    magnitude = min(magnitude, max(init_mag, float(max_step)))
            magnitude *= self._step_scale[name]
            if not param.is_float:
                magnitude = max(magnitude, 1.0)
            else:
                magnitude = max(magnitude, 0.1)
            phase = self._phase_from_name(name)

            spectral_component = math.sin(iteration * spectral_frequency + phase)
            carrier_component  = math.cos(iteration * 0.37 - phase / 2.0)
            gaussian_component = rng.gauss(0.0, 0.5)

            logistic_gain = 1.0 / (1.0 + math.exp(-value / (50.0 + magnitude)))
            blend = 0.6 * spectral_component + 0.3 * carrier_component + 0.1 * gaussian_component
            delta = temperature * logistic_gain * blend

            candidate = value + delta * magnitude

            lo = -clamp_mult * init_mag
            hi =  clamp_mult * init_mag
            if candidate < lo:
                candidate = lo + 0.1 * rng.random()
            elif candidate > hi:
                candidate = hi - 0.1 * rng.random()

            soft_min_val: Optional[float] = None
            soft_max_val: Optional[float] = None
            if hint:
                soft_min = hint.get("soft_min")
                if isinstance(soft_min, (int, float)):
                    soft_min_val = float(soft_min)
                    candidate = max(candidate, soft_min_val)
                soft_max = hint.get("soft_max")
                if isinstance(soft_max, (int, float)):
                    soft_max_val = float(soft_max)
                    candidate = min(candidate, soft_max_val)
                sentinels = hint.get("sentinels")
                snap_window = max(0.5, magnitude * (0.1 if not param.is_float else 0.05))
                snapped = False
                if isinstance(sentinels, list) and sentinels:
                    for sentinel in sentinels:
                        if isinstance(sentinel, (int, float)) and abs(candidate - float(sentinel)) <= snap_window:
                            candidate = float(sentinel)
                            snapped = True
                            break
                    probe_prob = hint.get("sentinel_probe")
                    if not snapped and isinstance(probe_prob, (int, float)) and rng.random() < float(probe_prob):
                        valid_sentinels = [float(s) for s in sentinels if isinstance(s, (int, float))]
                        if valid_sentinels:
                            candidate = float(rng.choice(valid_sentinels))

            if param.min_value is not None:
                candidate = max(candidate, param.min_value)
            if param.max_value is not None:
                candidate = min(candidate, param.max_value)
            if soft_min_val is not None:
                candidate = max(candidate, soft_min_val)
            if soft_max_val is not None:
                candidate = min(candidate, soft_max_val)

            if param.is_float:
                formatted = f"{candidate:.{param.decimals}f}"
            else:
                formatted = str(int(round(candidate)))

            updated_lines[param.line_index] = f"{param.indent}{param.yaml_key}: {formatted}"
            param.raw_value = formatted
            try:
                new_value = float(formatted)
            except ValueError:
                new_value = value
            self._initial_magnitudes[name] = max(
                self._initial_magnitudes[name],
                abs(new_value),
                init_mag,
            )

        return updated_lines

    def update_step_scales(
            self,
            decision: AcceptanceDecision,
            best: TestResult,
            candidate: TestResult,
    ) -> Dict[str, object]:
        """Adjust per-parameter step scaling based on the last decision outcome.
           Also track 'bad streaks' and auto-freeze parameters after repeated non-improvements.
        """

        mutated = list(self._last_mutated)
        if not mutated:
            summary: Dict[str, object] = {
                "mutated": 0,
                "grow": {"count": 0, "avg": 1.0},
                "shrink": {"count": 0, "avg": 1.0},
                "frozen_now": 0,
                "frozen_total": len(self._frozen),
            }
            return summary

        shrink_factors: List[float] = []
        grow_factors: List[float] = []

        degrade = candidate.failures > best.failures or candidate.errors > best.errors
        shrink_multiplier = 0.7
        grow_multiplier = 1.25

        if not decision.keep or degrade:
            for key in mutated:
                prev = self._step_scale[key]
                updated = max(0.25, prev * shrink_multiplier)
                self._step_scale[key] = updated
                factor = updated / prev if prev > 0 else shrink_multiplier
                shrink_factors.append(factor)
        elif decision.keep and decision.improved:
            for key in mutated:
                prev = self._step_scale[key]
                updated = min(3.0, prev * grow_multiplier)
                self._step_scale[key] = updated
                factor = updated / prev if prev > 0 else grow_multiplier
                grow_factors.append(factor)

        # NEW: bad-streak tracking & freezing
        newly_frozen = 0
        if decision.keep and decision.improved:
            for key in mutated:
                self._bad_streak[key] = 0
        else:
            for key in mutated:
                self._bad_streak[key] += 1
                if self._bad_streak[key] >= max(1, self._freeze_after):
                    norm = key.lower()
                    if norm not in self._frozen:
                        self._frozen.add(norm)
                        newly_frozen += 1

        summary = {
            "mutated": len(mutated),
            "grow": {
                "count": len(grow_factors),
                "avg": (sum(grow_factors) / len(grow_factors)) if grow_factors else 1.0,
            },
            "shrink": {
                "count": len(shrink_factors),
                "avg": (sum(shrink_factors) / len(shrink_factors)) if shrink_factors else 1.0,
            },
            "frozen_now": newly_frozen,
            "frozen_total": len(self._frozen),
        }

        self._last_mutated.clear()
        return summary

# ----------------------------
# Maven test runner (with JVM/system props parity)
# ----------------------------

def _aggregate_test_totals_from_reports(project_root: Path) -> Tuple[int, int, int, int]:
    patterns = [
        str(project_root / "**" / "target" / "surefire-reports" / "TEST-*.xml"),
        str(project_root / "**" / "target" / "failsafe-reports" / "TEST-*.xml"),
    ]
    files: List[str] = []
    for pat in patterns:
        files.extend(glob.glob(pat, recursive=True))

    if not files:
        raise RuntimeError(
            "No test report XMLs found in any module.\n"
            f"  {patterns[0]}\n  {patterns[1]}"
        )

    total = failures = errors = skipped = 0

    def add_suite(suite):
        nonlocal total, failures, errors, skipped
        t  = int(suite.attrib.get("tests", 0))
        fz = int(suite.attrib.get("failures", 0))
        er = int(suite.attrib.get("errors", 0))
        sk = int(suite.attrib.get("skipped", suite.attrib.get("ignored", 0)) or 0)
        total += t; failures += fz; errors += er; skipped += sk

    for f in files:
        try:
            tree = ET.parse(f)
            root = tree.getroot()
            if root.tag == "testsuite":
                add_suite(root)
            else:
                for suite in root.findall(".//testsuite"):
                    add_suite(suite)
        except Exception:
            continue

    return total, failures, errors, skipped


def _safe_float(value: object) -> Optional[float]:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def _safe_int(value: object) -> Optional[int]:
    if value is None:
        return None
    if isinstance(value, int):
        return value
    try:
        return int(str(value).strip())
    except (TypeError, ValueError):
        return None


def _percentile(sorted_values: List[float], quantile: float) -> Optional[float]:
    if not sorted_values:
        return None
    if not 0.0 <= quantile <= 1.0:
        return None
    if len(sorted_values) == 1:
        return sorted_values[0]
    position = quantile * (len(sorted_values) - 1)
    lower_index = int(math.floor(position))
    upper_index = int(math.ceil(position))
    lower = sorted_values[lower_index]
    upper = sorted_values[upper_index]
    if lower_index == upper_index:
        return lower
    weight = position - lower_index
    return lower + (upper - lower) * weight


def _parse_best_move_metrics(stdout: str, stderr: str) -> Tuple[List[Dict], Dict]:
    records: List[Dict] = []
    summary: Dict = {}
    for stream in (stdout, stderr):
        if not stream:
            continue
        for line in stream.splitlines():
            stat_match = BMSTAT_PATTERN.search(line)
            if stat_match:
                try:
                    records.append(json.loads(stat_match.group(1)))
                except json.JSONDecodeError:
                    pass
            sum_match = BMSUM_PATTERN.search(line)
            if sum_match:
                try:
                    summary = json.loads(sum_match.group(1))
                except json.JSONDecodeError:
                    pass
    summary = {k: v for k, v in summary.items() if v is not None}

    if records:
        durations = [
            val for val in (
                _safe_float(record.get("durationMs")) for record in records
            )
            if val is not None
        ]
        nodes = [
            val for val in (
                _safe_float(record.get("nodes")) for record in records
            )
            if val is not None
        ]
        ranks = [
            val for val in (
                _safe_int(record.get("rank")) for record in records
            )
            if val is not None and val > 0
        ]

        summary.setdefault("positions", len(records))

        if durations:
            durations.sort()
            median = statistics.median(durations)
            p95 = _percentile(durations, 0.95)
            summary["durationMsMedian"] = median
            if p95 is not None:
                summary["durationMsP95"] = p95
            summary["durationMsMax"] = max(durations)
            summary["durationMsMin"] = min(durations)
            slow_threshold = p95 if p95 is not None else median * 1.5
            if slow_threshold:
                slow_positions = sum(1 for d in durations if d >= slow_threshold)
                summary["slowPositionCount"] = slow_positions
                summary["slowPositionRate"] = slow_positions / len(durations)
        if nodes:
            nodes.sort()
            summary["nodesP95"] = _percentile(nodes, 0.95) or nodes[-1]
            summary["nodesMax"] = max(nodes)
            summary["nodesMedian"] = statistics.median(nodes)
        if ranks:
            misses = sum(1 for r in ranks if r > 1)
            summary["missCount"] = misses
            summary["missRate"] = misses / len(records)

        # Track the slowest FEN for diagnostics
        slowest_record: Optional[Dict] = None
        slowest_duration = -1.0
        for record in records:
            duration = _safe_float(record.get("durationMs"))
            if duration is None:
                continue
            if duration > slowest_duration:
                slowest_duration = duration
                slowest_record = record
        if slowest_record:
            if slowest_duration >= 0.0:
                summary.setdefault("durationMsMax", slowest_duration)
            summary["slowestFen"] = slowest_record.get("fen")
            summary["slowestExpected"] = slowest_record.get("expectedCandidates")

    return records, summary


def _compose_argline(
        use_preview: bool,
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str],
        engine_sysprops: Dict[str, str],
) -> str:
    parts: List[str] = []
    if use_preview:
        parts.append("--enable-preview")
    parts.extend(_derive_jvm_flags(jvm_gc, xms, xmx, apc, conc_gc_threads, ci_compiler_count, soft_max))
    for k, v in engine_sysprops.items():
        parts.append(f"-D{k}={v}")
    return " ".join(parts)


def run_best_move_tests(
        project_root: Path,
        mvn_bin: str,
        test_name: str,
        java_release: int,
        use_preview: bool,
        extra_maven_args: List[str],
        # JVM & engine parity inputs:
        jvm_gc: str,
        xms: str,
        xmx: str,
        apc: int,
        conc_gc_threads: Optional[int],
        ci_compiler_count: Optional[int],
        soft_max: Optional[str],
        engine_sysprops: Dict[str, str],
) -> TestResult:
    """
    Execute Maven tests and parse results.
      - Puts JVM flags + engine system properties into Surefire's fork via -DargLine="...".
    """
    arg_line_str = _compose_argline(
        use_preview, jvm_gc, xms, xmx, apc, conc_gc_threads, ci_compiler_count, soft_max, engine_sysprops
    )

    base_cmd = [
        mvn_bin,
        f"-Djava.version={java_release}",
        f"-Dmaven.compiler.source={java_release}",
        f"-Dmaven.compiler.target={java_release}",
        f"-Dmaven.compiler.enablePreview={'true' if use_preview else 'false'}",
        f"-DargLine={arg_line_str}",
        "-DskipTests=false",
        "-Dsurefire.printSummary=true",
        f"-Dtest={test_name}",
        "surefire:test",
        *extra_maven_args,
    ]

    t0 = time.perf_counter()
    proc = subprocess.run(
        base_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
        cwd=str(project_root),
    )
    dt = time.perf_counter() - t0

    stdout = proc.stdout or ""
    stderr = proc.stderr or ""
    decision_stats, summary_metrics = _parse_best_move_metrics(stdout, stderr)

    matches = TEST_SUMMARY_PATTERN.findall(stdout) or TEST_SUMMARY_PATTERN.findall(stderr)
    if matches:
        total, failures, errors, skipped = map(int, matches[-1])
        return TestResult(
            total,
            failures,
            errors,
            skipped,
            stdout,
            stderr,
            dt,
            metrics=summary_metrics,
            decision_stats=decision_stats,
        )

    try:
        total, failures, errors, skipped = _aggregate_test_totals_from_reports(project_root)
        return TestResult(
            total,
            failures,
            errors,
            skipped,
            stdout,
            stderr,
            dt,
            metrics=summary_metrics,
            decision_stats=decision_stats,
        )
    except Exception as ex:
        logs_dir = project_root / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        out_path = logs_dir / f"maven_stdout_{timestamp()}.log"
        err_path = logs_dir / f"maven_stderr_{timestamp()}.log"
        out_path.write_text(stdout, encoding="utf-8")
        err_path.write_text(stderr, encoding="utf-8")

        shortened = textwrap.shorten((stdout or stderr) or "", width=1000, placeholder=" …")
        raise RuntimeError(
            "Failed to parse test summary from Maven output or any module reports.\n"
            f"Command: {' '.join(base_cmd)}\n"
            f"Output snippet:\n{shortened}\n"
            f"Fallback error: {ex}\n"
            f"Full outputs dumped to:\n  {out_path}\n  {err_path}"
        )


# ----------------------------
# Scoring and acceptance
# ----------------------------

def score_tuple(res: TestResult, duration_metric: str) -> Tuple[float, float, float, float, float, float, float]:
    return res.score_tuple(duration_metric)


def tuple_better(
        a: Tuple[float, float, float, float, float, float, float],
        b: Tuple[float, float, float, float, float, float, float],
) -> bool:
    return a > b


def scalar_score(res: TestResult, duration_metric: str, duration_weight: float) -> float:
    avg_cp_loss = float(res.metrics.get("avgCpLoss", 0.0) or 0.0)
    top1_rate = float(res.metrics.get("top1Rate", 0.0) or 0.0)
    duration_priority = res.metric_value(duration_metric)
    if duration_priority is None or math.isnan(duration_priority):
        duration_priority = res.metric_value("avgDurationMs")
    duration_priority_sec = 0.0
    if duration_priority is not None and not math.isnan(duration_priority):
        duration_priority_sec = float(duration_priority) / 1000.0
    else:
        duration_priority_sec = res.duration_s
    return (
            (res.successes * 1.0)
            - (res.failures * 0.6)
            - (res.errors * 1.5)
            - (0.02 * res.duration_s)
            - (duration_weight * duration_priority_sec)
            - (0.005 * avg_cp_loss)
            + (0.5 * top1_rate)
    )


@dataclass
class AcceptanceDecision:
    keep: bool
    improved: bool
    reason: str
    info: Dict[str, float] = dataclasses.field(default_factory=dict)


def _clamp(value: float, lower: float, upper: float) -> float:
    if lower > upper:
        lower, upper = upper, lower
    return max(lower, min(upper, value))


def _effective_mut_frac(base: float, floor: float, ceiling: float, no_improve: int) -> float:
    """Return the mutation fraction applied for this iteration."""
    if no_improve <= 0:
        return _clamp(base, floor, ceiling)
    plateau_pull = min(0.65, 0.1 * no_improve)
    adjusted = base - (base - floor) * plateau_pull
    return _clamp(adjusted, floor, ceiling)


def _mut_frac_next(
        previous_used: float,
        decision: AcceptanceDecision,
        candidate: TestResult,
        prior_best: TestResult,
        no_improve: int,
        floor: float,
        ceiling: float,
) -> float:
    value = _clamp(previous_used, floor, ceiling)

    if candidate.successes < prior_best.successes:
        value -= (value - floor) * 0.6
    elif candidate.failures > prior_best.failures or candidate.errors > prior_best.errors:
        value -= (value - floor) * 0.4

    if decision.keep and decision.improved:
        value += (ceiling - value) * 0.5
    elif decision.keep and decision.reason == "annealed":
        delta = decision.info.get("delta")
        regress = abs(delta) if isinstance(delta, (int, float)) else 0.0
        if (
                regress >= 0.5
                or candidate.failures > prior_best.failures
                or candidate.errors > prior_best.errors
        ):
            value += (ceiling - value) * 0.35
        else:
            value -= (value - floor) * 0.2
    elif not decision.keep:
        plateau_pull = min(0.5, no_improve / 8.0)
        value -= (value - floor) * (0.2 + 0.5 * plateau_pull)
    else:
        plateau_pull = min(0.4, no_improve / 10.0)
        value -= (value - floor) * (0.1 + 0.4 * plateau_pull)

    return _clamp(value, floor, ceiling)


def _time_improvement(
        best: TestResult,
        cand: TestResult,
        duration_metric: str,
) -> Tuple[bool, float]:
    best_metric = best.metric_value(duration_metric)
    cand_metric = cand.metric_value(duration_metric)
    if best_metric is None or cand_metric is None:
        return False, 0.0
    if math.isnan(best_metric) or math.isnan(cand_metric):
        return False, 0.0
    if best_metric <= 0.0:
        return False, 0.0
    if cand_metric >= best_metric:
        return False, 0.0
    drop = (best_metric - cand_metric) / best_metric
    return True, drop


def accept_candidate(
        best: TestResult,
        cand: TestResult,
        temp: float,
        allow_worse: bool,
        rng: random.Random,
        duration_metric: str,
        duration_bonus_threshold: float,
        max_failure_regress: int,
        max_error_regress: int,
        duration_weight: float,
) -> AcceptanceDecision:
    bt = score_tuple(best, duration_metric)
    ct = score_tuple(cand, duration_metric)
    if tuple_better(ct, bt):
        return AcceptanceDecision(True, True, "improved")

    time_drop_flag, drop_ratio = _time_improvement(best, cand, duration_metric)
    if (
            time_drop_flag
            and drop_ratio >= max(0.0, duration_bonus_threshold)
            and cand.failures <= best.failures + max(0, max_failure_regress)
            and cand.errors <= best.errors + max(0, max_error_regress)
    ):
        return AcceptanceDecision(True, True, "time_bonus", {"time_drop": drop_ratio})

    if not allow_worse:
        return AcceptanceDecision(False, False, "rejected")

    db = (
            scalar_score(cand, duration_metric, duration_weight)
            - scalar_score(best, duration_metric, duration_weight)
    )
    if time_drop_flag:
        db += max(0.0, drop_ratio) * duration_weight
    prob = math.exp(db / max(1e-9, temp))
    if rng.random() < prob:
        return AcceptanceDecision(True, False, "annealed", {"prob": prob, "delta": db})
    return AcceptanceDecision(False, False, "annealed_reject", {"prob": prob, "delta": db})


# ----------------------------
# Logging
# ----------------------------

def log_jsonl(path: Path, record: dict) -> None:
    line = json.dumps(record, ensure_ascii=False)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as f:
        f.write(line + "\n")


def save_iteration_config(base_dir: Path, iteration: int, result: TestResult, lines: List[str]) -> Path:
    """Persist the tuning file for a single iteration with a descriptive name."""

    successes = result.successes
    duration_value = f"{result.duration_s:.2f}"
    duration_clean = duration_value.rstrip("0").rstrip(".") if "." in duration_value else duration_value
    filename = f"Iteration-{iteration}-{successes}-{duration_clean}.yaml"
    path = base_dir / filename
    path.parent.mkdir(parents=True, exist_ok=True)

    content = "\n".join(lines)
    if not content.endswith("\n"):
        content += "\n"
    path.write_text(content, encoding="utf-8")
    return path


# ----------------------------
# Main
# ----------------------------

def main() -> None:
    parser = argparse.ArgumentParser(description="Automated tuning loop for BestMoveSearchTest.")
    parser.add_argument("--tuning-path", type=Path, default=Path("src/main/resources/tuning/seed-tunings.yaml"))
    parser.add_argument("--project-root", type=Path, default=None)
    parser.add_argument("--mvn", type=str, default="mvn")
    parser.add_argument("--test", type=str, default="BestMoveSearchTest")
    parser.add_argument("--java-release", type=int, default=25)
    parser.add_argument("--preview", action="store_true", default=True)
    parser.add_argument("--no-preview", dest="preview", action="store_false")
    parser.add_argument("--extra-maven-args", nargs=argparse.REMAINDER, default=[])

    parser.add_argument("--max-iters", type=int, default=0)
    parser.add_argument("--seed", type=int, default=None)

    # Exploration knobs
    parser.add_argument("--temp-start", type=float, default=0.35)
    parser.add_argument("--temp-min",   type=float, default=0.05)
    parser.add_argument("--temp-decay", type=float, default=12.0)
    parser.add_argument("--spectral-base", type=float, default=0.61)

    parser.add_argument("--accept-worse", action="store_true")
    parser.add_argument("--accept-temp",  type=float, default=0.08)
    parser.add_argument(
        "--mut-frac",
        type=float,
        default=0.33,
        help=(
            "Initial mutation fraction before adaptive scaling toward the configured bounds. "
            "Defaults mirror the previous fixed value so widening the range is opt-in."
        ),
    )
    parser.add_argument(
        "--mut-frac-min",
        type=float,
        default=0.33,
        help=(
            "Lower bound for the adaptive mutation fraction. The loop drifts toward this when iterations stall, "
            "lose successes, or revert to the best checkpoint (defaults to the fixed 0.33 behaviour)."
        ),
    )
    parser.add_argument(
        "--mut-frac-max",
        type=float,
        default=0.33,
        help=(
            "Upper bound for the adaptive mutation fraction. Improvements and high-cost annealing pushes nudge toward "
            "this ceiling (defaults to 0.33 for backwards compatibility)."
        ),
    )
    parser.add_argument("--noimp-reheat", type=int,   default=10)
    parser.add_argument("--reheat-factor",type=float, default=1.7)
    parser.add_argument("--priority-duration-metric", type=str, default="durationMsP95",
                        help="Primary duration metric key (e.g., durationMsP95, durationMsMedian, avgDurationMs).")
    parser.add_argument("--duration-bonus-threshold", type=float, default=0.2,
                        help="Fractional drop in the priority duration metric that qualifies as a time-based improvement.")
    parser.add_argument("--max-failure-regress", type=int, default=1,
                        help="Maximum additional test failures allowed when accepting via time bonus.")
    parser.add_argument("--max-error-regress", type=int, default=0,
                        help="Maximum additional test errors allowed when accepting via time bonus.")
    parser.add_argument("--duration-weight", type=float, default=1.1,
                        help="Score penalty per second of the priority duration metric for annealing decisions.")

    # NEW: Filtering & auto-freeze controls
    parser.add_argument("--allow-pattern", type=str, default="",
                        help="Regex over normalized parameter names to ALLOW (case-insensitive). Empty = allow all.")
    parser.add_argument("--deny-pattern", type=str, default="",
                        help="Regex over normalized parameter names to DENY (case-insensitive).")
    parser.add_argument("--freeze-after", type=int, default=8,
                        help="If a parameter is mutated this many times without an accepted improvement, freeze it.")

    parser.add_argument("--log-jsonl", type=Path, default=Path("logs/auto_tuning_log.jsonl"))
    parser.add_argument("--git-commit", action="store_true")

    # -------- JVM & Engine parity options (mirroring lichess_bot) --------
    parser.add_argument("--jvm-gc", type=str, default=os.environ.get("JAVA_GC", "zgc"),
                        help='GC: zgc|shenandoah|g1 or a custom JVM flag.')
    parser.add_argument("--xms", type=str, default=os.environ.get("JAVA_XMS", "auto"),
                        help='Heap Xms (e.g., 6g, 4096m) or "auto".')
    parser.add_argument("--xmx", type=str, default=os.environ.get("JAVA_XMX", "auto"),
                        help='Heap Xmx (e.g., 6g, 4096m) or "auto".')
    parser.add_argument("--apc", type=str, default="auto",
                        help='ActiveProcessorCount value or "auto".')
    parser.add_argument("--concgc", type=str, default="auto",
                        help='ConcGCThreads value or "auto".')
    parser.add_argument("--cicomp", type=str, default="auto",
                        help='CICompilerCount value or "auto".')

    parser.add_argument("--tt-mb", type=int, default=int(os.environ.get("CHESSENGINE_TT_MB", "1024")))
    parser.add_argument("--engine-threads", type=str, default=os.environ.get("CHESSENGINE_THREADS", "auto"))
    parser.add_argument("--lazy-threads", type=str, default=os.environ.get("CHESSENGINE_LAZY_THREADS", "auto"))
    parser.add_argument("--root-par-limit", type=str, default=os.environ.get("CHESSENGINE_ROOT_PAR_LIMIT", "auto"))
    parser.add_argument("--tuning-file", type=str, default=os.environ.get("CHESSENGINE_TUNING_FILE", ""),
                        help="Optional absolute path to tuning file passed as -Dchessengine.tuning.file.")

    # A hint for planning: how many games concurrently during tests (kept 1)
    parser.add_argument("--plan-concurrent", type=int, default=1)

    args = parser.parse_args()

    if args.mut_frac_min <= 0.0 or args.mut_frac_max <= 0.0:
        parser.error("--mut-frac-min and --mut-frac-max must be positive.")
    if args.mut_frac_min > args.mut_frac_max:
        parser.error("--mut-frac-min must be less than or equal to --mut-frac-max.")
    if args.mut_frac <= 0.0:
        parser.error("--mut-frac must be positive.")

    raw_tuning_path: Path = args.tuning_path

    if args.project_root:
        project_root: Path = args.project_root.resolve()
    else:
        probe_path = raw_tuning_path if raw_tuning_path.is_absolute() else (Path.cwd() / raw_tuning_path)
        project_root = find_project_root(probe_path.parent)

    project_root = project_root.resolve()

    if raw_tuning_path.is_absolute():
        tuning_path = raw_tuning_path.resolve()
    else:
        tuning_path = (project_root / raw_tuning_path).resolve()
    if args.log_jsonl.is_absolute():
        log_jsonl_path = args.log_jsonl
    else:
        log_jsonl_path = (project_root / args.log_jsonl).resolve()
    iteration_config_dir = log_jsonl_path.parent / "iteration-configs"
    args.log_jsonl = log_jsonl_path

    mvn_bin: str       = args.mvn
    extra_args: List[str] = args.extra_maven_args

    print(f"Project root: {project_root}")
    print(f"Tuning file : {tuning_path}")

    optimizer = SeedTuningOptimizer(tuning_path)

    # Wire up filters & freeze settings
    if args.allow_pattern:
        try:
            optimizer._allow_re = re.compile(args.allow_pattern, re.IGNORECASE)
        except re.error as e:
            print(f"[warn] Invalid --allow-pattern regex: {e}. Ignoring.", file=sys.stderr)
    if args.deny_pattern:
        try:
            optimizer._deny_re = re.compile(args.deny_pattern, re.IGNORECASE)
        except re.error as e:
            print(f"[warn] Invalid --deny-pattern regex: {e}. Ignoring.", file=sys.stderr)
    optimizer._freeze_after = max(1, int(args.freeze_after))

    rng = random.Random()
    if args.seed is None:
        args.seed = time.time_ns() & 0xFFFFFFFF
    rng.seed(args.seed)
    print(f"RNG seed    : {args.seed}")

    mut_frac_floor = args.mut_frac_min
    mut_frac_ceiling = args.mut_frac_max
    mut_frac_state = _clamp(args.mut_frac, mut_frac_floor, mut_frac_ceiling)

    # ---------- Plan parallelism (parity with lichess_bot)
    plan = _auto_thread_plan(max_concurrent_games=max(1, args.plan_concurrent))
    def _resolve_int(opt: str, auto_val: int) -> int:
        return auto_val if str(opt).strip().lower() == "auto" else int(opt)

    search_threads = _resolve_int(args.engine_threads, plan["search"]) if str(args.engine_threads).isdigit() or args.engine_threads == "auto" else int(args.engine_threads)
    lazy_threads   = _resolve_int(args.lazy_threads,   plan["lazy"])   if str(args.lazy_threads).isdigit()   or args.lazy_threads   == "auto" else int(args.lazy_threads)
    root_limit     = _resolve_int(args.root_par_limit, plan["root_limit"]) if str(args.root_par_limit).isdigit() or args.root_par_limit == "auto" else int(args.root_par_limit)

    planned_total  = max(1, search_threads + lazy_threads)
    apc_val        = _resolve_int(args.apc, plan["planned_total"]) if args.apc == "auto" else int(args.apc)

    # GC helpers (keep conservative; scale modestly)
    logical, physical = _detect_cpus()
    conc_gc_threads = None if args.concgc == "auto" else int(args.concgc)
    ci_comp_count   = None if args.cicomp == "auto" else int(args.cicomp)
    if args.concgc == "auto":
        conc_gc_threads = max(1, min(4, apc_val // 2))
    if args.cicomp == "auto":
        ci_comp_count = max(1, min(8, apc_val))

    # Heap sizing
    if args.xms.lower() == "auto" or args.xmx.lower() == "auto":
        xms_auto, xmx_auto, soft_max = _auto_heap(args.tt_mb)
        xms = xms_auto if args.xms.lower() == "auto" else args.xms
        xmx = xmx_auto if args.xmx.lower() == "auto" else args.xmx
    else:
        xms = args.xms
        xmx = args.xmx
        soft_max = None

    print(
        f"[plan] CPUs(logical/physical)={logical}/{physical} | "
        f"search={search_threads} lazy={lazy_threads} rootParallelLimit={root_limit} | "
        f"APC={apc_val} ConcGCThreads={conc_gc_threads} CICompilerCount={ci_comp_count} | "
        f"Xms={xms} Xmx={xmx} GC={args.jvm_gc}"
    )

    # Engine system properties (mirroring the bot defaults)
    engine_sysprops = {
        "chessengine.searchThreads": str(search_threads),
        "chessengine.lazySmpThreads": str(lazy_threads),
        "chessengine.rootParallelLimit": str(root_limit),
        "chessengine.tt.mb": str(args.tt_mb),
        "logging.level.root": "INFO",
        # Keep UCI verbosity modest in tests
        "chessengine.uci.info.minIntervalMs": "200",
        "chessengine.uci.info.maxPvLen": "10",
    }

    syzygy_native, syzygy_paths = resolve_syzygy_from_env()
    if syzygy_native:
        engine_sysprops["chessengine.syzygy.nativeLibrary"] = syzygy_native
    if syzygy_paths:
        engine_sysprops["chessengine.syzygy.paths"] = syzygy_paths
    if args.tuning_file:
        override_path = Path(args.tuning_file)
        if not override_path.is_absolute():
            override_path = (project_root / override_path).resolve()
        else:
            override_path = override_path.resolve()
        engine_sysprops["chessengine.tuning.file"] = str(override_path)
    else:
        engine_sysprops["chessengine.tuning.file"] = str(tuning_path)

    # ---------- Initial backup & load
    optimizer.backup("initial")
    best_lines, _ = optimizer.load()
    best_content = list(best_lines)

    # ---------- Baseline
    print("\nRunning initial test suite…")
    baseline_result = run_best_move_tests(
        project_root=project_root,
        mvn_bin=mvn_bin,
        test_name=args.test,
        java_release=args.java_release,
        use_preview=args.preview,
        extra_maven_args=extra_args,
        jvm_gc=args.jvm_gc,
        xms=xms,
        xmx=xmx,
        apc=apc_val,
        conc_gc_threads=conc_gc_threads,
        ci_compiler_count=ci_comp_count,
        soft_max=soft_max,
        engine_sysprops=engine_sysprops,
    )
    print("Baseline   :", baseline_result.summary())

    best_result = baseline_result
    best_checkpoint = optimizer.checkpoint_best(best_content)
    print(f"Best checkpoint saved to: {best_checkpoint}")

    iteration = 0
    no_improve = 0
    temp_boost = 1.0
    current_content = list(best_content)

    try:
        while True:
            iteration += 1
            if args.max_iters and iteration > args.max_iters:
                print("\nReached max iterations; stopping.")
                break

            print(f"\n=== Iteration {iteration} ===")

            previous_best_result = best_result
            current_lines, current_parameters = optimizer.load()
            eff_temp_start = args.temp_start * temp_boost
            effective_mut_frac = _effective_mut_frac(
                mut_frac_state,
                mut_frac_floor,
                mut_frac_ceiling,
                no_improve,
            )
            print(f"[mut] Effective mutation fraction: {effective_mut_frac:.4f}")

            candidate_lines = optimizer.perturb(
                current_lines,
                current_parameters,
                iteration,
                rng,
                temp_start=eff_temp_start,
                temp_min=args.temp_min,
                temp_decay=args.temp_decay,
                spectral_base=args.spectral_base,
                mut_frac=effective_mut_frac,
                clamp_mult=5.0,
            )
            optimizer.write(candidate_lines)

            try:
                candidate_result = run_best_move_tests(
                    project_root=project_root,
                    mvn_bin=mvn_bin,
                    test_name=args.test,
                    java_release=args.java_release,
                    use_preview=args.preview,
                    extra_maven_args=extra_args,
                    jvm_gc=args.jvm_gc,
                    xms=xms,
                    xmx=xmx,
                    apc=apc_val,
                    conc_gc_threads=conc_gc_threads,
                    ci_compiler_count=ci_comp_count,
                    soft_max=soft_max,
                    engine_sysprops=engine_sysprops,
                )
            except Exception as exc:
                print(f"Test execution failed: {exc}. Reverting changes.")
                optimizer.write(best_content)
                current_content = list(best_content)
                log_jsonl(
                    args.log_jsonl,
                    {"ts": timestamp(), "iter": iteration, "event": "test_execution_failed", "error": str(exc)},
                )
                continue

            print("Candidate  :", candidate_result.summary())

            snapshot_path = save_iteration_config(
                iteration_config_dir,
                iteration,
                candidate_result,
                candidate_lines,
            )
            print(f"Saved iteration config: {snapshot_path}")

            decision = accept_candidate(
                best=best_result,
                cand=candidate_result,
                temp=args.accept_temp,
                allow_worse=args.accept_worse,
                rng=rng,
                duration_metric=args.priority_duration_metric,
                duration_bonus_threshold=args.duration_bonus_threshold,
                max_failure_regress=args.max_failure_regress,
                max_error_regress=args.max_error_regress,
                duration_weight=args.duration_weight,
            )
            scale_summary = optimizer.update_step_scales(decision, best_result, candidate_result)
            if (
                    isinstance(scale_summary.get("grow"), dict)
                    and scale_summary["grow"].get("count", 0)
            ) or (
                    isinstance(scale_summary.get("shrink"), dict)
                    and scale_summary["shrink"].get("count", 0)
            ) or scale_summary.get("frozen_now", 0):
                print("[step-scale]", json.dumps(scale_summary))

            if decision.keep:
                current_content = list(candidate_lines)
                if decision.improved:
                    if decision.reason == "time_bonus":
                        drop_pct = decision.info.get("time_drop", 0.0) * 100.0
                        print(
                            "Accepted via time bonus "
                            f"({args.priority_duration_metric} ↓ {drop_pct:.1f}%). Updating best parameters."
                        )
                    else:
                        print("Improvement detected. Keeping new tuning parameters.")
                    best_content = list(candidate_lines)
                    best_result = candidate_result
                    best_checkpoint = optimizer.checkpoint_best(best_content)
                    print(f"Updated best checkpoint: {best_checkpoint}")
                    no_improve = 0

                    if args.git_commit:
                        try:
                            msg = (
                                f"Auto-tune: {best_result.successes} ok, "
                                f"{best_result.failures} fail, {best_result.errors} err "
                                f"({args.test}, iter {iteration}, {decision.reason})"
                            )
                            subprocess.run(["git", "add", str(tuning_path)], cwd=str(project_root), check=False)
                            subprocess.run(["git", "commit", "-m", msg], cwd=str(project_root), check=False)
                        except Exception as git_exc:
                            print(f"(Non-fatal) Git commit failed: {git_exc}")
                else:
                    prob = decision.info.get("prob")
                    delta = decision.info.get("delta")
                    detail = ""
                    if prob is not None and delta is not None:
                        detail = f" (Δscore={delta:.3f}, p={prob:.3f})"
                    print("Accepted worse candidate via annealing for exploration." + detail)
                    no_improve += 1
            else:
                detail = ""
                if decision.reason.startswith("annealed"):
                    prob = decision.info.get("prob")
                    delta = decision.info.get("delta")
                    if prob is not None and delta is not None:
                        detail = f" (Δscore={delta:.3f}, p={prob:.3f})"
                print("No improvement. Reverting to previous best parameters." + detail)
                optimizer.write(best_content)
                current_content = list(best_content)
                no_improve += 1
                if args.noimp_reheat and (no_improve % args.noimp_reheat == 0):
                    temp_boost *= args.reheat_factor
                    print(
                        f"(Plateau) Reheating: temp_start ×= {args.reheat_factor:.2f} "
                        f"-> {args.temp_start * temp_boost:.4f}"
                    )

            mut_frac_state = _mut_frac_next(
                effective_mut_frac,
                decision,
                candidate_result,
                previous_best_result,
                no_improve,
                mut_frac_floor,
                mut_frac_ceiling,
            )

            log_jsonl(
                args.log_jsonl,
                {
                    "ts": timestamp(),
                    "iter": iteration,
                    "seed": args.seed,
                    "temp_start_effective": eff_temp_start,
                    "mut_frac": args.mut_frac,
                    "mut_frac_used": effective_mut_frac,
                    "mut_frac_next": mut_frac_state,
                    "mut_frac_bounds": [mut_frac_floor, mut_frac_ceiling],
                    "candidate": dataclasses.asdict(candidate_result),
                    "best": dataclasses.asdict(best_result),
                    "accepted": decision.keep,
                    "decision": {
                        "keep": decision.keep,
                        "improved": decision.improved,
                        "reason": decision.reason,
                        "info": decision.info,
                    },
                    "step_scale": scale_summary,
                    "no_improve": no_improve,
                    "filters": {
                        "allow": args.allow_pattern or None,
                        "deny": args.deny_pattern or None,
                        "freeze_after": optimizer._freeze_after,
                        "frozen_total": len(optimizer._frozen),
                    },
                },
            )

    except KeyboardInterrupt:
        print("\nStopping optimization loop by user request.")
    finally:
        optimizer.write(best_content)
        print("Final best :", best_result.summary())
        print("Done.")


if __name__ == "__main__":
    try:
        main()
    except Exception as err:
        print(f"Fatal error: {err}", file=sys.stderr)
        sys.exit(1)
