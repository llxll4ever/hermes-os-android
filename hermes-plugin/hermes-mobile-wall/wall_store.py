"""Shared ring-buffer store for Hermes OS Android Live Bot Wall."""

from __future__ import annotations

import json
import os
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

_LOCK = threading.Lock()


def _shared_state_path() -> Path:
    root = Path(os.environ.get("HERMES_HOME") or (Path.home() / ".hermes"))
    # Resolve to top-level ~/.hermes/shared even when invoked inside ~/.hermes/profiles/<name>
    if root.parent.name == "profiles":
        root = root.parent.parent
    shared_dir = root / "shared"
    shared_dir.mkdir(parents=True, exist_ok=True)
    return shared_dir / "mobile_wall_state.json"


def _read_unlocked(path: Path) -> dict[str, Any]:
    try:
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                return data
    except Exception:
        pass
    return {"bots": {}, "feed": [], "updated_at": time.time()}


def record_bot_state(
    profile: str,
    state: str,
    activity: str,
    *,
    tool_name: str | None = None,
    session_id: str | None = None,
    room_id: str | None = None,
) -> dict[str, Any]:
    prof = (profile or os.environ.get("HERMES_PROFILE") or "default").strip() or "default"
    now = time.time()
    path = _shared_state_path()
    with _LOCK:
        doc = _read_unlocked(path)
        bots = doc.setdefault("bots", {})
        bots[prof] = {
            "profile": prof,
            "state": state,
            "activity": activity[:200],
            "tool_name": tool_name,
            "session_id": session_id,
            "room_id": room_id,
            "updated_at": now,
        }
        feed = doc.setdefault("feed", [])
        feed.insert(0, {
            "profile": prof,
            "state": state,
            "activity": activity[:200],
            "tool_name": tool_name,
            "room_id": room_id,
            "ts": now,
        })
        doc["feed"] = feed[:40]
        doc["updated_at"] = now
        try:
            fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".wall_", suffix=".tmp")
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(doc, f)
            os.replace(tmp, path)
        except Exception:
            pass
        return doc


def snapshot() -> dict[str, Any]:
    path = _shared_state_path()
    with _LOCK:
        return _read_unlocked(path)
