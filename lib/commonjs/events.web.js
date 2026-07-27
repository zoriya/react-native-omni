"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.useEvent = exports.stateMapper = void 0;
exports.usePlayerState = usePlayerState;
var _react = require("@videojs/react");
var _react2 = require("react");
var _provider = require("./provider.web");
function createEventMapper(key, selector, handler) {
  return {
    [key]: {
      selector,
      handler
    }
  };
}
const eventMapper = {
  ...createEventMapper("end", _react.selectPlayback, (cb, value, prev) => {
    if (value?.ended && prev && !prev.ended) cb();
  }),
  ...createEventMapper("error", _react.selectError, (cb, value, prev) => {
    if (value?.error && value.error !== prev?.error) {
      cb(value.error.message ?? "Unknown error", value.error.message ?? "Unknown error");
    }
  }),
  ...createEventMapper("subtitleChange", _react.selectTextTrack, (cb, value, prev) => {
    if (value === prev) return;
    const tracks = value?.textTrackList;
    if (tracks?.length) {
      for (let i = 0; i < tracks.length; i++) {
        const track = tracks[i];
        if (track?.mode === "showing") {
          cb({
            id: `text-${i}`,
            label: track.label,
            language: track.language,
            selected: true
          });
          return;
        }
      }
    }
    cb(undefined);
  }),
  ...createEventMapper("audioTrackChange", _react.selectAudioTrack, (cb, value) => {
    if (!value) return;
    const track = value.audioTrackList.find(t => t.enabled);
    if (!track) return;
    cb({
      id: track.id,
      label: track.label,
      language: track.language,
      selected: true
    });
  }),
  ...createEventMapper("renditionChange", _react.selectQuality, (cb, value) => {
    const active = value?.activeVideoRendition;
    if (!active) return;
    cb({
      id: active.id,
      width: active.width ?? 0,
      height: active.height ?? 0,
      bitrate: active.bitrate ?? 0,
      selected: true
    });
  })
};
const useEvent = (event, callback) => {
  const config = eventMapper[event];
  const callbackRef = (0, _react2.useRef)(callback);
  callbackRef.current = callback;
  const prevRef = (0, _react2.useRef)(undefined);
  const value = (0, _react.usePlayer)(config?.selector ?? (() => undefined));
  const player = (0, _provider.usePlayer)();
  (0, _react2.useEffect)(() => {
    if (!config) return;
    config.handler(callbackRef.current, value, prevRef.current);
    prevRef.current = value;
  }, [value, config]);
  (0, _react2.useEffect)(() => {
    // don't use callbackRef.current directly to keep future callback updates
    const cb = () => callbackRef.current();
    if (event === "prev") {
      player.onPrev.add(cb);
      return () => {
        player.onPrev.delete(cb);
      };
    }
    if (event === "next") {
      player.onNext.add(cb);
      return () => {
        player.onNext.delete(cb);
      };
    }
    return undefined;
  }, [event, player]);
};
exports.useEvent = useEvent;
function createMapper(key, selector, mapper) {
  return {
    [key]: {
      selector,
      mapper
    }
  };
}
const stateMapper = exports.stateMapper = {
  ...createMapper("status", _react.selectPlayback, s => {
    if (s?.waiting) return "loading";
    if (s?.ended) return "idle";
    return "readyToPlay";
  }),
  ...createMapper("isPlaying", _react.selectPlayback, s => {
    if (!s) return false;
    return !s.paused;
  }),
  ...createMapper("currentTime", _react.selectTime, s => {
    return s?.currentTime ?? 0;
  }),
  ...createMapper("buffered", _react.selectBuffer, s => {
    if (!s?.buffered.length) return 0;
    const last = s.buffered[s.buffered.length - 1];
    return last?.[1] ?? 0;
  }),
  ...createMapper("duration", _react.selectTime, s => {
    return s?.duration ?? 0;
  }),
  ...createMapper("playbackRate", _react.selectPlaybackRate, s => {
    return s?.playbackRate ?? 1;
  }),
  ...createMapper("volume", _react.selectVolume, s => {
    return s?.volume ?? 1;
  }),
  ...createMapper("muted", _react.selectVolume, s => {
    return s?.muted ?? false;
  }),
  ...createMapper("isAutoQuality", _react.selectQuality, q => {
    if (!q) return true;
    return !q.videoRenditionList.some(r => r.selected);
  }),
  ...createMapper("castStatus", _react.selectRemotePlayback, r => {
    if (!r) return "unavailable";
    if (r.remotePlaybackState === "connecting") return "connecting";
    if (r.remotePlaybackState === "connected") return "connected";
    return r.remotePlaybackAvailability;
  })
};
function usePlayerState(key, _refresh) {
  const config = stateMapper[key];
  if (!config) throw new Error(`No mapper for ${key}`);
  const ret = (0, _react.usePlayer)(config.selector);
  return config.mapper(ret);
}
//# sourceMappingURL=events.web.js.map