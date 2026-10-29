"""hermes-mobile-wall — Live Bot Activity Wall hooks for Hermes OS Android."""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

_DIR = str(Path(__file__).resolve().parent)
if _DIR not in sys.path:
    sys.path.insert(0, _DIR)

import wall_store  # noqa: E402


def _resolve_profile(payload: Any) -> str:
    if isinstance(payload, dict):
        for k in ("profile", "member_id", "agent_profile"):
            v = str(payload.get(k) or "").strip()
            if v:
                return v
    return (os.environ.get("HERMES_PROFILE") or "default").strip() or "default"


def _on_session_start(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    sid = payload.get("session_id") if isinstance(payload, dict) else None
    wall_store.record_bot_state(prof, "THINKING", "Sesión activa iniciada", session_id=sid)


def _on_session_end(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    sid = payload.get("session_id") if isinstance(payload, dict) else None
    wall_store.record_bot_state(prof, "IDLE", "Turno completado", session_id=sid)


def _pre_tool_call(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    tool = (payload.get("tool_name") or payload.get("name") or "tool") if isinstance(payload, dict) else "tool"
    wall_store.record_bot_state(prof, "RUNNING_TOOL", f"Ejecutando {tool}", tool_name=str(tool))


def _post_tool_call(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    tool = (payload.get("tool_name") or payload.get("name") or "tool") if isinstance(payload, dict) else "tool"
    wall_store.record_bot_state(prof, "THINKING", f"Analizando salida de {tool}", tool_name=str(tool))


def _pre_approval(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    tool = (payload.get("tool_name") or "acción") if isinstance(payload, dict) else "acción"
    wall_store.record_bot_state(prof, "WAITING_APPROVAL", f"Esperando aprobación: {tool}", tool_name=str(tool))


def _post_approval(payload: Any = None, **_kw: Any) -> None:
    prof = _resolve_profile(payload)
    wall_store.record_bot_state(prof, "THINKING", "Aprobación procesada")


def _on_room_member_activity(**kwargs: Any) -> None:
    member_id = str(kwargs.get("member_id") or "default")
    room_id = str(kwargs.get("room_id") or "")
    kind = str(kwargs.get("kind") or "")
    payload = kwargs.get("payload") if isinstance(kwargs.get("payload"), dict) else {}
    tool_name = str(payload.get("name") or payload.get("tool_name") or "") or None
    state_map = {
        "tool.started": ("RUNNING_TOOL", f"Sala {room_id}: usando {tool_name or 'herramienta'}"),
        "tool.completed": ("THINKING", f"Sala {room_id}: terminó {tool_name or 'herramienta'}"),
        "reasoning.delta": ("THINKING", f"Sala {room_id}: razonando…"),
        "message.delta": ("SPEAKING", f"Sala {room_id}: respondiendo al equipo…"),
        "request.opened": ("WAITING_APPROVAL", f"Sala {room_id}: solicita permiso"),
        "turn.error": ("ERROR", f"Sala {room_id}: error en turno"),
    }
    state, summary = state_map.get(kind, ("THINKING", f"Actividad en sala ({kind})"))
    wall_store.record_bot_state(member_id, state, summary, tool_name=tool_name, room_id=room_id or None)


def register(ctx: Any) -> None:
    for hook_name, fn in (
        ("on_session_start", _on_session_start),
        ("on_session_end", _on_session_end),
        ("pre_tool_call", _pre_tool_call),
        ("post_tool_call", _post_tool_call),
        ("pre_approval_request", _pre_approval),
        ("post_approval_response", _post_approval),
        ("on_room_member_activity", _on_room_member_activity),
    ):
        try:
            ctx.register_hook(hook_name, fn)
        except Exception:
            pass
