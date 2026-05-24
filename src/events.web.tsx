import {
	type Selector,
	selectBuffer,
	selectError,
	selectPlayback,
	selectPlaybackRate,
	selectSource,
	selectTextTrack,
	selectTime,
	selectVolume,
	usePlayer,
} from "@videojs/react";
import { useEffect, useRef } from "react";
import type { OmniEvents } from "./types/events";
import type { OmniPlayerState } from "./types/player";

type EventMapperConfig = {
	[Key in keyof OmniEvents]?: {
		selector: Selector<any, any>;
		handler: (cb: OmniEvents[Key], value: any, prev: any) => void;
	};
};

function createEventMapper<Key extends keyof OmniEvents, Result>(
	key: Key,
	selector: Selector<any, Result>,
	handler: (
		cb: OmniEvents[Key],
		value: Result,
		prev: Result | undefined,
	) => void,
) {
	return { [key]: { selector, handler } };
}

const eventMapper: EventMapperConfig = {
	...createEventMapper("end", selectPlayback, (cb, value, prev) => {
		if (value?.ended && prev && !prev.ended) cb();
	}),
	...createEventMapper("error", selectError, (cb, value, prev) => {
		if (value?.error && value.error !== prev?.error) {
			cb(
				value.error.message ?? "Unknown error",
				value.error.message ?? "Unknown error",
			);
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
						selected: true,
					});
					return;
				}
			}
		}
		cb(undefined);
	}),
};

export const useEvent = <Event extends keyof OmniEvents>(
	event: Event,
	callback: OmniEvents[Event],
) => {
	const config = eventMapper[event];
	const callbackRef = useRef(callback);
	callbackRef.current = callback;
	const prevRef = useRef<any>(undefined);

	const value = usePlayer(config?.selector ?? (() => ({})));

	useEffect(() => {
		if (!config) return;
		const prev = prevRef.current;
		config.handler(callbackRef.current as any, value, prev);
		prevRef.current = value;
	}, [value, config]);
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
