# Anikoto for CloudStream

A [CloudStream 3](https://github.com/recloudstream/cloudstream) extension for **[anikoto.cz](https://anikoto.cz)**.

## Install

In CloudStream: **Settings → Extensions → Add repository**, and paste:

```
https://raw.githubusercontent.com/UncleNoor/anikoto-cloudstream/builds/repo.json
```

Then open the repository and install **AnikotoProvider**.

(The `builds` branch is rebuilt by GitHub Actions on every push to `main`.)

## Features

- Home page rows: Latest Updated, New Releases, Most Viewed, Currently Airing, Movies, Upcoming
- Search, with pagination
- Full details: poster, banner, synopsis, genres, year, status, duration, recommendations, MAL id (for tracking sync)
- Separate **Sub** and **Dub** episode lists
- Streams from every Anikoto server (HD-1, HD-2, Vidstream-1/2), all served by the MegaPlay player, deduplicated
- All subtitle tracks the player provides (often 40+ languages on Crunchyroll-sourced shows)

## How it works

| Step | Endpoint |
| --- | --- |
| Listing / search | `/{latest-updated,…}?page=N`, `/filter?keyword=…` |
| Episodes | `/ajax/episode/list/{animeId}` → `data-ids` per episode |
| Servers | `/ajax/server/list?servers={ids}` → `data-link-id` per server |
| Embed URL | `/ajax/server?get={linkId}` → `megaplay.buzz/stream/s-2/{id}/{sub,dub}` |
| Stream | `megaplay.buzz/stream/getSources?id={dataId}` → `enc` = base64url AES-256-CBC JSON with the `master.m3u8` URL |

The AES key and IV are the ones shipped in MegaPlay's `lib/app.main.js`. If they rotate, the extractor
re-reads them from that file automatically. HLS requests need `Referer: https://megaplay.buzz/`.

## Building locally

Requires JDK 17+ and the Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir=`).

```sh
./gradlew AnikotoProvider:make          # → AnikotoProvider/build/AnikotoProvider.cs3
./gradlew AnikotoProvider:deployWithAdb # push to a connected device running CloudStream
```
