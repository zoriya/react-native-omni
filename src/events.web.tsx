import { useEffect, useRef, useState } from "react";
import type { OmniEvents } from "./types/events";
import type { OmniPlayerState, PlayerStatus } from "./types/player";
import {
	selectPlayback,
	selectTime,
	selectBuffer,
	selectPlaybackRate,
	selectVolume,
	selectSource,
	usePlayer,
	type Selector,
} from "@videojs/react";

export const useEvent = <Event extends keyof OmniEvents>(
	event: Event,
	callback: OmniEvents[Event],
) => {
	const player = usePlayer();
	const callbackRef = useRef(callback);
	callbackRef.current = callback;

	useEffect(() => {
		const store = player.store;
		const media = store.state.media as any;

		if (!media) return;

		switch (event) {
			case "end": {
				const handler = () => (callbackRef.current as OmniEvents["end"])();
				media.addEventListener("ended", handler);
				return () => media.removeEventListener("ended", handler);
			}
			case "error": {
				const handler = () => {
					const error = store.state.error;
					(callbackRef.current as OmniEvents["error"])(
						error?.message ?? "Unknown error",
						error?.message ?? "Unknown error",
					);
				};
				media.addEventListener("error", handler);
				return () => media.removeEventListener("error", handler);
			}
			case "prev": {
				const handler = () => (callbackRef.current as OmniEvents["prev"])();
				store.state.onPrev = handler;
				return () => {
					if (store.state.onPrev === handler) store.state.onPrev = undefined;
				};
			}
			case "next": {
				const handler = () => (callbackRef.current as OmniEvents["next"])();
				store.state.onNext = handler;
				return () => {
					if (store.state.onNext === handler) store.state.onNext = undefined;
				};
			}
			case "videoTrackChange": {
				const handler = () => {
					if (!media.videoTracks?.length) return;
					for (let i = 0; i < media.videoTracks.length; i++) {
						const track = media.videoTracks[i];
						if (track?.enabled) {
							(callbackRef.current as OmniEvents["videoTrackChange"])({
								id: track.id || `video-${i}`,
								label: track.label,
								language: track.language,
								selected: true,
							});
							break;
						}
					}
				};
				media.videoTracks?.addEventListener("change", handler);
				return () => media.videoTracks?.removeEventListener("change", handler);
			}
			case "audioTrackChange": {
				const handler = () => {
					if (!media.audioTracks?.length) return;
					for (let i = 0; i < media.audioTracks.length; i++) {
						const track = media.audioTracks[i];
						if (track?.enabled) {
							(callbackRef.current as OmniEvents["audioTrackChange"])({
								id: track.id || `audio-${i}`,
								label: track.label,
								language: track.language,
								selected: true,
							});
							break;
						}
					}
				};
				media.audioTracks?.addEventListener("change", handler);
				return () => media.audioTracks?.removeEventListener("change", handler);
			}
			case "subtitleChange": {
				const handler = () => {
					if (!media.textTracks?.length) return;
					for (let i = 0; i < media.textTracks.length; i++) {
						const track = media.textTracks[i];
						if (track?.mode === "showing") {
							(callbackRef.current as OmniEvents["subtitleChange"])({
								id: track.id || `text-${i}`,
								label: track.label,
								language: track.language,
								selected: true,
							});
							return;
						}
					}
					(callbackRef.current as OmniEvents["subtitleChange"])(undefined);
				};
				media.textTracks?.addEventListener("cuechange", handler);
				return () =>
					media.textTracks?.removeEventListener("cuechange", handler);
			}
			case "audioFocusChange":
			case "renditionChange":
				return;
		}
	}, [player, event]);
};

type MapperConfig = {
	[Key in keyof OmniPlayerState]?: {
		selector: Selector<any, any>;
		mapper: (ret: any) => OmniPlayerState[Key];
	};
};

function createMapper<
	Key extends keyof OmniPlayerState,
	Result,
	Ret extends OmniPlayerState[Key],
>(key: Key, selector: Selector<any, Result>, mapper: (ret: Result) => Ret) {
	return { [key]: { selector, mapper } };
}

const mapper: MapperConfig = {
	...createMapper("status", selectPlayback, (s) => {
		if (s?.waiting) return "loading";
		if (s?.ended) return "idle";
		return "readyToPlay";
	}),
	...createMapper("isPlaying", selectPlayback, (s) => {
		if (!s) return false;
		return !s.paused;
	}),
	...createMapper("currentTime", selectTime, (s) => {
		return s?.currentTime ?? 0;
	}),
	...createMapper("buffered", selectBuffer, (s) => {
		if (!s?.buffered.length) return 0;
		const last = s.buffered[s.buffered.length - 1];
		return last?.[1] ?? 0;
	}),
	...createMapper("duration", selectTime, (s) => {
		return s?.duration ?? 0;
	}),
	...createMapper("playbackRate", selectPlaybackRate, (s) => {
		return s?.playbackRate ?? 1;
	}),
	...createMapper("volume", selectVolume, (s) => {
		return s?.volume ?? 1;
	}),
	...createMapper("muted", selectVolume, (s) => {
		return s?.muted ?? false;
	}),
	...createMapper("isAutoQuality", selectSource, (s) => {
		return false;
	}),
};

export function usePlayerState<Key extends keyof OmniPlayerState>(
	key: Key,
): OmniPlayerState[Key];
export function usePlayerState(
	key: "currentTime",
	refresh?: number,
): OmniPlayerState["currentTime"];
export function usePlayerState<Key extends keyof OmniPlayerState>(
	key: Key,
	_refresh?: number,
): OmniPlayerState[Key] {
	const config = mapper[key];
	if (!config) throw new Error(`No mapper for ${key}`);
	const ret = usePlayer(config.selector);
	return config.mapper(ret);
}
