import base64
import csv
import json
import sys

API_URLS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]
DDNS_SUFFIX = ".opengw.net"
# Two files, because two kinds of app read this repository:
#  - servers.json    : short list for the old app versions (they rebuild the whole screen after every ping)
#  - servers_v2.json : the full list for the versions with the country screen
LEGACY_FILE = "servers.json"
OUTPUT_FILE = "servers_v2.json"
LEGACY_SIZE = 60

# The numbers VPN Gate publishes (speed, ping, sessions) are measured from Japan, so this script
# does not try to find the "best" server. It keeps every server that can work at all; the app groups
# them by country, measures the real ping from the phone, and shows a quality label.
POOL_SIZE = 400           # upper bound for the number of servers written to servers.json
MIN_SPEED_MBPS = 10       # below this a server is not worth listing
MIN_UPTIME_HOURS = 1      # very young servers often disappear before the next refresh
SPEED_CAP_MBPS = 300      # only used to order the file: a measurement above this tells nothing more
LOAD_SESSIONS = 60        # only used to order the file: every LOAD_SESSIONS sessions halve the rank


def fetch_csv_text():
    import requests

    last_error = None
    for url in API_URLS:
        try:
            response = requests.get(url, timeout=60)
            response.raise_for_status()
            return response.text
        except Exception as e:  # noqa: BLE001
            last_error = e
            print(f"Fetch failed for {url}: {e}")
    raise RuntimeError(f"Could not fetch VPN Gate list: {last_error}")


def ports_from_config(config_b64):
    """Read the OpenVPN config embedded in the CSV and return (tcp_port, udp_port); None when missing.

    On VPN Gate the TCP port advertised for a server is also the port to use for MS-SSTP and for the
    SoftEther protocol (443 means no ':port' suffix on the website).
    """
    try:
        text = base64.b64decode(config_b64).decode("utf-8", errors="ignore")
    except Exception:  # noqa: BLE001
        return None, None

    proto = None
    entries = []  # (protocol or None, port)
    for raw in text.splitlines():
        parts = raw.strip().split()
        if not parts or parts[0].startswith(("#", ";")):
            continue
        key = parts[0].lower()
        if key == "proto" and len(parts) > 1:
            proto = parts[1].lower()
        elif key == "remote" and len(parts) >= 3 and parts[2].isdigit():
            remote_proto = parts[3].lower() if len(parts) >= 4 else None
            entries.append((remote_proto, int(parts[2])))

    tcp_port = None
    udp_port = None
    for remote_proto, port in entries:
        effective = remote_proto or proto
        if not effective:
            continue
        if effective.startswith("tcp") and tcp_port is None:
            tcp_port = port
        elif effective.startswith("udp") and udp_port is None:
            udp_port = port

    return tcp_port, udp_port


def to_int(value, default):
    return int(value) if value.isdigit() else default


def to_float(value):
    try:
        return float(value) if value else 0.0
    except ValueError:
        return 0.0


def quality(server):
    """Rough health of a server. The speed counts only up to a cap and a loaded server loses points."""
    capped = min(server["speed_mbps"], SPEED_CAP_MBPS)
    load = 1 + server["sessions"] / LOAD_SESSIONS
    return capped / load


def select_pool(servers):
    """Every usable server, best-looking first, without duplicates, up to POOL_SIZE."""
    seen = set()
    pool = []

    for server in sorted(servers, key=quality, reverse=True):
        if server["host"] in seen:
            continue
        seen.add(server["host"])
        pool.append(server)

        if len(pool) >= POOL_SIZE:
            break

    return pool


