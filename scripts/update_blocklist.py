#!/usr/bin/env python3
"""
update_blocklist.py — Actualiza blocklist.txt con entradas de fuentes públicas.

Fuentes:
  - uBlock Origin filters (YouTube-específico)
  - AdGuard Mobile Ads & Tracking
  - EasyPrivacy (dominios de tracking)
  - HaGeZi Multi LIGHT — bloqueo general de anuncios/rastreadores (cualquier sitio,
    no solo YouTube/Google; es la lista que convierte el bloqueo en la defensa
    principal del navegador en vez de un extra solo para YouTube)
  - HaGeZi Pop-Up Ads — redes de pop-ups/redirecciones (el tipo de anuncio que
    saca de la app en sitios como Cuevana)
  - Lista manual en blocklist.txt (preservada siempre)

Corre semanalmente vía GitHub Actions.
"""

import urllib.request
import re
import sys
import os
import tempfile
from datetime import datetime, timezone

# ── Fuentes públicas ──────────────────────────────────────────────────────────
SOURCES = [
    {
        "name": "uBlock Origin — filters (YouTube/Google Ads)",
        "url": "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "keywords": ["youtube", "google", "googlevideo", "doubleclick", "googleapis",
                     "adsystem", "adservice", "imasdk", "pagead", "ima.", "adnxs",
                     "taboola", "outbrain", "scorecardresearch", "googlesyndication"],
    },
    {
        "name": "uBlock Origin — badware risks",
        "url": "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "keywords": ["google", "doubleclick", "youtube", "ad", "track"],
    },
    {
        "name": "AdGuard Mobile Ads",
        "url": "https://filters.adthing.net/filters/2.txt",
        "keywords": ["google", "youtube", "doubleclick", "ad", "track", "analytics"],
    },
    {
        "name": "EasyPrivacy",
        "url": "https://easylist.to/easylist/easyprivacy.txt",
        "keywords": ["google-analytics", "doubleclick", "googlesyndication",
                     "googletagmanager", "scorecardresearch", "quantserve"],
    },
    # Sin filtro de palabras clave — estas dos ya vienen curadas para anuncios y
    # rastreadores en general, no solo Google/YouTube. Son las que hacen que el
    # bloqueo funcione en cualquier sitio (Cuevana, Brave Search, lo que sea),
    # no solo dentro de los modos YouTube.
    {
        "name": "HaGeZi Multi LIGHT (anuncios/rastreadores general)",
        "url": "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
        "keywords": None,
    },
    {
        "name": "HaGeZi Pop-Up Ads",
        "url": "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/popupads.txt",
        "keywords": None,
    },
]

# ── Dominios que NUNCA se bloquean (falsos positivos) ─────────────────────────
WHITELIST = {
    "youtube.com", "www.youtube.com", "youtu.be",
    "googleapis.com",          # genérico — solo bloqueamos subdominios específicos
    "google.com", "www.google.com",
    "gstatic.com", "ytimg.com", "yt3.ggpht.com",
    "googlevideo.com",         # streams de video legítimos
    "accounts.google.com",
}

# ── Subdominios de googleapis que sí se bloquean ─────────────────────────────
GOOGLEAPIS_BLOCK = {"imasdk", "ima", "pagead"}


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "BraveTVBlocklistUpdater/1.0"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8", errors="ignore")


def extract_hosts(content: str, keywords: list) -> set:
    """Extrae dominios de reglas ABP/uBlock: ||domain^ y /etc/hosts."""
    hosts = set()
    for line in content.splitlines():
        line = line.strip()
        if not line or line.startswith(("!", "#", "@@", "/")):
            continue
        # ABP/uBlock host rule: ||example.com^
        m = re.match(r"^\|\|([a-zA-Z0-9.\-]+)\^", line)
        if m:
            host = m.group(1).lower()
            if _relevant(host, keywords) and _safe(host):
                hosts.add(host)
            continue
        # /etc/hosts format: 0.0.0.0 example.com
        m = re.match(r"^(?:0\.0\.0\.0|127\.0\.0\.1)\s+([a-zA-Z0-9.\-]+)", line)
        if m:
            host = m.group(1).lower()
            if _relevant(host, keywords) and _safe(host):
                hosts.add(host)
    return hosts


def extract_host_paths(content: str, keywords: list) -> set:
    """Keep simple domain-anchored path rules without resource/domain options.

    They are more precise than converting ||host/path^ to a host-wide block. Rules
    with wildcards, regexes, exceptions, or $options are intentionally left out.
    """
    paths = set()
    rule_re = re.compile(
        r"^\|\|([a-zA-Z0-9.\-]+)/([a-zA-Z0-9._~!$&'()+,;=:@%/-]+)\^$"
    )
    for raw in content.splitlines():
        line = raw.strip()
        match = rule_re.match(line)
        if not match:
            continue
        host, path = match.group(1).lower(), "/" + match.group(2)
        if _relevant(host, keywords) and _safe(host):
            paths.add(host + path)
    return paths


