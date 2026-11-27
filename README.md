# Wargames Map

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

## Host a game for family and friends

`scripts/play.ps1` runs the whole game from this computer and shares it for free:

```powershell
.\scripts\play.ps1
```

It builds the Vue app into the Spring Boot jar (so the page and `/api` share one
address, which the seat cookies need), starts the server on port 8080, and opens
a free [Cloudflare quick tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/).
Share the printed `https://….trycloudflare.com` link; it changes on every run.
Saved games stay in `backend/data`, so a game can continue across runs. The
script offers to install `cloudflared` with winget if it is missing. Press
Ctrl+C to stop both the tunnel and the server. Stop any `mvn spring-boot:run`
first, since only one server can open the database at a time.

For a link that stays the same every time, use Tailscale Funnel instead (free
Tailscale account required):

```powershell
.\scripts\play-tailscale.ps1
```

This is the same as `.\scripts\play.ps1 -Tunnel tailscale`. It offers to
install Tailscale with winget, signs you in if needed, and prints
`https://<computer>.<tailnet>.ts.net`. The first time, Tailscale may print a
`login.tailscale.com` link to turn on Funnel for your account; open it and
approve. To change the link, rename the computer in the Tailscale admin
console (for example to `wargames`); the tailnet part can be changed to one
of the names Tailscale offers in its DNS settings. Your family just opens the
link; they do not need Tailscale.

- `-LocalOnly` skips the tunnel; players on the same Wi-Fi use the printed
  `http://192.168.x.x:8080` address (allow Java through Windows Firewall if needed).
- `-SkipBuild` reuses the last built jar.

Server output goes to `backend/data/server.log`. The map editor stays read-only
unless `MAP_EDITOR_TOKEN` is set.

## Deploy free on Render (always reachable)

The game can run on [Render](https://render.com)'s free plan at
`https://<name>.onrender.com`, with saved games in a free
[Neon](https://neon.tech) Postgres database (Render's free servers erase local
files on restart). `Dockerfile` builds the whole game into one image and
`render.yaml` describes the service.

1. **Neon:** sign up, create a project, and open **Connect**. Note the host,
   database, user, and password from the connection string
   `postgresql://<user>:<password>@<host>/<database>?sslmode=require`.
2. **GitHub:** push this repository (Render deploys from it).
3. **Render:** sign up with GitHub, choose **New → Blueprint**, pick this
   repository, and fill in the three values it asks for:
   - `DATABASE_URL` = `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `DATABASE_USERNAME` = `<user>`
   - `DATABASE_PASSWORD` = `<password>`
4. Wait for the first build (several minutes), then open the
   `https://….onrender.com` link. The address comes from `name` in
   `render.yaml` (`wargames` → `wargames.onrender.com`, plus a few
   random characters if that name is taken), so change it before the first
   deploy. You can also add your own domain under **Settings → Custom Domains**.

Every push to the main branch redeploys. On the free plan the server sleeps
after about 15 minutes with no visitors and takes up to a minute to wake on
the next visit; it stays awake while a game page is open. The map editor stays
read-only unless you add a `MAP_EDITOR_TOKEN` environment variable.

## Play a campaign

The default page is the War of the Ring lobby. Choose **Host a game** on a laptop,
set a display name, map scale and player count, and share the generated game
code or link. **6 players** is the classic game; **8 players** adds the
expansion: the Elves (Free Peoples, from Lothlórien) and the Ringwraiths
(Sauron's Forces, led by the Witch-king from Minas Morgul), each with 2 units
and a home stronghold with a garrison of 2.
Players open the same app on their phones, enter the code, and claim one of the
character seats. Some parties let the player pick who represents them: the
Hobbits are led by Frodo (default), Sam, Merry or Pippin; Rohan by Théoden,
Éowyn or Éomer; the Fellowship by Aragorn, Gandalf or Gimli; the Elves by
Galadriel, Legolas or Arwen. Whoever leads the Hobbits is the Ring-bearer. The
host can start after every seat is filled.
Everyone in a game can open a short rule sheet from the **Rules** button in
the header.

- **Joining:** the lobby shows a QR code for the join link; scan it with a phone
  camera. (On a local test, open the host page from the network address so the
  code points somewhere phones can reach.)
- **While you were away:** when your turn starts, a card lists what happened
  since your last turn ended on this device.
- **Sound effects:** dice, battle clashes, a horn for a captured stronghold,
  and themes for the Ring's fate are synthesized in the browser (no audio
  files). They're off by default; the speaker button in the header turns them
  on for that device.
- **Strongholds** show as keeps in their owner's color with one pip per
  garrison unit (hollow pips are lost units).
- **TV mode** (host only): a larger, full-screen view whose map glides to the
  active party each turn, with a bigger battle card. Hidden parties show the
  whole map.

Character portraits are optional. Put full-size square originals in
`assets/portraits/` (kept out of git and deploys), named after the leader in
lowercase without accents: `frodo.png`, `sam.png`, `merry.png`, `pippin.png`,
`theoden.png`, `eowyn.png`, `eomer.png`, `aragorn.png`, `gandalf.png`,
`gimli.png`, `galadriel.png`, `legolas.png`, `arwen.png`, `khamul.png`,
`saruman.png`, `sauron.png`, `witch-king.png`. Then run
`python scripts/make_portraits.py` to write 256 × 256 WebP copies to
`frontend/public/portraits/` (about 12 KB each), which the game loads. They
appear on map icons, seats and the character panel; a leader without an image
keeps its initials. Only use images you have the right to publish if that
folder is deployed.
Campaign sessions are stored by the Java API. There is no login: hosting or
joining gives that device a random 256-bit seat secret in an HttpOnly,
SameSite=Strict cookie scoped to that game's API path (`Secure` when served over
HTTPS). Page scripts cannot read it, and the server stores only its SHA-256
hash. Each seat has its own cookie, so several players can use different tabs
of one browser; a tab names the seat it acts as with the `X-Game-Seat` header
and the `seat` URL parameter, and the server checks that the matching cookie is
present. The home screen lists this device's saved seats (only the game code and
seat name are kept in `localStorage`) and can forget one. Seats are tied to the
device: a cleared browser or private window that closes loses them, and the seat
cannot be reclaimed from another device. Seat secrets saved by earlier versions
in `localStorage` are converted to cookies automatically and then deleted.

