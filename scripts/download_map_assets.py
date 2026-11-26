#!/usr/bin/env python3
"""Download the exact extensionless CES map tiles shown in the supplied DevTools screenshots."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener
from urllib.robotparser import RobotFileParser


ASSET_HOST = "media.bookofmormon.online"
ASSET_ROOT = f"https://{ASSET_HOST}/map/ces"
USER_AGENT = "Wargames-CES-tile-downloader/1.2"
REQUEST_DELAY_SECONDS = 0.5
MAX_FILE_BYTES = 30 * 1024 * 1024
MAX_TOTAL_BYTES = 200 * 1024 * 1024
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"

# Each mapping is zoom level -> column (x) -> visible row numbers (y).
# These paths are transcribed from the user's DevTools screenshots.
TILE_ROWS = {
    6: {
        14: range(28, 30),
        15: range(28, 30),
    },
    7: {
        29: range(56, 59),
        30: range(56, 59),
        31: range(56, 59),
    },
    8: {
        x: range(113, 118) for x in range(59, 64)
    },
    9: {
        120: range(227, 235),
        121: range(227, 235),
        122: range(227, 235),
        123: range(227, 235),
        124: range(227, 235),
        125: range(227, 234),
        126: range(227, 234),
        127: range(228, 233),
    },
    10: {
        244: range(458, 462),
        245: range(458, 462),
        246: range(458, 462),
        247: range(458, 465),
        248: range(461, 465),
        249: range(461, 465),
        250: range(461, 465),
    },
}


def tile_urls() -> list[tuple[int, int, int, str]]:
    return [
        (zoom, x, y, f"{ASSET_ROOT}/{zoom}/{x}/{y}")
        for zoom, columns in TILE_ROWS.items()
        for x, rows in columns.items()
        for y in rows
    ]


class MediaHostRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        parsed = urlparse(new_url)
        if parsed.scheme != "https" or parsed.hostname != ASSET_HOST:
            raise HTTPError(new_url, code, "Redirect outside the CES media host blocked", headers, response)
        return super().redirect_request(request, response, code, message, headers, new_url)


def robots_policy(opener) -> RobotFileParser:
    url = f"https://{ASSET_HOST}/robots.txt"
    robots = RobotFileParser(url)
    request = Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with opener.open(request, timeout=20) as response:
            body = response.read(1024 * 1024).decode("utf-8", errors="replace")
            robots.parse(body.splitlines())
    except HTTPError as error:
        if error.code == 404:
            robots.parse(["User-agent: *", "Allow: /"])
        else:
            raise RuntimeError(f"Could not read robots.txt: {error}") from error
    return robots


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("frontend/public/map-assets/ces"),
        help="public asset folder (defaults to the frontend's CES tile directory)",
    )
    parser.add_argument("--delay", type=float, default=REQUEST_DELAY_SECONDS, help="seconds between requests")
    args = parser.parse_args()

    opener = build_opener(MediaHostRedirectHandler())
    try:
        robots = robots_policy(opener)
    except (HTTPError, URLError, TimeoutError, RuntimeError) as error:
        parser.error(str(error))

    tiles = tile_urls()
    args.out.mkdir(parents=True, exist_ok=True)
    saved = 0
    failed = 0
    total_bytes = 0

    for index, (zoom, x, y, url) in enumerate(tiles):
        if not robots.can_fetch(USER_AGENT, url):
            print(f"Skipping disallowed by robots.txt: z={zoom} x={x} y={y}")
            failed += 1
            continue
        if index:
            time.sleep(max(0, args.delay))
        request = Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with opener.open(request, timeout=30) as response:
                content = response.read(MAX_FILE_BYTES + 1)
                content_type = response.headers.get_content_type()
        except (HTTPError, URLError, TimeoutError) as error:
            print(f"Failed z={zoom} x={x} y={y}: {error}")
            failed += 1
            continue

        if len(content) > MAX_FILE_BYTES:
            print(f"Skipped oversized tile z={zoom} x={x} y={y}")
            failed += 1
            continue
        if not content.startswith(PNG_SIGNATURE):
            print(f"Skipped non-PNG response z={zoom} x={x} y={y} ({content_type})")
            failed += 1
            continue
        if total_bytes + len(content) > MAX_TOTAL_BYTES:
            print("Reached 200 MiB total download limit; stopping.")
            failed += len(tiles) - index
            break

        destination = args.out / str(zoom) / str(x) / f"{y}.png"
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        saved += 1
        total_bytes += len(content)
        print(f"Saved z={zoom} x={x} y={y} ({content_type}, {len(content):,} bytes) -> {destination}")

    manifest_tiles = []
    for image_path in sorted(args.out.glob("*/*/*.png")):
        try:
            zoom, x, y = (int(part) for part in image_path.relative_to(args.out).with_suffix("").parts)
        except ValueError:
            continue
        manifest_tiles.append({
            "z": zoom,
            "x": x,
            "y": y,
            "src": f"/map-assets/ces/{zoom}/{x}/{y}.png",
        })
    manifest_path = args.out / "manifest.json"
    manifest_path.write_text(
        json.dumps({"tileSize": 256, "baseZoom": 8, "tiles": manifest_tiles}, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"Wrote tile manifest: {manifest_path}")
    print(f"Finished: {saved}/{len(tiles)} requested tiles saved; {failed} failed or skipped; {total_bytes:,} bytes downloaded.")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
