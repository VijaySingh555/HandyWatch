# HandyWatch v0.1

One app for two Android devices: the **phone** (Vivo X Fold5) and the **watch** (an Android 8
watch, 320 × 480). The watch shows and controls the phone over classic Bluetooth.

- **Notifications:** the phone's notifications appear on the watch and buzz there. Dismissing one
  on the watch dismisses it on the phone (Settings switch). Exclude apps on the phone.
- **Music:** title, artist and app; previous, play/pause and next; the phone's music volume;
  Find phone (rings for up to a minute); Lock phone.
- **Control:**
  - **Camera:** opens the phone's camera screen. The shutter fires now or after 5 s / 10 s (big
    countdown and beeps on the phone). A small copy of each photo comes back to the watch.
    Photos are saved in `Pictures/HandyWatch`.
  - **Keys:** arrows (as swipes), play/pause, Back, Home, Recent apps, the notification shade,
    and volume.
  - **Pad:** moves a pointer on the phone. Tap clicks, hold long-presses, two fingers scroll.
  - **Type:** text goes into the phone's focused text box. There are Delete and Enter buttons too.
- **Status dials:** read from `esp_status.php` on rathour.net with the read key. They're fetched
  through the phone, so no internet is needed on the watch. They refresh every 5/10/30/60 s while
  the page is open, with an alert when a task finishes or fails. Display only: nothing is reported.
- **Mobile data:** uses Android's own Bluetooth tethering (Android doesn't let apps switch it
  on). The phone's set-up screen opens the tethering setting. The watch's Settings show whether
  it's online through the phone.

## First run

**Phone:** install, open, choose **This is my phone**, then work down *Set up this phone*:
Nearby devices, Pair the watch, Notification access, HandyWatch control (Accessibility), Camera,
Locking the phone (optional), Battery (Unrestricted and Autostart), Share mobile data.
On Android 13 and later, sideloaded apps need *App info › ⋮ › Allow restricted settings* before
Accessibility and notification access can be switched on.

**Watch:** install, open, choose **This is my watch**. Then in Settings › Phone, choose the phone.
For mobile data, open Bluetooth settings › the phone's cog › Internet access. In Settings › Status
dials, enter the read key.

## Build

Push to `main` (or run the workflow by hand). GitHub Actions builds a signed release APK named
**HandyWatch-apk**, using the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS` and `KEY_PASSWORD`. No libraries are used: only Android's own classes.

## Link

Bluetooth RFCOMM, service UUID `7d3f6a10-3b8e-4c1d-9a52-6e0b4f1c2d77`. The phone listens and the
watch connects, retrying from 3 s up to 60 s apart. Each message is one line of JSON.
Phone → watch messages are `{"t":...}`: `hello`, `nall`, `n+`, `n-`, `media`, `bat`, `cam`,
`shot`, `ring`, `ctl`, `toast`, `dials`. Watch → phone messages are `{"cmd":...}`: `hello`,
`dismiss`, `dismissall`, `media`, `ring`, `lock`, `cam`, `key`, `pad`, `move`, `tap`, `hold`,
`scroll`, `type`, `bksp`, `enter`, `dials`.
