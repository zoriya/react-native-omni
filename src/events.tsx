import { useEffect, useEffectEvent, useState } from "react";
import { ProviderFactory, usePlayer } from "./provider";
import type { OmniPlayer } from "./specs/omni-player.nitro";
import type { CastLoadRequest, OmniEvents } from "./types/events";
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

// cast connect (android tv): called when a sender casts to this app. throw to
// refuse the cast, senders show an error.
export const useCastLoad = (
	callback: (request: CastLoadRequest) => void | Promise<void>,
) => {
	const onLoad = useEffectEvent(
		async (request: CastLoadRequest) => await callback(request),
	);
	useEffect(() => ProviderFactory.addCastLoadListener(onLoad), []);
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
		let unsubscribe: (() => void) | undefined;
		switch (key) {
			case "currentTime":
			case "buffered":
			case "duration":
			case "playbackRate":
			case "volume":
				unsubscribe = em.addStateListener(key, setState);
				break;
			case "isPlaying":
			case "muted":
			case "isAutoQuality":
				unsubscribe = em.addStateBoolListener(key, setState);
				break;
			case "status":
				unsubscribe = em.addPlayerStatusListener(setState);
				break;
			case "castStatus":
				unsubscribe = em.addCastStatusListener(setState);
				break;
			case "source":
				unsubscribe = em.addSourceListener(setState);
				break;
			case "videos":
			case "audios":
			case "subtitles":
				unsubscribe = em.addTracksListener(key, setState);
				break;
			case "renditions":
				unsubscribe = em.addRenditionsListener(setState);
				break;
		}
		// the value read on render can be stale by now: events sent between the render
		// and this effect are lost (ex: `autoplay` starting the player on mount).
		setState(player[key]);
		return unsubscribe;
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
