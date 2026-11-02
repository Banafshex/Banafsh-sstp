import base64
import csv
import json
import sys

API_URLS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]
DDNS_SUFFIX = ".opengw.net"
OUTPUT_FILE = "servers.json"

# The numbers VPN Gate publishes (speed, ping, sessions) are measured from Japan.
# They say little about the path from Iran, so this script does NOT try to find the
# "fastest" server. It builds a wide, varied pool of healthy candidates and the app
# measures the real ping from the phone and picks from them.
POOL_SIZE = 60            # servers written to servers.json
MAX_PER_BLOCK = 5         # at most this many from the same /24 network
MAX_PER_COUNTRY = 5       # first pass: spread over countries (60 / 5 = about 12 countries)
COUNTRY_CAPS = {}         # per-country override, for example {"JP": 10} if Japan keeps proving the best
MIN_SPEED_MBPS = 50       # below this a server is not worth testing
MIN_UPTIME_HOURS = 3      # very young servers often disappear before the next refresh
SPEED_CAP_MBPS = 300      # a measurement above this tells nothing more about a phone's speed
LOAD_SESSIONS = 60        # every LOAD_SESSIONS sessions halve the quality of a server


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


def tcp_port_from_config(config_b64):
    """Read the OpenVPN config embedded in the CSV and return its TCP port.

    On VPN Gate the TCP port advertised for a server is also the port
    to use for MS-SSTP (443 means no ':port' suffix on the website).
    """
    try:
        text = base64.b64decode(config_b64).decode("utf-8", errors="ignore")
    except Exception:  # noqa: BLE001
        return None

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

    for remote_proto, port in entries:
        effective = remote_proto or proto
        if effective and effective.startswith("tcp"):
            return port
    return None


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


def block_of(ip):
    return ".".join(ip.split(".")[:3])


def select_pool(servers):
    """Best servers first, but varied: capped per /24 network and, in a first pass, per country."""
    ranked = sorted(servers, key=quality, reverse=True)

    chosen = []
    taken = set()
    per_block = {}
    per_country = {}

    def take(server, use_country_cap):
        key = server["host"]
        block = block_of(server["ip"])
        country = server["country"]

        if key in taken or per_block.get(block, 0) >= MAX_PER_BLOCK:
            return
        if use_country_cap and per_country.get(country, 0) >= COUNTRY_CAPS.get(country, MAX_PER_COUNTRY):
            return

        chosen.append(server)
        taken.add(key)
        per_block[block] = per_block.get(block, 0) + 1
        per_country[country] = per_country.get(country, 0) + 1

    for server in ranked:
        if len(chosen) >= POOL_SIZE:
            break
        take(server, True)

    # Not enough countries to fill the pool: fill the rest by quality alone.
    for server in ranked:
        if len(chosen) >= POOL_SIZE:
            break
        take(server, False)

    chosen.sort(key=quality, reverse=True)
    return chosen


def parse_servers(csv_text):
    lines = csv_text.replace("\r", "").split("\n")
    rows_total = 0
    servers = []

    reader = csv.reader(lines[2:])
    for row in reader:
        if len(row) < 15:
            continue
        host_name = row[0].strip()
        if not host_name or host_name.startswith(("#", "*")):
            continue
        rows_total += 1

        port = tcp_port_from_config(row[14])
        if port is None:
            continue  # cannot know the SSTP port -> skip this server

        speed_mbps = round(to_float(row[4]) / 1_000_000, 2)
        if speed_mbps < MIN_SPEED_MBPS:
            continue

        uptime_hours = round(to_float(row[8]) / 3_600_000, 1)
        if uptime_hours < MIN_UPTIME_HOURS:
            continue

        servers.append({
            "host_name": host_name,
            "host": host_name + DDNS_SUFFIX,
            "ip": row[1].strip(),
            "port": port,
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


def main():
    servers = parse_servers(fetch_csv_text())
    if not servers:
        print("No usable servers found; keeping the old servers.json")
        sys.exit(1)

    for s in servers[:5]:
        print(f"  {s['host']}:{s['port']}  {s['country']}  {s['speed_mbps']} Mbps  sessions {s['sessions']}")

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(servers, f, ensure_ascii=False, indent=2)
    print(f"Saved {len(servers)} servers to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
