import type { OmniEvents } from "./types/events";
import type { OmniPlayerState } from "./types/player";
export declare const useEvent: <Event extends keyof OmniEvents>(event: Event, callback: OmniEvents[Event]) => void;
export declare function usePlayerState<Key extends keyof OmniPlayerState>(key: Key): OmniPlayerState[Key];
export declare function usePlayerState(key: "currentTime", refresh?: number): OmniPlayerState["currentTime"];
//# sourceMappingURL=events.d.ts.map