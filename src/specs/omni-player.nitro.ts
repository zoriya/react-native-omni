import type { HybridObject } from "react-native-nitro-modules";
import type { OmniEvents } from "../types/events";
import type {
	// OmniPlayerState,
	OmniPlayer as OmniPlayerT,
} from "../types/player";
import type { Source } from "../types/source";

export interface OmniEventMap extends HybridObject<{ android: "kotlin" }> {
	// addStateListener(
	// 	key: keyof OmniPlayerState,
	// 	cb: (value: number) => void,
	// ): void;
	// removeStateListener(cb: OmniEvents["end"]): void;

	addOnEndListener(cb: OmniEvents["end"]): void;
	removeOnEndListener(cb: OmniEvents["end"]): void;

	addOnPrevListener(cb: OmniEvents["prev"]): void;
	removeOnPrevListener(cb: OmniEvents["prev"]): void;

	addOnNextListener(cb: OmniEvents["next"]): void;
	removeOnNextListener(cb: OmniEvents["next"]): void;

	addOnErrorListener(cb: OmniEvents["error"]): void;
	removeOnErrorListener(cb: OmniEvents["error"]): void;

	addOnAudioFocusChangeListener(cb: OmniEvents["audioFocusChange"]): void;
	removeOnAudioFocusChangeListener(cb: OmniEvents["audioFocusChange"]): void;

	addOnVideoTrackChangeListener(cb: OmniEvents["videoTrackChange"]): void;
	removeOnVideoTrackChangeListener(cb: OmniEvents["videoTrackChange"]): void;

	addOnAudioTrackChangeListener(cb: OmniEvents["audioTrackChange"]): void;
	removeOnAudioTrackChangeListener(cb: OmniEvents["audioTrackChange"]): void;

	addOnSubtitleChangeListener(cb: OmniEvents["subtitleChange"]): void;
	removeOnSubtitleChangeListener(cb: OmniEvents["subtitleChange"]): void;

	addOnRenditionChangeListener(cb: OmniEvents["renditionChange"]): void;
	removeOnRenditionChangeListener(cb: OmniEvents["renditionChange"]): void;
}

export interface OmniPlayer
	extends HybridObject<{ android: "kotlin" }>,
		OmniPlayerT {
	readonly eventMap: OmniEventMap;
}

export interface OmniPlayerFactory extends HybridObject<{ android: "kotlin" }> {
	createPlayer(props: Source): OmniPlayer;
}