The host sets the Middle-earth map width in miles (1,000 miles by default).
Map coordinates use the seeded 1,774 × 887 map aspect ratio and movement uses
straight-line map distance. The server divides that path into the terrain
strokes it crosses and charges each segment at its terrain rate: plains 15,
forest 10, mountains 7.5, and volcanic/Mount Doom 6 miles per movement point.
Following a river uses a fixed 10 miles per point, and each river crossing
costs one additional point. Flow direction is not modeled. The seeder adds
volcanic strokes over the Mordor plateau if the map has none. Parties that end
in mountains or volcanic ground are hidden from the other team and from the
host's screen; forest and Mount Doom use a coin flip. The character panel
previews the segment and crossing costs before a move is confirmed.
Turn order is shuffled once when the campaign starts and kept every round.
Map character and city icons tile when locations overlap; select an icon to
toggle its information. To battle, move onto an enemy party or stronghold, then
choose **Resolve battle** or **Attack stronghold** in the character panel.
Starting armies: Hobbits 1, Rohan 2, Fellowship 3, Easterlings 1, Uruk-hai 2, Orcs 3
(expansion: Elves 2, Ringwraiths 2).
Battles: each side rolls one die per army unit (a leader with no units left
still rolls one). The higher total wins and ties go to the defender. The loser
loses exactly one unit, or falls if it had none left. A party attacks at most
once per turn. Defenders on volcanic ground or Mount Doom subtract 1 from each
die.
Strongholds fight the same way with a garrison: The Shire 1, Rohan 2,
Gondor 3, Rhûn 1, Barad-dûr 3, Isengard 2. Beating a stronghold removes one
garrison unit; beating one with no garrison left (its bare walls roll one die)
captures it. Garrisons do not regrow. Every battle plays out on screen as
rolling dice for all players.
Mount Doom is a small area: a move that ends within about 25 miles of it (2.5%
of the map width) lands on the summit. Frodo can destroy the Ring on his turn
there, including right after moving. Each opposing party on Mount Doom first
fights him once, in turn order; if Frodo falls (his Hobbits are lost first)
Sauron captures the Ring,
and if he survives them all the Free Peoples win. On wide screens, the ♪ button in the
game header plays background music: `frontend/public/music/theme.mp3` in the
lobby and setup screens, and `frontend/public/music/ambience.mp3` during the
campaign. It switches tracks as the screen changes, and each track resumes
where it left off. That folder is kept out of git and deploys (add your own
files locally); the button only appears where a file exists, and if one track
is missing the other plays everywhere.
Movement, army damage, direct-contact attacks, stronghold capture, hidden
positions, turn order, Ring capture/destruction, and victory are validated by
the API. For an internet-hosted game, publish the frontend and API behind the
same public origin (or route `/api` to the API); the development Vite server
also listens on the local network for phone testing. For that local test, open
the host page using Vite's printed **Network** URL rather than `localhost`, so
the copied join link points to an address the phone can reach.

