# Game Radar

Android app listing PC, PS5 and Xbox games:

- **Upcoming** – everything with a release date from tomorrow onward (up to 5 years out)
- **Last 60 days** – what came out in the past 60 days, newest first

Each game shows its release date, platform badges and genres. Tap a game for store links
(Steam, PlayStation Store, Xbox Store, Epic, GOG), the official website, YouTube trailers,
the IGDB page and a description.

Filters: All / PC / PS5 / Xbox, "Most popular first", and "Hide obscure titles"
(hides tiny listings almost nobody tracks; switch it off to see everything).

## 1. Get free IGDB keys

Release dates come from [IGDB](https://www.igdb.com) (run by Twitch). Unlike most game
databases it stores a separate date for every platform, so console ports of older PC games
(e.g. BeamNG.drive on PS5) show up on their own launch date.

1. Go to https://dev.twitch.tv/console/apps and log in with a Twitch account
   (two-factor login must be switched on).
2. **Register Your Application**: any name, OAuth Redirect URL `http://localhost`,
   category *Application Integration*, client type *Confidential*.
3. Open **Manage**, copy the **Client ID**, press **New Secret** and copy that too.
4. Paste both into the app the first time it opens (later: ⋮ → IGDB keys).

## 2. Build the APK — option A: GitHub (no software needed)

1. Create a free GitHub account and a new **empty** repository (e.g. `game-radar`).
2. Upload all the files in this folder (including the hidden `.github` folder —
   easiest from a computer: drag the folder contents onto the repo's "uploading an
   existing file" page, or use `git push`).
   If the `.github` folder doesn't upload (some computers hide it), open the repo's
   **Actions** tab → "set up a workflow yourself", name it `build.yml`, and paste in
   the contents of `.github/workflows/build.yml`.
3. GitHub builds the app automatically (Actions tab, ~4 minutes).
4. On your phone, open the repo → **Releases** → **Game Radar (latest build)** →
   download `GameRadar.apk`, open it, and allow "install unknown apps" when asked.

## 2. Build the APK — option B: Android Studio

1. Install Android Studio, then **File → Open** this folder.
2. Let Gradle sync, then **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. The APK is at `app/build/outputs/apk/debug/app-debug.apk` — copy it to your phone
   and install, or plug the phone in and press ▶ Run.

## Notes

- A game that launches on different days per platform appears once per date
  (e.g. Over the Hill: PC in October, consoles later).
- Dates like "Q4 2026" or "2027" are shown as "exact day not announced".
- "Hide obscure titles" hides listings nobody on IGDB follows or has rated yet.
