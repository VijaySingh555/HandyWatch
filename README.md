# HandyWatch v0.5

One app for two Android devices: the **phone** (Vivo X Fold5) and the **watch** (a CD99 Android 9
watch, about 410 × 502). The watch shows and controls the phone over classic Bluetooth.

- **Notifications:** the phone's notifications appear on the watch and buzz there. Swipe one
  sideways to dismiss it (on the phone too, if chosen); tap one for the full text and time.
  Exclude apps on the phone.
- **Music:** title, artist and app (long titles scroll); previous, play/pause and next; the
  phone's music volume (tried three ways until the level moves, or real volume keys in keyboard mode); Find phone (the ringtone as an alarm, heard in silent mode, with
  vibration, for up to a minute); Lock phone.
- **Control:**
  - **Camera:** tap the preview to start the phone's camera; the shutter then takes the photo
    (the watch buzzes and its screen flashes white with "Photo taken"; the phone blinks). Flash
    Off/Auto/On on both devices (On lights the torch while filming). The phone's camera screen
    shows recent photos and videos: tap to open, hold to delete. Selfies are saved mirrored, as
    the preview shows them (by EXIF, so nothing is re-encoded). A live preview from the phone (small JPEGs over Bluetooth, the next sent once
    the last is shown). Photo or video, now or after 5 s / 10 s; a black-screen button lets
    the phone film with its screen dark. Photos go to `Pictures/HandyWatch`, videos to
    `Movies/HandyWatch`.
  - **Keys:** a 3 × 3 pad: volume either side of up; left, play/pause, right; Back, down, Home.
    With **Settings › Keys › Real keys** the watch acts as a Bluetooth keyboard for the phone
    (Android 9+, if the watch's Bluetooth has the keyboard profile): real arrow keys, volume and
    play/pause keys, Back, Home and typing. Otherwise the arrows are swipes through HandyWatch
    control (Accessibility).
  - **Pad:** moves a pointer on the phone. Tap clicks, hold long-presses, two fingers scroll.
  - **Type:** text goes into the phone's focused text box. There are Delete and Enter buttons too.
- **Status dials:** read from `esp_status.php` on rathour.net with the read key. They're fetched
  through the phone, so no internet is needed on the watch. They refresh every 5/10/30/60 s while
  the page is open, with an alert when a task finishes or fails. Display only: nothing is reported.
- **Look:** OLED black or Grey (after HandyDash's grey pages), set on each device.
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
`shot`, `frame`, `ring`, `ctl`, `typed`, `toast`, `dials`. Watch → phone messages are `{"cmd":...}`: `hello`,
`dismiss`, `dismissall`, `media`, `ring`, `lock`, `cam`, `key`, `pad`, `move`, `tap`, `hold`,
`scroll`, `type`, `bksp`, `enter`, `dials`.
