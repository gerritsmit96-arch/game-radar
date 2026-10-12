# Game Radar

Android app listing PC, PS5 and Xbox games:

- **Upcoming** – everything with a release date from tomorrow onward (up to 5 years out)
- **Last 60 days** – what came out in the past 60 days, newest first

Each game shows its release date, platform badges and genres. Tap a game for store links
(Steam, PlayStation Store, Xbox Store, Epic, GOG), the official website, YouTube trailers,
the RAWG page and a description.

Filters: All / PC / PS5 / Xbox, "Most popular first", and "Hide obscure titles"
(hides tiny listings almost nobody tracks; switch it off to see everything).

## 1. Get a free RAWG API key

The release data comes from [RAWG.io](https://rawg.io), the largest open game database.

1. Go to https://rawg.io/apidocs and sign up (free, ~1 minute).
2. Copy your API key. The app asks for it the first time it opens
   (you can change it later via ⋮ → API key).

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

- Release dates are only as good as RAWG's data; "(estimated)" means the publisher
  hasn't confirmed the exact day yet. Games with no date at all ("TBA") can't be listed
  by date, so they don't appear.
- RAWG's free tier allows 20,000 requests a month — far more than personal use needs.