def _relevant(host: str, keywords) -> bool:
    if not keywords:   # source already curated for ads/trackers — nothing to filter by
        return True
    return any(kw in host for kw in keywords)


def _safe(host: str) -> bool:
    if host in WHITELIST:
        return False
    if host == "googleapis.com":
        return False
    if host.endswith(".googleapis.com"):
        sub = host.split(".googleapis.com")[0].split(".")[-1]
        return sub in GOOGLEAPIS_BLOCK
    # Skip bare TLDs or very short hosts
    if "." not in host or len(host) < 5:
        return False
    return True


def read_manual_entries(path: str):
    """Lee las entradas manuales (con # Manual o al inicio del archivo hasta el primer bloque auto)."""
    hosts, patterns, host_paths, header_lines = set(), set(), set(), []
    in_auto = False
    with open(path) as f:
        for line in f:
            stripped = line.rstrip()
            if "AUTO-GENERATED" in stripped:
                in_auto = True
                break
            header_lines.append(stripped)
            if stripped.startswith("HOST:"):
                hosts.add(stripped[5:].strip())
            elif stripped.startswith("PATTERN:"):
                patterns.add(stripped[8:].strip())
            elif stripped.startswith("HOSTPATH:"):
                host_paths.add(stripped[9:].strip())
    return hosts, patterns, host_paths, header_lines


def write_blocklist(path: str, manual_hosts: set, manual_patterns: set,
                    manual_host_paths: set, header_lines: list,
                    auto_hosts: set, auto_host_paths: set):
    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
    new_hosts = sorted(auto_hosts - manual_hosts)
    new_host_paths = sorted(auto_host_paths - manual_host_paths)
    directory = os.path.dirname(os.path.abspath(path))
    fd, temp_path = tempfile.mkstemp(prefix=".blocklist-", suffix=".tmp", dir=directory)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            # Cabecera manual preservada
            for line in header_lines:
                f.write(line + "\n")

            # Separador bloque automático
            f.write("\n")
            f.write("# ════════════════════════════════════════════════════════════\n")
            f.write(f"# AUTO-GENERATED — {today} — no editar esta sección manualmente\n")
            f.write("# Fuentes: uBlock Origin, AdGuard Mobile Ads, EasyPrivacy, HaGeZi Light, HaGeZi Pop-Up Ads\n")
            f.write("# ════════════════════════════════════════════════════════════\n")

            # Hosts automáticos (excluye los que ya están en la sección manual)
            if new_hosts:
                f.write(f"\n# {len(new_hosts)} dominios de fuentes públicas\n")
                for h in new_hosts:
                    f.write(f"HOST:{h}\n")
            if new_host_paths:
                f.write(f"\n# {len(new_host_paths)} rutas específicas de anuncios/rastreadores\n")
                for rule in new_host_paths:
                    f.write(f"HOSTPATH:{rule}\n")
            f.flush()
            os.fsync(f.fileno())
        # No deja blocklist.txt truncada si el proceso falla mientras escribe.
        os.replace(temp_path, path)
    except Exception:
        try:
            os.unlink(temp_path)
        except OSError:
            pass
        raise

    print(f"✓ blocklist.txt actualizado — {len(manual_hosts)} manuales + {len(new_hosts)} automáticos")


def main():
    path = "blocklist.txt"
    manual_hosts, manual_patterns, manual_host_paths, header_lines = read_manual_entries(path)
    print(f"  Entradas manuales: {len(manual_hosts)} hosts, {len(manual_patterns)} patrones")

    auto_hosts = set()
    auto_host_paths = set()
    successful_sources = set()
    for source in SOURCES:
        try:
            print(f"  Descargando: {source['name']} …")
            content = fetch(source["url"])
            found = extract_hosts(content, source["keywords"])
            found_paths = extract_host_paths(content, source["keywords"])
            print(f"    → {len(found)} dominios relevantes")
            print(f"    → {len(found_paths)} rutas específicas relevantes")
            auto_hosts.update(found)
            auto_host_paths.update(found_paths)
            successful_sources.add(source["name"])
        except Exception as e:
            print(f"    ✗ Error: {e}", file=sys.stderr)

    print(f"  Total automáticos únicos: {len(auto_hosts)}")
    broad_sources = {
        "HaGeZi Multi LIGHT (anuncios/rastreadores general)",
        "HaGeZi Pop-Up Ads",
    }
    if not (successful_sources & broad_sources) or len(auto_hosts) < 10000:
        print("✗ No se reemplaza blocklist.txt: faltaron listas generales o el resultado "
              "es demasiado pequeño; se conserva la última lista completa.", file=sys.stderr)
        raise SystemExit(1)
    write_blocklist(path, manual_hosts, manual_patterns, manual_host_paths,
                    header_lines, auto_hosts, auto_host_paths)


if __name__ == "__main__":
    main()
