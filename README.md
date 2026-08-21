# HGA-Media

An Android TV / Fire TV IPTV player with a built-in advertising system.

Think IPTV Smarters Pro for the playback and layout, SmartOne for the tidiness,
Kodi for the "runs on anything" attitude — with an advert engine bolted through
the middle so a screen in a venue earns its keep between programmes.

**No streams are included.** You supply an Xtream Codes line or an M3U playlist.

---

## What it does

**Playback**
- Xtream Codes portals (`player_api.php`) — live, movies, series, episodes
- Plain M3U / M3U8 playlists
- XMLTV guide, or the provider's own "now and next"
- Favourites, categories, search, direct channel-number entry
- Remote-friendly throughout: D-pad, OK, channel up/down, number keys

**Adverts** — three modes, switchable at any time
| Mode | What the viewer sees |
|---|---|
| `lbar` | Picture shrinks into the top-left 75%; advert fills the L. Nothing is covered. |
| `overlay` | Banner fades over the lower third for a few seconds. |
| `interstitial` | Full-screen advert while the next channel tunes. |

- Two advert sources: a primary on your own network, a fallback on the web
- Everything cached on the device, so adverts keep running with no internet
- Per-advert scheduling: date range, days of the week, time of day, weighting
- Play counts per advert, so you can invoice an advertiser with a number

**Venue features**
- PIN-protected owner console (default PIN `4321`)
- Start on power up, resume last channel
- Venue lock so staff cannot change the playlist
- Device naming for multi-site setups

---

## Build it

You do not need Android Studio. Push this folder to a GitHub repository and the
included workflow builds an installable APK for you. See `docs/` for the
step-by-step guide.

If you do have Android Studio: open the folder, press Run.

```
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK 34. minSdk is 21, so it installs on Fire TV
Sticks, Android TV boxes, Google TV sets, phones and tablets from one APK.

---

## Layout of the code

```
app/src/main/java/com/hga/media/
├── HgaApp.kt              application start-up
├── data/                  playlists, EPG, settings
│   ├── XtreamClient.kt    Xtream Codes API
│   ├── M3uParser.kt       M3U / M3U8
│   ├── EpgSource.kt       streaming XMLTV reader
│   ├── Repo.kt            single source of truth + disk cache
│   └── Prefs.kt           every persisted setting
├── ads/                   the advert engine
│   ├── AdRepository.kt    fetch, cache, choose
│   ├── AdController.kt    L-bar / overlay / interstitial display
│   └── AdSyncWorker.kt    background refresh
├── ui/                    screens
└── util/                  HTTP, images, helpers
```

`docs/advert-pack/` holds artwork templates, a worked `ads.json` and three
ready-made example adverts you can put live in five minutes.

---

## Branding

Modelled on the EmulationStation "Carbon" theme: near-black carbon weave, cool
white text, one bright accent. To rebrand the entire app, change a single value:

`app/src/main/res/values/colors.xml` → `hga_accent`
