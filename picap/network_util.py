"""Small helpers for reporting Pi network addresses to clients."""

from __future__ import annotations

import socket
import subprocess


def get_lan_ip() -> str | None:
    """Best-effort IPv4 address reachable from the local LAN."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            ip = sock.getsockname()[0]
            if ip and not ip.startswith("127."):
                return ip
    except OSError:
        pass

    try:
        result = subprocess.run(
            ["hostname", "-I"],
            capture_output=True,
            text=True,
            timeout=2,
            check=False,
        )
        for part in result.stdout.strip().split():
            if "." in part and not part.startswith("127."):
                return part
    except (OSError, subprocess.SubprocessError):
        pass

    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127."):
                return ip
    except OSError:
        pass

    return None
