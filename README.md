# BOM Wargames Map

A first playable map prototype using Vue 3 and a Java Spring Boot API.

## Run locally

Start the Java API (requires Java 17+ and Maven):

```sh
cd backend
mvn spring-boot:run
```

The API stores data in a file-backed H2 database at `backend/data/wargames.mv.db`.
The database is created and seeded on first startup with original and
alternate-name Book of Mormon campaigns, plus Lord of the Rings map locations,
routes, and events. Place records include their map layer so the same campaign
can store separate artwork and tile-map coordinates. The Adventure campaign
(`book-of-mormon-adventure`) uses the original map coordinates with alternate
place names. Set
`DATABASE_URL`, `DATABASE_USERNAME`, or `DATABASE_PASSWORD` to configure the H2 connection.

In another terminal, start the Vue app (requires Node 20+):

```sh
cd frontend
npm install
npm run dev
```

Open the Vite URL printed in the terminal. The frontend proxies `/api` to the Java server on port 8080.

## Campaign API

All resources are JSON under `/api`. Campaigns have a `slug`, `name`, `setting`,
and optional `description` and `mapMode`. Places, routes, events, and armies
belong to a campaign and include its numeric `campaignId`. Collection endpoints
accept `?campaignId=<id>` to filter by campaign. Terrain regions are shared map
data, queried by `mapKey` and `mapLayer` instead.

| Resource | Collection endpoint | Fields |
| --- | --- | --- |
| Campaigns | `/api/campaigns` | `slug`, `name`, `setting`, `description`, `mapMode` |
| Places | `/api/places` | `campaignId`, `name`, `alternateName`, `kind`, `mapLayer`, `x`, `y`, `tier`, `description` |
| Routes | `/api/routes` | `campaignId`, `name`, `color`, `description`, ordered `points` (`x`, `y`) |
| Events | `/api/events` | `campaignId`, `eventNumber`, `title`, `description`, `placeName`, `dateLabel`, `x`, `y` |
| Armies | `/api/armies` | `campaignId`, `name`, `faction`, `side`, `x`, `y`, `unitCount`, `description` |
| Terrain regions | `/api/terrain-regions?mapKey=ces&mapLayer=tiles` | `mapKey`, `mapLayer`, `name`, `terrainType`, `brushSize`, ordered `points` (`x`, `y`) |

Each collection supports `GET` and `POST`; `/api/{resource}/{id}` supports
`GET`, `PUT`, and `DELETE`. Coordinates are map percentages from 0 to 100.
Terrain map keys are `ces` and `middle-earth`; Book of Mormon campaign variants
share the `ces` annotations. The illustrated and tile views have separate
coordinate sets so painted marks remain aligned with each display. Terrain types
are `mountain`, `river`, `forest`, and `other`. Terrain regions are descriptive
map data; movement and combat rules are not applied automatically.
Deleting a campaign requires deleting its related records first. For example:
The existing `/api/locations` endpoint remains available as a Book of Mormon
map-location view.

```sh
curl http://localhost:8080/api/campaigns
curl "http://localhost:8080/api/places?campaignId=1"
curl -X POST http://localhost:8080/api/armies \
  -H "Content-Type: application/json" \
  -d '{"campaignId":1,"name":"First Army","faction":"Nephites","side":"blue","x":48.3,"y":46.5,"unitCount":500}'
```

## Map controls

- Drag the map to pan; use the mouse wheel or zoom buttons to zoom.
- Select **Place army**, then click the map to place a piece.
- Drag a piece to move it. Select it and press Delete/Backspace to remove it.
- Choose a terrain type and optionally name it, select **Paint terrain**, then drag across the map. Select **Erase painted terrain** and click a stroke to remove it, or use its delete button in the sidebar. Press Escape to stop either terrain tool.
- Use the location selector to navigate to named places.
- Campaigns, places, routes, events, terrain regions, and army placements are loaded from the Java API. Terrain strokes and army moves/removals are saved to the database.
- Use **Map layers** to switch between the labeled reference map and the assembled CES tile mosaic. The mosaic is drawn on a high-resolution canvas from separate 256 × 256 PNG tiles; available zoom 9 and 10 tiles are overlaid on zoom 8 tiles for extra detail.

## Download the CES map tiles shown in DevTools

The downloader requests the exact extensionless `z/x/y` tile paths visible in the supplied screenshots under `https://media.bookofmormon.online/map/ces/`, saves PNG files under the frontend's public assets, and writes the manifest used by the canvas. It checks `robots.txt`, waits between requests, and applies per-file and total download limits.

```sh
python scripts/download_map_assets.py
```
