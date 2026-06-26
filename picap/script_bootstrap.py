"""Re-run CLI scripts with the project virtualenv when system Python is used."""

from __future__ import annotations

import os
import sys
from pathlib import Path


def project_root() -> Path:
    return Path(__file__).resolve().parents[1]


def venv_python() -> Path:
    return project_root() / ".venv" / "bin" / "python"


def reexec_in_project_venv() -> None:
    """Replace the current process with .venv/bin/python if it exists."""
    interpreter = venv_python()
    if not interpreter.is_file():
        return
    try:
        if Path(sys.executable).resolve() == interpreter.resolve():
            return
    except OSError:
        return
    os.execv(str(interpreter), [str(interpreter), *sys.argv])


def ensure_import(
    module_name: str,
    *,
    pip_hint: str | None = None,
) -> None:
    try:
        __import__(module_name)
    except ModuleNotFoundError as exc:
        root = project_root()
        venv = venv_python()
        lines = [
            f"Missing Python module: {module_name}",
            "",
            "PiCap scripts must use the project virtualenv:",
            f"  cd {root}",
            f"  source .venv/bin/activate",
            "  pip install -r requirements.txt",
            "",
            "Or run directly:",
            f"  {venv} scripts/<script>.py",
        ]
        if pip_hint:
            lines.extend(["", pip_hint])
        raise SystemExit("\n".join(lines)) from exc
