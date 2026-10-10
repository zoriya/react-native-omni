import { useEffect, useState } from "react";
import { usePlayer } from "./provider";
import type { OmniPlayer } from "./specs/omni-player.nitro";
import type { OmniEvents } from "./types/events";
import type { OmniPlayerState } from "./types/player";

function capitalize<T extends string>(str: T): Capitalize<T> {
	return (str.charAt(0).toUpperCase() + str.slice(1)) as Capitalize<T>;
}

export const useEvent = <Event extends keyof OmniEvents>(
	event: Event,
	callback: OmniEvents[Event],
) => {
	const player = usePlayer() as OmniPlayer;
	useEffect(() => {
		return player.eventMap[`addOn${capitalize(event)}Listener`](
			callback as any,
		);
	}, [player, event, callback]);
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
	refresh?: number,
): OmniPlayerState[Key] {
	const player = usePlayer() as OmniPlayer;
	// don't call `player[key]` (native call on main thread) every re-render
	// only do it when needed (init)
	const [ret, setState] = useState<any>(() => player[key]);

	useEffect(() => {
		const em = player.eventMap;
		switch (key) {
			case "currentTime":
			case "buffered":
			case "duration":
			case "playbackRate":
			case "volume":
				return em.addStateListener(key, setState);
			case "isPlaying":
			case "muted":
			case "isAutoQuality":
				return em.addStateBoolListener(key, setState);
			case "status":
				return em.addPlayerStatusListener(setState);
			case "castStatus":
				return em.addCastStatusListener(setState);
			case "source":
				return em.addSourceListener(setState);
			case "videos":
			case "audios":
			case "subtitles":
				return em.addTracksListener(key, setState);
			case "renditions":
				return em.addRenditionsListener(setState);
		}
	}, [player, key]);

	if (key === "currentTime") refresh ??= 1;
	useEffect(() => {
		if (!refresh || refresh <= 0) return;
		const int = setInterval(() => {
			setState(player[key]);
		}, refresh * 1000);
		return () => clearInterval(int);
	}, [refresh, key, player]);

	return ret;
}
