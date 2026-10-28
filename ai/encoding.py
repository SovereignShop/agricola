"""Versioned numeric encoding for observations and parameterized actions.

Named engine features retain quantities and spatial/card identity. A bounded
cache amortizes name hashing across games; action vectors are additionally
cached by their semantic command ID in the replay buffer. Encoding never uses
Python's randomized hash(), engine snapshots, or opponent private cards.
"""
from functools import lru_cache
import hashlib
import math
import numpy as np
from pathlib import Path
from edn_format import loads, Keyword

SCHEMA_PATH = Path(__file__).resolve().parents[1] / "resources/agricola/learning.edn"
SCHEMA = loads(SCHEMA_PATH.read_text())
ENCODING = SCHEMA[Keyword("encoding")]
VERSION = ENCODING[Keyword("version")]
OBS_DIM = ENCODING[Keyword("observation-dim")]
ACTION_DIM = ENCODING[Keyword("action-dim")]
SEATS = ENCODING[Keyword("player-slots")]
HIDDEN = ENCODING[Keyword("hidden-size")]
ACTION_SPACE = SCHEMA[Keyword("action-space")]
if (ENCODING[Keyword("hash")], ENCODING[Keyword("transform")], ENCODING[Keyword("dtype")]) != ("blake2b-64", "signed-log1p", "float32"):
    raise ValueError("Unsupported EDN encoder definition; implement it before loading this schema")


@lru_cache(maxsize=262144)
def coordinate(name, size):
    digest = hashlib.blake2b(name.encode(), digest_size=8).digest()
    return int.from_bytes(digest[:4], "little") % size, 1 if digest[4] & 1 else -1


def encode(features, size):
    out = np.zeros(size, dtype=np.float32)
    for name, value in features:
        index, sign = coordinate(name, size)
        out[index] += sign * math.copysign(math.log1p(abs(value)), value)
    return out