## The Iron Throne (a Game of Thrones campaign)

A second campaign on a map of Westeros and Essos. Choose **The Iron Throne** at
the bottom of the War of the Ring home screen (or open `/?thrones=CODE`). Codes
from either game can be typed into either Join box; the app opens the right one.

The host sets the map width (2,500 miles by default). 2 to 7 houses play, each
claimed by a person on their own device or by a bot the host adds: House Stark,
Lannister, Targaryen, Baratheon, Greyjoy, Tyrell or Martell. A player may choose
which heir leads first. Houses nobody claims become neutral castles. The Dead are
always in the game, as three armies the server plays.

The first time a player opens a game, a four-card **How to play** guide explains
it; the button of that name opens it again, with a link to the full rules. In short:

- **The goal.** Hold every castle on the map at the end of a round, alone or with
  your coalition; everyone in the coalition wins. Being the last side standing
  (every house outside your coalition, and the Dead, destroyed) also wins. The
  Dead win if every living house falls, or if they hold every castle. Neutral
  castles start with small garrisons (1, or 2 for great seats and cities) so the
  map can be taken.
- **Your turn.** Collect gold (1 a castle, 2 for your own seat, 3 for King's
  Landing), move once, then fight once. Gold raises troops (2 each, 2 a turn) in a
  castle you hold. The turn card shows the steps and lights the next one.
- **Movement and battle** work as in War of the Ring, with snow, desert, rivers,
  mountains, forest and Valyria's volcanic ground. Tap the map to plan a march;
  the bubble shows the cost. Armies sail from port to port, using the whole turn.
  The Greyjoys sail from any coast, and dragons fly. The Wall can only be crossed
  through a castle you or a partner hold. Attack buttons show your odds.
- **Succession.** When a leader falls, the next heir rises at the house's seat.
  A house is out when its line ends or it holds no castle.
- **Coalitions.** Invite houses from the **Houses** panel at any time; accepting
  joins your coalitions together. Partners can't attack each other, see each
  other's armies, defend together and win together. Leaving (on your turn) and
  attacking a former partner the same turn is a Red Wedding (+2 per die), and
  makes you an oathbreaker: no new partners and no income for 3 rounds. Bots
  accept an invitation when they are outnumbered or the Dead are near.
- **The Long Night.** Winter arrives in round 8 (Fate cards can bring it
  sooner), and everyone sees it fall. Snow creeps south, the Dead's three armies
  grow every round, march on the Wall and then spread across the realm. Partners
  who both fight the Dead during the Long Night are **forged in winter**: +1 on
  every die for the rest of the game, while they stay together. Only dragons,
  Valyrian steel (Jon, Jaime or Arya) or Dragonstone's dragonglass can slay the
  Night King, and slaying him destroys every army of the Dead.
- **Each house** has a passive ability and a once-per-game power. A Fate card is
  drawn every round after the first.

