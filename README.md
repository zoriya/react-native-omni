# react-native-omni

A library to have real players on android and web. It uses VLC on android and
videojs v10 on the web (ios not implemented yet, PR welcome)

## Features

- **vlc v4**: to support more codecs than exoplayer, hdr and so on
- **Adaptive streaming**: HLS out of the box, with automatic quality
  (rendition) selection or manual override.
- **Multi-track playback**: enumerate and switch video, audio, and subtitle
  tracks at runtime.
- **Rich subtitle support**: vtt, srt, ass (via [jassub](https://github.com/ThaUnknown/jassub)) and pgs (via [libpgs](https://github.com/Arcus92/libpgs-js))
- **Picture-in-Picture**: enter PiP automatically or on demand on Android.
- **and basic player stuff**: media sessions, playlists, hook based api...


## Installation

```bash
bun add react-native-omni react-native-nitro-modules
```

### Expo config plugin (Android)

The library ships an Expo config plugin that wires up media notifications and
picture-in-picture. Add it to your `app.json` / `app.config.js`:

```json
{
  "expo": {
    "plugins": ["react-native-omni"]
  }
}
```

The plugin will:

- Register the `OmniPlayerService` media session service and add the
  `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permissions.
- Enable Picture-in-Picture on your `MainActivity` (declares
  `supportsPictureInPicture`, adds the required `configChanges`, and hooks the
  pip lifecycle callbacks).

If you are not using Expo, replicate those manifest/activity changes manually.

### Web setup

Most features are available out of the box, you need custom steps for advanced
subtitles rendering:

<details>

<summary>ass rendering</summary>

#### Fonts

JASSUB only renders glyphs for fonts it has, and does **not** ship a usable
default font. Provide the fonts a subtitle references through
`source.fonts` (an array of font-file URLs). Optionally set
`subtitleAssets.jassub.fontUrl` as a fallback for styles whose font isn't
listed. If no matching font is available, that text simply won't appear.

#### Cross-origin isolation

jassub's wasm is multi-threaded and relies on `SharedArrayBuffer`, which
browsers only expose on a **cross-origin-isolated** page. Serve these response
headers on your HTML document:

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: credentialless
```

`credentialless` keeps cross-origin video/subtitle/font requests working without
requiring `Cross-Origin-Resource-Policy` headers on every remote asset. Without
isolation, JASSUB initializes but silently never renders.

</details>

## Quick start

Wrap your player UI in an `OmniProvider` with a `source`, then render an
`OmniView` and drive it with the hooks.

```tsx
import {
  OmniProvider,
  OmniView,
  usePlayer,
  usePlayerState,
  useEvent,
} from "react-native-omni";

function Player() {
  const player = usePlayer();
  const isPlaying = usePlayerState("isPlaying");
  const currentTime = usePlayerState("currentTime");
  const duration = usePlayerState("duration");

  useEvent("end", () => player.playNext());

  return (
    <>
      <OmniView style={{ width: "100%", aspectRatio: 16 / 9 }} autoplay />
      <Button
        title={isPlaying ? "Pause" : "Play"}
        onPress={() => (isPlaying ? player.pause() : player.play())}
      />
      <Text>
        {currentTime.toFixed(0)} / {duration.toFixed(0)}
      </Text>
    </>
  );
}

export default function App() {
  const source = {
    src: [{ uri: "https://example.com/stream.m3u8", headers: {} }],
    subtitles: [],
    metadata: { title: "My video", hasPrev: false, hasNext: true },
  };

  return (
    <OmniProvider source={source} showNotification>
      <Player />
    </OmniProvider>
  );
}
```

## API

### `<OmniProvider>`

Creates the underlying player and exposes it to children. Must wrap any
component that uses `<OmniView>`, `usePlayer`, `usePlayerState`, or `useEvent`.

| Prop               | Type      | Default | Description                                          |
| ------------------ | --------- | ------- | ---------------------------------------------------- |
| `source`           | `Source`  | —       | The media to play.                                   |
| `showNotification` | `boolean` | `false` | Create a media session (OS controls/notification).   |
| `children`         | `ReactNode` | —     | Your player UI.                                      |

Updating `source` swaps the media in place (and seeks to `source.startTime` if
set) without recreating the player.

### `<OmniView>`

Renders the video surface. Place it inside an `OmniProvider`. Accepts a `style`
plus:

| Prop             | Type             | Platform | Description                                                             |
| ---------------- | ---------------- | -------- | ----------------------------------------------------------------------- |
| `autoplay`       | `boolean`        | all      | Start playing as soon as the media is ready.                            |
| `autoPip`        | `boolean`        | Android  | Automatically enter Picture-in-Picture when the app is backgrounded.    |
| `subtitleAssets` | `SubtitleAssets` | Web      | URLs for the ASS/PGS renderer worker/wasm/font assets (see below).      |

### `usePlayer()`

Returns the `OmniPlayer` instance — an imperative handle for controlling
playback and reading/writing state.

**Playback controls**

```ts
player.play();
player.pause();
player.seekBy(offset); // relative seek in seconds

player.playPrev(); // fires the `prev` event (implement navigation yourself)
player.playNext(); // fires the `next` event
player.hasPrev; // readonly boolean
player.hasNext; // readonly boolean
```

**Writable state**

```ts
player.currentTime = 42; // seek to absolute position (seconds)
player.playbackRate = 1.5;
player.volume = 0.8; // 0..1
player.muted = true;
```

**Readonly state**

```ts
player.status; // "idle" | "loading" | "readyToPlay" | "error"
player.isPlaying;
player.buffered; // seconds buffered
player.duration; // seconds
player.isAutoQuality; // whether rendition selection is automatic
```

**Tracks & renditions**

```ts
player.videos; // Track[]
player.selectVideo(track);

player.audios; // Track[]
player.selectAudio(track);

player.subtitles; // Track[]
player.selectSubtitle(track); // pass undefined to turn subtitles off

player.rendition; // Rendition[]
player.selectRendition(rendition); // pass undefined for automatic quality
```

> Prefer `usePlayerState`/`useEvent` for values that change over time — reading
> them directly off `player` gives you a one-time snapshot and won't re-render.

### `usePlayerState(key, refresh?)`

Subscribes to a single reactive player property and re-renders when it changes.
Only the component using it re-renders.

```ts
const status = usePlayerState("status");
const isPlaying = usePlayerState("isPlaying");
const duration = usePlayerState("duration");
const currentTime = usePlayerState("currentTime", 1); // poll every 1s
```

The optional `refresh` argument (seconds) sets a polling interval.
Useful for `currentTime`, which defaults to a 1s refresh (we don't refresh every
ms for this because crossing js/native bridge on native consumes lot of battery)

### `useEvent(event, callback)`

Subscribes to a player event for the lifetime of the component.

```ts
useEvent("end", () => player.playNext());
useEvent("prev", handlePrev);
useEvent("next", handleNext);
useEvent("error", (type, message) => console.warn(type, message));
useEvent("audioFocusChange", (status) => {/* … */});

useEvent("videoTrackChange", (track) => {/* … */});
useEvent("audioTrackChange", (track) => {/* … */});
useEvent("subtitleChange", (track) => {/* track may be undefined = off */});
useEvent("renditionChange", (rendition) => {/* … */});
```

| Event              | Signature                              | When                                          |
| ------------------ | -------------------------------------- | --------------------------------------------- |
| `end`              | `() => void`                           | Playback reached the end.                     |
| `prev` / `next`    | `() => void`                           | Prev/next requested (from UI or OS controls). |
| `error`            | `(type, message) => void`              | A playback error occurred.                    |
| `audioFocusChange` | `(status) => void`                     | The OS audio focus changed.                   |
| `videoTrackChange` | `(track) => void`                      | The active video track changed.               |
| `audioTrackChange` | `(track) => void`                      | The active audio track changed.               |
| `subtitleChange`   | `(track?) => void`                     | The active subtitle changed (`undefined` = off). |
| `renditionChange`  | `(rendition) => void`                  | The active quality/rendition changed.         |

## Example app

A full example (playlist navigation, track/rendition selectors, an event log,
and web subtitle assets) lives in [`example/`](./example). To run it:

```bash
bun install
bun run android   # Android
bun run web       # Web
```
