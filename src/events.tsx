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
		player.eventMap[`addOn${capitalize(event)}Listener`](callback as any);
		return () =>
			player.eventMap[`removeOn${capitalize(event)}Listener`](callback as any);
	}, [player, event, callback]);
};

export const usePlayerState = <Key extends keyof OmniPlayerState>(
	key: Key,
): OmniPlayerState[Key] => {
	const player = usePlayer() as OmniPlayer;
	const [ret, setState] = useState<any>(player[key]);

	useEffect(() => {
		const em = player.eventMap;
		switch (key) {
			case "currentTime":
			case "buffered":
			case "duration":
			case "playbackRate":
			case "volume":
				em.addStateListener(key, setState);
				return () => em.removeStateListener(key, setState);
			case "isPlaying":
			case "muted":
				em.addStateBoolListener(key, setState);
				return () => em.removeStateBoolListener(key, setState);
			case "status":
				em.addPlayerStatusListener(setState);
				return () => em.removePlayerStatusListener(setState);
		}
	}, [player, key]);

	return ret;
};
