# PiCap

PiCap is a Raspberry Pi application that captures photos from a connected camera, reads numeric values from configured screen regions using OCR, stores readings in SQLite, and exposes data and configuration over a Bluetooth Low Energy (BLE) GATT API for a phone app.

## GitHub

This project uses Git on the `main` branch.

### First-time setup

1. Create an empty repository on GitHub (no README or `.gitignore`): [github.com/new](https://github.com/new)
2. Link and push from this machine:

```powershell
# Windows
.\scripts\github-setup.ps1 -GitHubUsername YOUR_GITHUB_USERNAME
```

```bash
# Linux / macOS / Raspberry Pi
chmod +x scripts/github-setup.sh
./scripts/github-setup.sh YOUR_GITHUB_USERNAME
```

Or manually:

```bash
git remote add origin git@github.com:YOUR_GITHUB_USERNAME/PiCap.git
git push -u origin main
```

Use HTTPS if you do not have SSH keys configured:

```bash
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/PiCap.git
git push -u origin main
```

### Day to day

```bash
git pull
# make changes
git add -A
git commit -m "Describe your change"
git push
```

On the Raspberry Pi, clone once then pull updates:

```bash
git clone https://github.com/YOUR_GITHUB_USERNAME/PiCap.git
cd PiCap
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
```

## Features

- Camera capture via Pi Camera Module (`picamera2`) or USB webcam (`opencv`)
- Region-based OCR with Tesseract (digit-focused)
- Persistent SQLite storage for capture history
- BLE GATT API for mobile access (config, capture trigger, latest/history readings, status)
- HTTP REST API over WiFi (fallback when BLE advertising fails on the Pi)

## Requirements

### Raspberry Pi OS

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip tesseract-ocr libtesseract-dev bluez bluez-tools
```

For Pi Camera Module:

```bash
sudo apt install -y python3-picamera2
```

### Python environment

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
python scripts/patch-bless.py
cp config.example.yaml config.yaml
```

### Raspberry Pi Bluetooth setup

Before first run (and after reboot if BLE fails), prepare Bluetooth:

```bash
chmod +x scripts/setup-pi-bluetooth.sh
./scripts/setup-pi-bluetooth.sh
```

This script unblocks Bluetooth rfkill, enables BlueZ experimental mode, turns on LE advertising via `btmgmt`, and patches `bless` for Pi/BlueZ compatibility.

### Run as a systemd service (optional)

Edit `scripts/picap.service` if your user or project path differs, then:

```bash
sudo cp scripts/picap.service /etc/systemd/system/picap.service
sudo systemctl daemon-reload
sudo systemctl enable --now picap
journalctl -u picap -f
```

## Usage

Run HTTP + BLE (HTTP keeps running if BLE fails):

```bash
python -m picap serve --config config.yaml
```

HTTP only (recommended if BLE advertising is unreliable):

```bash
python -m picap serve-http --config config.yaml
```

One-off local capture (prints JSON, does not save unless `--save`):

```bash
python -m picap capture --config config.yaml
python -m picap capture --config config.yaml --save
```

Query stored data locally:

```bash
python -m picap latest --config config.yaml
python -m picap history --config config.yaml --limit 20
```

## HTTP REST API (WiFi)

Enabled by default on port `8080`. Phone and PC clients on the same network can use:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/status` | Device status (`ble_active`, `http_active`, etc.) |
| `GET` | `/api/config` | Full configuration JSON |
| `PATCH` / `PUT` | `/api/config` | Merge config patch (same format as BLE) |
| `GET` | `/api/latest` | Latest reading |
| `GET` | `/api/history?limit=20&offset=0` | Reading history |
| `POST` | `/api/capture` | Trigger capture + OCR |

Example status payload:

```json
{
  "ready": true,
  "http_active": true,
  "http_port": 8080,
  "http_url": "http://10.0.0.17:8080"
}
```

The Android app reads `http_url` over BLE after connecting so Preview and Regions work without typing the Pi IP.

Example (replace with your Pi IP):

```bash
curl http://192.168.1.50:8080/api/status
curl -X POST http://192.168.1.50:8080/api/capture
curl -X PATCH http://192.168.1.50:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{"ocr":{"min_confidence":70}}'
```

Optional API key: set `http.api_key` in `config.yaml` and send `X-API-Key: your-secret` from clients.

Disable HTTP or BLE in `config.yaml`:

```yaml
http:
  enabled: true
ble:
  enabled: true
```

## BLE GATT API

Advertised device name: `PiCap` (configurable via `ble.device_name`).

| Characteristic | UUID key in config | Access | Payload |
|---|---|---|---|
| Config | `char_config_uuid` | read / write | Full config as JSON (merge or replace) |
| Capture | `char_capture_uuid` | write / notify | Write `capture` or `{"action":"capture"}` |
| Latest | `char_latest_uuid` | read / notify | Most recent reading JSON |
| History | `char_history_uuid` | write then read | Write `{"limit":20,"offset":0}`, then read |
| Status | `char_status_uuid` | read / notify | Device status JSON |

### Configuration updates via API

Config writes **merge** into the existing YAML by default. Send only the sections you want to change:

```json
{
  "ocr": {
    "mode": "auto",
    "min_confidence": 70
  }
}
```

To replace entire sections instead of merging, include `"replace": true`:

```json
{
  "replace": true,
  "regions": [
    {"name": "slot_a", "x": 50, "y": 100, "width": 120, "height": 40}
  ],
  "ocr": {"mode": "regions"}
}
```

CLI equivalents:

```bash
python -m picap config --config config.yaml
python -m picap config-set patch.json --config config.yaml
```

### OCR modes

- **`auto`** (default): Scans the full image, detects all numeric values, and records their positions. No fixed coordinates required.
- **`regions`**: Uses manually defined pixel regions when layout is fixed.

Auto-detected readings are named `value_1`, `value_2`, etc. (sorted top-to-bottom, left-to-right) and include bounding box coordinates in each reading.

### Example latest reading

```json
{
  "id": 1,
  "captured_at": "2026-06-23T12:34:56+00:00",
  "image_path": "data/captures/capture_20260623_123456.jpg",
  "values": {
    "reading_1": "12.5",
    "reading_2": "98.2"
  },
  "readings": [
    {"name": "reading_1", "value": "12.5", "confidence": 92.0},
    {"name": "reading_2", "value": "98.2", "confidence": 88.0}
  ]
}
```

## Phone app integration

The Android client lives in [`android/`](android/). See [`android/README.md`](android/README.md) for build and run instructions.

On Android/iOS, connect to the `PiCap` BLE peripheral and interact with the GATT characteristics below. The Android app implements this flow out of the box.

1. Scan for `PiCap`
2. Connect and discover the custom service UUID
3. Read `status` and `latest`
4. Write to `capture` and subscribe to notifications
5. Read or write `config` to adjust OCR regions remotely

## Project layout

```
picap/             # Raspberry Pi Python service
android/           # Kotlin Android phone app
  app/src/main/java/com/picap/mobile/
    ble/           # BLE client
    data/          # JSON models
    ui/            # Jetpack Compose UI
config.example.yaml
requirements.txt
```

## Tuning OCR

In **auto** mode (default), adjust settings via the Android app Settings tab or the BLE config API:

- `min_confidence` — raise if you get false positives
- `min_digits` — minimum digits required in a detection
- `upscale_factor` — increase for small text
- `auto_psm` — Tesseract page segmentation mode (11 works well for sparse screen text)

For a fixed layout, set `ocr.mode` to `regions` and define pixel regions in config.

### OTW monitor dashboard

For a fixed monitor layout with Order Point / Current OTW times (`MM:SS`), use the example config and calibrate once:

```bash
cp config.otw-monitor.yaml.example config.yaml
python scripts/calibrate_regions.py --config config.yaml --capture
```

Draw tight boxes around only these two values:

| Region name | Location on screen |
|-------------|-------------------|
| `order_point_15min_avg` | Time under **15 Mins AVG** in the top-left **Order Point** box |
| `current_otw_15min_avg` | Small time under **15 Mins AVG** in the bottom **Current OTW** box |

Set `format: time` on each region so OCR reads `05:30` style values. Test without saving (use the project venv):

```bash
source .venv/bin/activate
python scripts/test_ocr_regions.py --config config.yaml --live --save-crops
```

Or without activating:

```bash
.venv/bin/python scripts/test_ocr_regions.py --config config.yaml --live --save-crops
```

Tune `upscale_factor` (try `3.0`) and `min_confidence` if reads are unreliable.

## Notes

- BLE payloads are JSON and sized for readings/metadata, not full images.
- Images are stored locally on the Pi at the path recorded in each reading.
- If BLE advertisement fails, use HTTP (`serve-http`) or rely on `serve` which starts HTTP first.
- Run `./scripts/setup-pi-bluetooth.sh` after reboot if BLE was soft-blocked.
