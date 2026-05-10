# Rokid Live Studio Android

Android/Kotlin MVP for receiving the Rokid Glasses camera/microphone stream on the phone, then streaming from the phone to YouTube or Twitch.

The glasses helper keeps the glasses side as light as possible: it opens the Wi-Fi Direct group, captures the glasses camera/mic, and sends the media to the phone. The phone does the heavier work: preview, rotation/mirror, encoding, RTMP/RTMPS publishing, YouTube/Twitch API calls, and chat polling.

## Network Flow

1. The phone connects to Hi Rokid through CXR-L.
2. The phone launches or talks to the helper app on the glasses.
3. When streaming starts, the helper opens the Wi-Fi Direct group on the glasses.
4. The phone joins that Wi-Fi Direct group.
5. The phone receives H.264 video and AAC audio from the glasses.
6. The phone can show a preview, encode the final stream, and publish to YouTube or Twitch.

Outdoor use does not require a home router. The glasses need Wi-Fi enabled so they can host Wi-Fi Direct, and the phone can keep using 4G/5G for YouTube/Twitch while Wi-Fi Direct carries the glasses stream to the phone.

## YouTube Modes

The YouTube screen has two modes.

| Mode | Google Cloud needed? | What the app controls | What stays in YouTube Studio |
| --- | --- | --- | --- |
| Stream key | No | Sends video/audio to the pasted stream key | Live creation, title, visibility, category, thumbnail, scheduling, chat |
| OAuth account | Yes, unless the app ships later with its own verified OAuth client | Creates the live, sets title/description/visibility/category, fetches the stream key, transitions the broadcast live/complete, reads chat for the helper overlay | Thumbnail and advanced Studio-only settings |

Short version: the `YouTube TV client ID` and `YouTube TV client secret` are only needed for `OAuth account` mode. They are not needed for stream key mode.

## Simple Mode: Stream Key

Use this if you want the easiest setup and do not need the app to control the YouTube live metadata.

1. Create or open a live in YouTube Studio.
2. Copy the stream key.
3. In Rokid Live Studio, open the YouTube screen.
4. Select `Stream key`.
5. Paste the key into `Stream Key`.
6. Select the resolution/preset in the app.
7. Tap `Start stream with key`.

In this mode the app only sends video and audio. It cannot change the title, visibility, category, thumbnail, or chat settings. Those options are handled in YouTube Studio and are disabled in the app.

This is the easiest mode to distribute because there is no OAuth, no Google verification, and no user account connection.

The `YouTube bitrate` setting only changes the phone's outgoing encoded RTMPS stream. It does not change the Rokid camera capture. If the phone preview is clean but YouTube looks blocky or stalls, lower the bitrate first. If that still happens at low bitrates, the likely bottleneck is the phone's upload/RTMPS path rather than the glasses camera or Wi-Fi Direct.

## OAuth Mode: TV Device Code

Use this if you want the app to create and control the YouTube live.

1. Complete the Google Cloud setup below.
2. In Rokid Live Studio, open the YouTube screen.
3. Select `OAuth account`.
4. Open `Advanced OAuth setup`.
5. Paste the `YouTube TV client ID`.
6. Paste the `YouTube TV client secret`.
7. Tap `Generate code & open page`.
8. The app copies the TV code to the clipboard and opens the Google device page.
9. Paste the code if Google does not fill it automatically.
10. Choose the correct Google account or YouTube Brand Account.
11. Approve access.
12. Return to Rokid Live Studio.
13. Tap `Refresh` if you want to verify the linked YouTube channel.
14. Set the title, visibility, category, resolution, bitrate, and chat helper options.
15. Tap `Create live and start stream`.

For Brand Accounts, prefer the TV/device-code flow. The Google account chooser can show the personal account and its Brand Accounts explicitly, which is why this flow was added.

If the Google Cloud OAuth app stays in testing mode, only the Google accounts listed as test users can link YouTube.

## Google Cloud Setup For YouTube OAuth

Official references:

