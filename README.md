# Spotify Ad Muter (Android)

Background app that mutes Spotify ads and restores the sound when music resumes.

## How it works
- Uses a **Notification Listener** so Android keeps it running and starts it automatically — no need to open the app each time.
- Watches Spotify's media session (track title / artist / album / skip availability).
- If the item looks like an ad (title or artist "Advertisement"/"Spotify", or no artist + no album, or a short unskippable item with no album), it mutes the media volume.
- As soon as a normal track is reported, volume is restored. It also unmutes if Spotify closes.
- The ad still plays inside Spotify (nothing is modified in Spotify); you just don't hear it.

## Optional: Restart Spotify on ad (experimental)
Turn on the switch in the app, then enable *Settings → Accessibility → Installed apps →
Spotify Ad Muter – Restart mode*. When an ad is detected it:
1. opens Spotify's App info page, taps **Force stop** and confirms,
2. reopens Spotify and presses Play.

Notes:
- Made for Samsung One UI with the phone language set to **English** (it looks for the "Force stop" / "OK" buttons).
- Only works when the screen is on and unlocked; otherwise the ad is just muted.
- It won't restart more than once every 90 s (to avoid loops); ads in between are muted.
- If Spotify plays the ad again after the restart, keep this off and rely on muting.

## Build the APK (GitHub Actions — no Android Studio needed)
1. Create a new GitHub repo, e.g. `Spotify-Ad-Muter`.
2. Upload **everything in this folder, including the `.github` and `app` folders**.
   On github.com → *Add file → Upload files* → drag the folders in.
   Hidden `.github` folders are easy to miss: if it doesn't upload, create
   `.github/workflows/build-apk.yml` with *Add file → Create new file* and paste the contents.
3. Go to the **Actions** tab → *Build APK* → open the latest run → download **SpotifyAdMuter-apk** (zip with the APK).

## Install & set up
1. Install `SpotifyAdMuter.apk` (allow "install unknown apps").
2. Open the app → **Grant notification access** → enable *Spotify Ad Muter*.
   - Android 13+: if this toggle (or the accessibility toggle) is greyed out, go to *Settings → Apps → Spotify Ad Muter → ⋮ → Allow restricted settings*, then try again.
3. Optional but recommended: *Settings → Apps → Spotify Ad Muter → Battery → Unrestricted*, so the phone doesn't stop it.
4. Play Spotify. The status and log in the app show what's happening.

## If an ad isn't muted
Open the app and look at the **Detection log** — it shows exactly what Spotify reported for the ad
(title, artist, album, length, skip). Adjust `adReason()` in `AdMuterService.java` to match.
