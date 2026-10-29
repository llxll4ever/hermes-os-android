"""FastAPI endpoint mounted automatically at /api/plugins/hermes-mobile-wall/wall."""

from __future__ import annotations

import sys
from pathlib import Path
from fastapi import APIRouter

_ROOT = str(Path(__file__).resolve().parent.parent)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

import wall_store  # noqa: E402

router = APIRouter()


@router.get("/wall")
async def get_wall_snapshot() -> dict:
    return {"ok": True, **wall_store.snapshot()}
