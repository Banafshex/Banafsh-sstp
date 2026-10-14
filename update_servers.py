import base64
import csv
import json
import sys

API_URLS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]
DDNS_SUFFIX = ".opengw.net"
TOP_N = 20
OUTPUT_FILE = "servers.json"


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

        ip = row[1].strip()
        score = int(row[2]) if row[2].isdigit() else 0
        ping = int(row[3]) if row[3].isdigit() else 999
        try:
            speed = float(row[4]) if row[4] else 0.0
        except ValueError:
            speed = 0.0
        sessions = int(row[7]) if row[7].isdigit() else 999

        servers.append({
            "host_name": host_name,
            "host": host_name + DDNS_SUFFIX,
            "ip": ip,
            "port": port,
            "country": row[6].strip(),
            "country_name": row[5].strip(),
            "speed_mbps": round(speed / 1_000_000, 2),
            "ping": ping,
            "sessions": sessions,
            "score": score,
        })

    servers.sort(
        key=lambda s: (s["score"], s["speed_mbps"], -s["sessions"], -s["ping"]),
        reverse=True,
    )
    print(f"Rows parsed: {rows_total}, with a TCP port: {len(servers)}")
    return servers[:TOP_N]


def main():
    servers = parse_servers(fetch_csv_text())
    if not servers:
        print("No usable servers found; keeping the old servers.json")
        sys.exit(1)

    for s in servers[:5]:
        print(f"  {s['host']}:{s['port']}  {s['speed_mbps']} Mbps  ping {s['ping']}")

    with open(OUTPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(servers, f, ensure_ascii=False, indent=2)
    print(f"Saved {len(servers)} servers to {OUTPUT_FILE}")


if __name__ == "__main__":
    main()
