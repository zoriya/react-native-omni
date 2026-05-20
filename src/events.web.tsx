import { useEffect, useRef, useState } from "react";
import { usePlayer } from "./provider.web";
import type { OmniEvents } from "./types/events";
import type { OmniPlayerState, PlayerStatus } from "./types/player";

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
				return () =>
					media.videoTracks?.removeEventListener("change", handler);
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
				return () =>
					media.audioTracks?.removeEventListener("change", handler);
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
	const player = usePlayer();
	const store = player.store;
	const [ret, setState] = useState<any>(player[key]);

	useEffect(() => {
		switch (key) {
			case "currentTime":
			case "buffered":
			case "duration":
			case "playbackRate":
			case "volume": {
				setState(store.state[key]);
				const handler = () => setState(store.state[key]);
				const unsub = store.subscribe(handler);
				return unsub;
			}
			case "isPlaying":
			case "muted":
			case "isAutoQuality": {
				setState(player[key]);
				const handler = () => setState(player[key]);
				const unsub = store.subscribe(handler);
				return unsub;
			}
			case "status": {
				const prevRef = { current: player.status as PlayerStatus };
				setState(prevRef.current);
				const int = setInterval(() => {
					const status = player.status;
					if (status !== prevRef.current) {
						prevRef.current = status;
						setState(status);
					}
				}, 100);
				return () => clearInterval(int);
			}
		}
	}, [player, key, store]);

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
