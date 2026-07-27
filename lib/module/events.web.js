"use strict";

import { selectAudioTrack, selectBuffer, selectError, selectPlayback, selectPlaybackRate, selectQuality, selectRemotePlayback, selectTextTrack, selectTime, selectVolume, usePlayer as useStoreSelector } from "@videojs/react";
import { useEffect, useRef } from "react";
import { usePlayer } from "./provider.web";
function createEventMapper(key, selector, handler) {
  return {
    [key]: {
      selector,
      handler
    }
  };
}
const eventMapper = {
  ...createEventMapper("end", selectPlayback, (cb, value, prev) => {
    if (value?.ended && prev && !prev.ended) cb();
  }),
  ...createEventMapper("error", selectError, (cb, value, prev) => {
    if (value?.error && value.error !== prev?.error) {
      cb(value.error.message ?? "Unknown error", value.error.message ?? "Unknown error");
    }
  }),
  ...createEventMapper("subtitleChange", selectTextTrack, (cb, value, prev) => {
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
  ...createEventMapper("audioTrackChange", selectAudioTrack, (cb, value) => {
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
  ...createEventMapper("renditionChange", selectQuality, (cb, value) => {
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
export const useEvent = (event, callback) => {
  const config = eventMapper[event];
  const callbackRef = useRef(callback);
  callbackRef.current = callback;
  const prevRef = useRef(undefined);
  const value = useStoreSelector(config?.selector ?? (() => undefined));
  const player = usePlayer();
  useEffect(() => {
    if (!config) return;
    config.handler(callbackRef.current, value, prevRef.current);
    prevRef.current = value;
  }, [value, config]);
  useEffect(() => {
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
function createMapper(key, selector, mapper) {
  return {
    [key]: {
      selector,
      mapper
    }
  };
}
export const stateMapper = {
  ...createMapper("status", selectPlayback, s => {
    if (s?.waiting) return "loading";
    if (s?.ended) return "idle";
    return "readyToPlay";
  }),
  ...createMapper("isPlaying", selectPlayback, s => {
    if (!s) return false;
    return !s.paused;
  }),
  ...createMapper("currentTime", selectTime, s => {
    return s?.currentTime ?? 0;
  }),
  ...createMapper("buffered", selectBuffer, s => {
    if (!s?.buffered.length) return 0;
    const last = s.buffered[s.buffered.length - 1];
    return last?.[1] ?? 0;
  }),
  ...createMapper("duration", selectTime, s => {
    return s?.duration ?? 0;
  }),
  ...createMapper("playbackRate", selectPlaybackRate, s => {
    return s?.playbackRate ?? 1;
  }),
  ...createMapper("volume", selectVolume, s => {
    return s?.volume ?? 1;
  }),
  ...createMapper("muted", selectVolume, s => {
    return s?.muted ?? false;
  }),
  ...createMapper("isAutoQuality", selectQuality, q => {
    if (!q) return true;
    return !q.videoRenditionList.some(r => r.selected);
  }),
  ...createMapper("castStatus", selectRemotePlayback, r => {
    if (!r) return "unavailable";
    if (r.remotePlaybackState === "connecting") return "connecting";
    if (r.remotePlaybackState === "connected") return "connected";
    return r.remotePlaybackAvailability;
  })
};
export function usePlayerState(key, _refresh) {
  const config = stateMapper[key];
  if (!config) throw new Error(`No mapper for ${key}`);
  const ret = useStoreSelector(config.selector);
  return config.mapper(ret);
}
//# sourceMappingURL=events.web.js.map