import csv
import json
import requests

API_URL = "http://www.vpngate.net/api/iphone/"


def main():
  try:
    response = requests.get(API_URL)
    response.raise_for_status()
    lines = response.text.replace("\r", "").split("\n")

    if len(lines) < 3:
      print("Error: Invalid data from VPN Gate")
      return

    # رد کردن خطوط هیدر و کامنت اولیه
    reader = csv.reader(lines[2:])

    servers = []
    for row in reader:
      if len(row) < 15:
        continue
      
      host_name = row[0].strip()
      # اگر ردیف خالی یا هیدر بود رد شو
      if not host_name or host_name.startswith("#"):
        continue

      ip = row[1].strip()
      score = int(row[2]) if row[2].isdigit() else 0
      ping = int(row[3]) if row[3].isdigit() else 999
      
      try:
        speed = float(row[4]) if row[4] else 0.0
      except ValueError:
        speed = 0.0

      country_long = row[5].strip()
      country_short = row[6].strip()
      num_sessions = int(row[7]) if row[7].isdigit() else 999

      servers.append({
          "host_name": host_name,
          "ip": ip,
          "country": country_short,
          "country_name": country_long,
          "speed_mbps": round(speed / 1_000_000, 2),
          "ping": ping,
          "sessions": num_sessions,
          "score": score,
      })

    # مرتب‌سازی بر اساس امتیاز کیفیت، سرعت بالا، جلسات کمتر و پینگ کمتر
    servers.sort(
        key=lambda x: (x["score"], x["speed_mbps"], -x["sessions"], -x["ping"]),
        reverse=True,
    )

    # انتخاب ۲۰ سرور برتر
    top_servers = servers[:20]

    with open("servers.json", "w", encoding="utf-8") as f:
      json.dump(top_servers, f, ensure_ascii=False, indent=4)

    print(f"Successfully saved {len(top_servers)} servers to servers.json")

  except Exception as e:
    print(f"Failed to fetch or parse VPN Gate data: {e}")


if __name__ == "__main__":
  main()
