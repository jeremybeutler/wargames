# Wargames Map

A browser-based campaign map and experimental WebRTC multiplayer prototype built
with Vue 3. Campaign data is stored locally in encrypted IndexedDB; no Java
backend or central database is required.

## Run locally

Requires Node.js 20+ and a modern browser:

```sh
cd frontend
npm install
npm run dev
```

Open the Vite URL printed in the terminal. Web Crypto and WebRTC require a
secure context, so use `localhost` for local development and HTTPS when hosted.

## Local encrypted storage

On first use, create a passphrase of at least 12 characters. The browser derives
an AES-256-GCM key from it using PBKDF2-SHA-256; the passphrase is never stored
or sent. Campaigns, places, routes, events, armies, terrain annotations, and
saved multiplayer room snapshots are encrypted before being written to
IndexedDB. The default campaign and map reference data is bundled with the app
and encrypted when installed into a new local database.

The database belongs to the current browser profile and site origin. It is not
automatically shared with other browsers or devices. Export encrypted backups
regularly; restoring or unlocking a backup requires the same passphrase. There
is no password reset or server-side copy. Losing the passphrase means losing
access to the data.

To migrate data from the old Java/H2 version, choose the one-time legacy H2 JSON
export while creating the encrypted database. The imported data replaces the
built-in collections and is encrypted in IndexedDB. The migration file itself
is plaintext; delete it after a successful import. For a database that has
already been set up in the browser, restore an encrypted backup from the setup
screen in a fresh browser profile.

## Map controls

- Drag the map to pan; use the mouse wheel or zoom buttons to zoom.
- Select **Place army**, then click the map to place a piece.
- Drag a piece to move it. Select it and press Delete/Backspace to remove it.
- Choose a terrain type and optionally name it, select **Paint terrain**, then
  drag across the map. Select **Erase painted terrain** and click a stroke to
  remove it, or use its delete button in the sidebar. Press Escape to stop
  either terrain tool.
- Use the location selector to navigate to named places.
- Use **Map layers** to switch between the labeled reference map and the
  assembled CES tile mosaic. The mosaic is drawn on a high-resolution canvas
  from separate 256 × 256 PNG tiles; available zoom 9 and 10 tiles are
  overlaid on zoom 8 tiles for extra detail.

Terrain regions are descriptive map annotations; movement and combat rules are
not applied automatically.

## Experimental browser multiplayer

The multiplayer panel lets a player host a room and share its invitation JSON.
The host approves join requests and proposed army placements, moves, and
removals; guests only see those changes after the host accepts them. The host
can also edit the room ruleset JSON. Its supported fields are `name`,
`requireHostApproval`, `maxArmies`, `maxUnitsPerArmy`, `allowArmyMovement`, and
`allowArmyRemoval`. Set `requireHostApproval` to `false` to have valid guest
army actions accepted automatically by the host.

Multiplayer uses Metered Realtime for signaling and WebRTC data channels for
game messages. The signaling service exchanges connection setup information;
STUN helps peers discover direct network paths, and TURN relays encrypted
WebRTC traffic only when a direct path cannot be established. Room messages
also use AES-GCM with a random key carried in the invitation. Treat invitations
as secrets and share them only with intended players.

Create a Metered **publishable** key in Realtime Messaging → API Keys. Enable
`Send`, `Publish`, `Subscribe`, and `Presence`, and limit the channel pattern
to `bom-wargames-*`. The Realtime SDK handles TURN configuration. Enter the
same key on each player's device. The key is intentionally browser-visible
(it is not a secret) and is saved in that browser's local storage; anyone who
obtains it can use the permissions configured for it.

To prefill the key for local development, copy `frontend/.env.example` to
`frontend/.env.local`, set `VITE_METERED_PUBLISHABLE_KEY` to your `pk_live_…`
or `pk_test_…` key, then restart Vite. Vite embeds `VITE_` variables in the
browser bundle, so only use a publishable key here—never TURN credentials or a
private API key. The `.env.local` file is ignored by Git.

Metered's published free tier currently lists
100 peak concurrent connections, 100,000 signaling messages/month, and 20 GB
of TURN usage/month; check the provider's [current terms and limits](https://www.metered.ca/tools/openrelay/webrtc-library)
before relying on them.

Metered and TURN operators can observe connection metadata such as peer
presence and network addresses, and can delay or block connectivity. Game
payloads travel peer-to-peer when possible; if TURN is needed, it forwards
WebRTC-encrypted packets. Play pauses if the host disconnects; host migration
is not implemented.

The host's room snapshot is saved in encrypted IndexedDB in that browser.
Multiplayer does not synchronize campaign data or terrain annotations between
players, and changes made to armies in a room do not alter the local campaign
army collection. Each player needs access to the frontend and the corresponding
built-in campaign/map data. Metered provides hosted signaling and optional TURN
transport, but no hosted game authority or campaign database.

## Download the CES map tiles shown in DevTools

The downloader requests the exact extensionless `z/x/y` tile paths visible in
the supplied screenshots under `https://media.bookofmormon.online/map/ces/`,
saves PNG files under the frontend's public assets, and writes the manifest
used by the canvas. It checks `robots.txt`, waits between requests, and applies
per-file and total download limits.

```sh
python scripts/download_map_assets.py
```
