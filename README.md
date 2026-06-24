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

## Requirements

### Raspberry Pi OS

```bash
sudo apt update
sudo apt install -y python3-venv python3-pip tesseract-ocr libtesseract-dev bluez
```

For Pi Camera Module:

```bash
sudo apt install -y python3-picamera2
```

Enable Bluetooth and ensure the Pi is discoverable:

```bash
sudo systemctl enable bluetooth
sudo systemctl start bluetooth
```

### Python environment

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp config.example.yaml config.yaml
```

Edit `config.yaml` to define OCR regions (`x`, `y`, `width`, `height`) that match the numbers on your target screen.

## Usage

Run the BLE service (primary mode for phone access):

```bash
python -m picap serve --config config.yaml
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

## Notes

- BLE payloads are JSON and sized for readings/metadata, not full images.
- Images are stored locally on the Pi at the path recorded in each reading.
- Run the service as root or configure BlueZ permissions if BLE advertisement fails.