- [OAuth 2.0 for TV and Limited-Input Device Applications](https://developers.google.com/youtube/v3/guides/auth/devices)
- [Obtaining authorization credentials](https://developers.google.com/youtube/registering_an_application)
- [YouTube Data API OAuth guide](https://developers.google.com/youtube/v3/guides/authentication)
- [Google OAuth app verification help](https://support.google.com/cloud/answer/13463073)
- [Unverified apps](https://support.google.com/cloud/answer/7454865)

### 1. Create Or Select A Google Cloud Project

1. Open [Google Cloud Console](https://console.cloud.google.com/).
2. Create a new project, for example `Rokid Live Studio`, or select an existing project.
3. Make sure the selected project is visible in the top project selector before continuing.

### 2. Enable The YouTube API

1. Go to `APIs & Services` -> `Library`.
2. Search for `YouTube Data API v3`.
3. Open it.
4. Click `Enable`.

No extra Google API is currently needed for categories or chat. The app uses YouTube Data API v3 for live creation, stream binding, live transitions, video/category updates, and live chat polling.

### 3. Configure The OAuth Consent Screen

1. Go to `APIs & Services` -> `OAuth consent screen`.
2. Choose the audience/user type.
   - For personal testing, use `External` and keep the app in testing mode.
   - If this is only for a Google Workspace organization, `Internal` may be available.
3. Fill the required app information:
   - App name: `Rokid Live Studio`
   - User support email
   - Developer contact email
4. Add the YouTube scope used by the MVP:
   - `https://www.googleapis.com/auth/youtube`
5. Save the consent screen.
6. While the app is in testing mode, add every Google account that should be allowed to sign in under `Test users`.

The `https://www.googleapis.com/auth/youtube` scope is broad, but the MVP needs account-level YouTube management to create lives, bind streams, update metadata/category, transition broadcasts, and read live chat. If the app becomes public, Google may require OAuth verification before normal users can connect without warnings or caps.

### 4. Create The TV OAuth Client

1. Go to `APIs & Services` -> `Credentials`.
2. Click `Create credentials`.
3. Choose `OAuth client ID`.
4. For application type, choose `TVs and Limited Input devices`.
5. Name it, for example `Rokid Live Studio TV Device`.
6. Click `Create`.
7. Copy the generated `Client ID`.
8. Copy the generated `Client secret`.

Google's device-flow docs note that apps distributed on devices cannot truly keep secrets private. In this MVP, the client secret is entered by the user in the app and stored encrypted on the phone. Do not commit your real client secret to Git.

### 5. Paste The Client In The Android App

1. Open Rokid Live Studio on the phone.
2. Go to `YouTube`.
3. Select `OAuth account`.
4. Open `Advanced OAuth setup`.
5. Paste the Cloud Console `Client ID` into `YouTube TV client ID`.
6. Paste the Cloud Console `Client secret` into `YouTube TV client secret`.
7. Tap `Generate code & open page`.
8. Choose the correct YouTube channel or Brand Account on the Google page.

After linking, the app stores the refresh token encrypted on the phone. `Sign out` removes the stored device refresh token.

### 6. Publishing And Verification

For local/personal testing, you can keep the OAuth app in testing mode and add yourself as a test user.

For distribution to other users, you have three realistic options:

1. Use `Stream key` mode only. No Google Cloud setup is required for end users.
2. Let advanced users create their own Google Cloud TV OAuth client and paste their own client ID/secret into the app.
3. Publish and verify your own Google OAuth app, then ship the app with your verified client configuration.

If the app requests sensitive or restricted scopes and is public-facing, Google may show an `unverified app` warning or enforce user caps until the OAuth app is verified. The verification process can require scope justification and a demo video showing the OAuth flow and how the requested scope is used.

## Twitch Modes

The Twitch screen mirrors the YouTube screen, but Twitch does not have private/unlisted livestream visibility. Twitch streams are controlled by the channel state in Creator Dashboard.

| Mode | Twitch developer setup needed? | What the app controls | What stays in Twitch |
| --- | --- | --- | --- |
| Stream key | No | Sends video/audio to the pasted stream key | Stream key management, title, category, chat integration |
| OAuth account | Yes | Fetches the stream key, updates channel title/category, reads chat for the helper overlay | Advanced Creator Dashboard settings |

Short version: `Stream key` mode is the easiest distribution path. `OAuth account` mode is needed if you want the app to manage Twitch metadata and show Twitch chat on the glasses helper.

## Simple Mode: Twitch Stream Key

Use this if you want the fastest Twitch setup.

1. Open Twitch Creator Dashboard.
2. Go to `Settings` -> `Stream`.
3. Copy the primary stream key.
4. In Rokid Live Studio, open the Twitch screen.
5. Select `Stream key`.
6. Paste the key into `Stream Key`.
7. Select the resolution and Twitch bitrate.
8. Tap `Start Twitch Stream`.

In this mode the app only sends video and audio to Twitch RTMP. It cannot change the title/category or read Twitch chat. Those options stay in Twitch Creator Dashboard and are disabled in the app.

## Twitch OAuth Setup

Use this if you want Rokid Live Studio to fetch the stream key automatically, update title/category, and display chat messages on the glasses helper.

Official references:

- [Twitch Developer Console](https://dev.twitch.tv/console/apps)
- [Twitch Device Code Flow](https://dev.twitch.tv/docs/authentication/getting-tokens-oauth/#device-code-grant-flow)
- [Twitch Helix API](https://dev.twitch.tv/docs/api/)
- [Twitch EventSub WebSocket](https://dev.twitch.tv/docs/eventsub/handling-websocket-events/)

### 1. Create A Twitch Developer App

1. Open [Twitch Developer Console](https://dev.twitch.tv/console/apps).
2. Click `Register Your Application`.
3. Name it, for example `Rokid Live Studio`.
4. Choose `Public` as the client type. Android APKs cannot keep a client secret private.
5. If Twitch asks for an OAuth Redirect URL, enter `http://localhost`. The MVP uses device-code login, so this URL is not used by the phone flow.
6. Choose an application category such as `Application Integration`.
7. Create the app.
8. Copy the `Client ID`.

The MVP does not need a Twitch client secret on the phone. Device-code login uses the public Client ID.

### 2. Paste The Client ID In The Android App

1. Open Rokid Live Studio on the phone.
2. Go to `Twitch`.
3. Select `OAuth account`.
4. Open `Advanced OAuth setup`.
5. Paste the Twitch Developer Console `Client ID`.
6. Tap `Generate code & open page`.
7. The app copies the TV/device code to the clipboard and opens the Twitch activation page.
8. Approve access with the Twitch account that owns the channel.
9. Return to Rokid Live Studio.
10. Tap `Refresh` if you want to verify the linked channel.
11. Set the title, category, resolution, bitrate, and helper chat options.
12. Tap `Start Twitch Stream`.

### 3. Twitch OAuth Scopes Used By The MVP

The app requests:

- `channel:read:stream_key` to fetch the RTMP stream key.
- `channel:manage:broadcast` to update the channel title/category.
- `user:read:chat` to receive chat messages through EventSub WebSocket.

After linking, the app stores the Twitch refresh token encrypted on the phone. `Sign out` removes the stored Twitch refresh token.

## Distribution And Updates

Best options:

| Path | Best for | Update behavior |
| --- | --- | --- |
| Google Play internal/closed testing | Real users and the cleanest install/update flow | Android handles updates |
| Firebase App Distribution | Private testers | Testers get install/update links |
| GitHub Releases | Direct APK distribution | Users install APKs manually unless the app adds an update checker |

GitHub can build and publish APKs, but do not commit signing keys or API secrets. Store release signing material in GitHub Secrets, for example:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

For public direct APK updates, the phone app Settings screen has a `Check & Install Update` button. It checks the latest public GitHub Release, finds a `.apk` asset, compares the release tag/body with the installed app version, downloads the APK when it is newer, then opens Android's package installer. If the GitHub repo stays private, do not put a GitHub token in the APK; use Play Store/Firebase or a small public update JSON endpoint instead.

Release naming expected by the in-app updater:

- Use tags like `v0.1.1`.
- Prefer tags like `v0.1.1+2` or add `versionCode: 2` in the release body when you want strict `versionCode` comparison.
- Attach the phone APK to the release. If multiple APKs are attached, names containing `phone` or `rokid-live-studio` are preferred over helper/glasses APKs.

## Current MVP Notes

- The helper app is responsible for opening Wi-Fi Direct on the glasses.
- The phone joins the helper group and keeps the heavy network/YouTube/Twitch work.
- YouTube category and helper chat are available in YouTube OAuth mode.
- Twitch category and helper chat are available in Twitch OAuth mode.
- Stream key mode is simpler but leaves metadata in YouTube Studio or Twitch Creator Dashboard.
- YouTube/Twitch chat is read by the phone, then sent as text to the helper overlay.
- Audio sent to YouTube/Twitch comes from the glasses microphone when the helper provides it.