The rules are in `backend/src/main/java/org/wargames/ThronesGame.java`, with
their fixed data in `ThronesRules.java`, the Dead's armies in `ThronesDeadAi.java`
(played through `BotScheduler` like any bot), and tests in `ThronesGameTest.java`.
The API is under `/api/thrones`. Saved games share the `game_sessions` table,
marked by a `game_type` column.

Portraits are optional, as for War of the Ring. Put square images in
`frontend/public/portraits/thrones/`, named after the leader in lowercase with
hyphens: `ned-stark.png`, `jon-snow.png`, `daenerys-targaryen.png`,
`night-king.png`, and so on. A leader without one shows the house sigil.

### The Westeros map

The art is `maps/westeros/source-art.webp` (2:1, like the Middle-earth map).
Place positions and the line of the Wall are in `maps/westeros/design.json`, in
map percentages, sitting on the castles painted in the art. After changing
either file, rebuild:

```sh
python scripts/build_westeros_map.py                       # map.png/map.webp and westeros-map.json
python scripts/generate_terrain.py --profile westeros     # terrain strokes and land/water mask
```

The second command writes `backend/src/main/resources/westeros-terrain.json` and
`westeros-land-mask.png`. The mask is used for sailing. Add `--preview
terrain/westeros-preview.png` to see what was detected.

The art itself is only 1774 × 887, so the game uses an AI upscale of it for
sharp zooming: the same picture with more pixels, nothing redrawn. With
[Real-ESRGAN](https://github.com/xinntao/Real-ESRGAN/releases)'s
`realesrgan-ncnn-vulkan` (Windows build, about 45 MB, runs on the GPU) in a
tools folder outside the project, and the art saved as `source.png`:

```sh
realesrgan-ncnn-vulkan -i source.png -o x4.png -n realesrgan-x4plus -s 4
python -c "from PIL import Image; Image.open('x4.png').resize((8192, 4096), Image.LANCZOS).save('level1.png')"
# level2.png (16384 x 8192): a second x4plus pass on level1.png, run in overlapping
# blocks to fit in memory, each block shrunk by 2 and stitched back together
python scripts/build_westeros_map.py --hd level1.png
python scripts/make_map_tiles.py --pyramid westeros level1.png level2.png
```

`map.webp` (4096 × 2048) loads first. Zooming in loads 1024-px tiles from
`frontend/public/map-assets/westeros/tiles/1/` (8K) and then `tiles/2/` (16K),
only those in view; `tiles/tiles.json` lists the levels. The maximum zoom is set
so one pixel of the 16K level fills about one pixel of the screen. The
`realesrgan-x4plus` model keeps the painted texture; the anime model flattens it.

## Playing with bots

In either game's lobby, the host can seat a bot in any open seat (**+ Easy bot**
or **+ Hard bot**), fill every open seat at once, or remove a bot with **×**. A
bot seat counts as filled, so a game can start with any mix of people and bots,
or bots alone. The server plays each bot's turn about 2.5 seconds after the
previous action (`BotScheduler`), so everyone sees each move and battle play out.

Bots only know what a player on their team would; armies hidden in the
mountains are hidden from them too.

- **War of the Ring** (`WarBot`). The Ring-bearer heads for Mount Doom; a hard
  one steers around enemies it can see and waits a turn if the interceptors on
  the mountain would likely kill it. Free Peoples bots clear enemies near Mount
  Doom and the Ring-bearer and escort it. Sauron's bots hunt the Ring-bearer when
  it shows itself and take the weakest strongholds; on hard, one of them guards
  Mount Doom.
- **The Iron Throne** (`ThronesBot`). Bot houses compete to win like players:
  they raise levies at castles they hold, march on castles and armies, and value
  King's Landing and the great seats. They never invite anyone, but accept an
  invitation to join forces when they are outnumbered or the Dead are near. The
  Dead's three armies are always played by the server, through the same paced turns.

Hard bots simulate the dice before each fight and only take good odds; easy bots
go for the nearest target, wander part of the way and take bad fights.

## Quick test games

To try a change with every seat filled, run:

```powershell
.\scripts\test-game.ps1                  # 6 players, waits in the lobby
.\scripts\test-game.ps1 -Players 8 -Start
.\scripts\test-game.ps1 -Leaders @{ frodo = 'Sam'; aragorn = 'Gandalf' }
```