def parse_servers(csv_text):
    lines = csv_text.replace("\r", "").split("\n")
    rows_total = 0
    no_tcp_port = 0
    too_slow = 0
    too_young = 0
    servers = []

    reader = csv.reader(lines[2:])
    for row in reader:
        if len(row) < 15:
            continue
        host_name = row[0].strip()
        if not host_name or host_name.startswith(("#", "*")):
            continue
        rows_total += 1

        port, udp_port = ports_from_config(row[14])
        if port is None:
            no_tcp_port += 1
            continue  # cannot know the TCP port (used by SSTP and SoftEther) -> skip this server

        speed_mbps = round(to_float(row[4]) / 1_000_000, 2)
        if speed_mbps < MIN_SPEED_MBPS:
            too_slow += 1
            continue

        uptime_hours = round(to_float(row[8]) / 3_600_000, 1)
        if uptime_hours < MIN_UPTIME_HOURS:
            too_young += 1
            continue

        servers.append({
            "host_name": host_name,
            "host": host_name + DDNS_SUFFIX,
            "ip": row[1].strip(),
            "port": port,
            "udp_port": udp_port or 0,
            "country": row[6].strip(),
            "country_name": row[5].strip(),
            "speed_mbps": speed_mbps,   # measured from Japan: informational only
            "ping": to_int(row[3], 999),  # measured from Japan: informational only
            "sessions": to_int(row[7], 999),
            "score": to_int(row[2], 0),
            "uptime_hours": uptime_hours,
            "operator": row[12].strip() if len(row) > 12 else "",
        })

    pool = select_pool(servers)

    per_country = {}
    for s in pool:
        per_country[s["country"]] = per_country.get(s["country"], 0) + 1

    print(
        f"Rows parsed: {rows_total}; dropped: {no_tcp_port} without TCP port, {too_slow} slower than "
        f"{MIN_SPEED_MBPS} Mbps, {too_young} younger than {MIN_UPTIME_HOURS} h; "
        f"pool: {len(pool)} servers from {len(per_country)} countries"
    )
    print("Per country: " + ", ".join(f"{c} {n}" for c, n in sorted(per_country.items(), key=lambda x: -x[1])))
    return pool


def parse_servers(csv_text):
    lines = csv_text.replace("\r", "").split("\n")
    rows_total = 0
    no_tcp_port = 0
    too_slow = 0
    too_young = 0
    servers = []

    reader = csv.reader(lines[2:])
    for row in reader:
        if len(row) < 15:
            continue
        host_name = row[0].strip()
        if not host_name or host_name.startswith(("#", "*")):
            continue
        rows_total += 1

        port, udp_port = ports_from_config(row[14])
        if port is None:
            no_tcp_port += 1
            continue  # cannot know the TCP port (used by SSTP and SoftEther) -> skip this server

        speed_mbps = round(to_float(row[4]) / 1_000_000, 2)
        if speed_mbps < MIN_SPEED_MBPS:
            too_slow += 1
            continue

        uptime_hours = round(to_float(row[8]) / 3_600_000, 1)
        if uptime_hours < MIN_UPTIME_HOURS:
            too_young += 1
            continue

        servers.append({
            "host_name": host_name,
            "host": host_name + DDNS_SUFFIX,
            "ip": row[1].strip(),
            "port": port,
            "udp_port": udp_port or 0,
            "country": row[6].strip(),
            "country_name": row[5].strip(),
            "speed_mbps": speed_mbps,   # measured from Japan: informational only
            "ping": to_int(row[3], 999),  # measured from Japan: informational only
            "sessions": to_int(row[7], 999),
            "score": to_int(row[2], 0),
            "uptime_hours": uptime_hours,
            "operator": row[12].strip() if len(row) > 12 else "",
        })

    pool = select_pool(servers)

    countries = len({s["country"] for s in pool})
    print(
        f"Rows parsed: {rows_total}, candidates (TCP port, speed >= {MIN_SPEED_MBPS} Mbps, "
        f"uptime >= {MIN_UPTIME_HOURS} h): {len(servers)}, pool: {len(pool)} servers from {countries} countries"
    )
    return pool


def legacy_list(servers):
    """The old apps get a short list of the better-looking servers."""
    better = [s for s in servers if s["speed_mbps"] >= 50 and s["uptime_hours"] >= 3]
    return (better or servers)[:LEGACY_SIZE]


def stage_in_git(path):
    """The workflow only adds servers.json by itself: stage the new file so the same commit carries it."""
    import subprocess

    try:
        subprocess.run(["git", "add", path], check=True, capture_output=True)
        print(f"Staged {path} for the commit")
    except Exception as e:  # noqa: BLE001
        print(f"Could not stage {path} (fine when not run by the workflow): {e}")


def write_json(path, servers):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(servers, f, ensure_ascii=False, indent=2)
    print(f"Saved {len(servers)} servers to {path}")


def main():
    servers = parse_servers(fetch_csv_text())
    if not servers:
        print("No usable servers found; keeping the old files")
        sys.exit(1)

    for s in servers[:5]:
        print(f"  {s['host']}:{s['port']}  {s['country']}  {s['speed_mbps']} Mbps  sessions {s['sessions']}")

    write_json(OUTPUT_FILE, servers)
    write_json(LEGACY_FILE, legacy_list(servers))
    stage_in_git(OUTPUT_FILE)


if __name__ == "__main__":
    main()
