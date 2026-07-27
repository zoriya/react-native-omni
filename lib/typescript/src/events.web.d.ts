import { type Selector } from "@videojs/react";
import type { OmniEvents } from "./types/events";
import type { OmniPlayerState } from "./types/player";
export declare const useEvent: <Event extends keyof OmniEvents>(event: Event, callback: OmniEvents[Event]) => void;
export declare const stateMapper: {
    status: {
        selector: Selector<object, import("@videojs/core").MediaPlaybackState | undefined>;
        mapper: (ret: import("@videojs/core").MediaPlaybackState | undefined) => import(".").PlayerStatus;
    };
    isPlaying: {
        selector: Selector<object, import("@videojs/core").MediaPlaybackState | undefined>;
        mapper: (ret: import("@videojs/core").MediaPlaybackState | undefined) => boolean;
    };
    currentTime: {
        selector: Selector<object, import("@videojs/core").MediaTimeState | undefined>;
        mapper: (ret: import("@videojs/core").MediaTimeState | undefined) => number;
    };
    buffered: {
        selector: Selector<object, import("@videojs/core").MediaBufferState | undefined>;
        mapper: (ret: import("@videojs/core").MediaBufferState | undefined) => number;
    };
    duration: {
        selector: Selector<object, import("@videojs/core").MediaTimeState | undefined>;
        mapper: (ret: import("@videojs/core").MediaTimeState | undefined) => number;
    };
    playbackRate: {
        selector: Selector<object, import("@videojs/core").MediaPlaybackRateState | undefined>;
        mapper: (ret: import("@videojs/core").MediaPlaybackRateState | undefined) => number;
    };
    volume: {
        selector: Selector<object, import("@videojs/core").MediaVolumeState | undefined>;
        mapper: (ret: import("@videojs/core").MediaVolumeState | undefined) => number;
    };
    muted: {
        selector: Selector<object, import("@videojs/core").MediaVolumeState | undefined>;
        mapper: (ret: import("@videojs/core").MediaVolumeState | undefined) => boolean;
    };
    isAutoQuality: {
        selector: Selector<object, import("@videojs/core").MediaQualityState | undefined>;
        mapper: (ret: import("@videojs/core").MediaQualityState | undefined) => boolean;
    };
    castStatus: {
        selector: Selector<object, import("@videojs/core").MediaRemotePlaybackState | undefined>;
        mapper: (ret: import("@videojs/core").MediaRemotePlaybackState | undefined) => import(".").CastStatus;
    };
};
export declare function usePlayerState<Key extends keyof OmniPlayerState>(key: Key): OmniPlayerState[Key];
export declare function usePlayerState(key: "currentTime", refresh?: number): OmniPlayerState["currentTime"];
//# sourceMappingURL=events.web.d.ts.map