It starts the API (port 8080) and the Vite dev server (port 5173) in their own
windows if they aren't already running. Then it opens your default browser with
a host tab that creates the game and one tab per character that joins its seat.
`-Start` begins the campaign once everyone has joined. Each tab is its own seat,
so switch tabs to play each turn. Frontend edits reload live; restart the API
window after backend changes. If `play.ps1` is already serving port 8080, the
tabs use that build's API. Add `-Url http://localhost:8080` to test the built app
instead of Vite.

Quick test links only work on localhost, reloading a tab reopens its seat, and
test games are left off the home screen's saved-games list.

## Map images

`frontend/public/map-assets/middle-earth/map.png` (4096 × 2048) is the source
art; the terrain generator reads it. The game shows `map.webp`, a 1.4 MB copy of
it. When a player zooms in past what that image can show sharply on their
screen, the map loads tiles from `hd/`: an AI upscale (Real-ESRGAN,
`realesr-animevideov3`, 2×) cut into 8 × 4 WebP tiles, 2.7 MB in all. Only the
tiles in view are loaded. After changing the map art, rebuild both:

```sh
realesrgan-ncnn-vulkan -i frontend/public/map-assets/middle-earth/map.png -o map-8k.png -n realesr-animevideov3 -s 2
python scripts/make_map_tiles.py map-8k.png
```

## Terrain strokes

Movement costs come from the terrain strokes stored in the database, not from
the map picture. `terrain/middle-earth.json` holds a full set generated from
the Middle-earth map art (forest, mountains, rivers, and Mordor's volcanic
plain). Regenerate it after changing the map art:

```sh
pip install pillow numpy scipy scikit-image
python scripts/generate_terrain.py --preview terrain/preview.png
```

Load it into a running game, local or live, with the map editor token. The
script first saves the game's current strokes to `terrain/backups/` and
prints the command to undo:

```powershell
.\scripts\import-terrain.ps1 -Url https://wargames.onrender.com
```

`-Append` adds strokes instead of replacing them; `-File` imports another
file, such as a backup. The script calls `POST /api/terrain-regions/import`
(`?replace=false` to append), which takes the same JSON format.

## Map editor access

The map editor requires a separate administrator token. Set `MAP_EDITOR_TOKEN`
in the backend environment before starting the API, then enter that token after
opening the map editor. Use a long, unique secret and share it only with map
administrators. The token is retained in the current browser tab's session
storage and is required on every map-data write; leaving the editor does not
revoke it, while **Lock editor** clears it.

Locally, `mvn spring-boot:run` and `scripts/play.ps1` use the `local` profile,
whose default token is `admin` (set `MAP_EDITOR_TOKEN` to override it). The
deployed app never uses that profile; on Render the token is the generated
`MAP_EDITOR_TOKEN` on the service's Environment page.

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
are `mountain`, `river`, `forest`, `volcanic`, and `other`. Map-data reads remain
public; all writes to campaigns, places, routes, events, armies, and terrain
regions require the `X-Map-Editor-Token` header. Terrain regions are map
annotations; the Middle-earth movement API uses the painted terrain strokes to
calculate travel costs, while other map data remains descriptive.
Deleting a campaign requires deleting its related records first. For example:
The existing `/api/locations` endpoint remains available as a Book of Mormon
map-location view.

```sh
curl http://localhost:8080/api/campaigns
curl "http://localhost:8080/api/places?campaignId=1"
curl -X POST http://localhost:8080/api/armies \
  -H "Content-Type: application/json" \
  -H "X-Map-Editor-Token: $MAP_EDITOR_TOKEN" \
  -d '{"campaignId":1,"name":"First Army","faction":"Nephites","side":"blue","x":48.3,"y":46.5,"unitCount":500}'
```

## Map controls

- Drag the map to pan; use the mouse wheel or zoom buttons to zoom. On the
  campaign map, wheel and pinch zoom center on the cursor or pinch point, two
  fingers pinch-zoom on phones, and panning stops at the map edges. Towns are
  labeled from 150% zoom and landmarks from 225%.
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
