import { type ReactNode } from "react";
import type { OmniPlayer, PlayerBackend } from "./types/player";
import type { CastOptions, Source } from "./types/source";
export declare const VideoPlayer: import("@videojs/react").CreatePlayerResult<import("@videojs/react").VideoPlayerStore>;
export declare const OmniProvider: ({ children, source, cast, backend: _backend, showNotification, }: {
    source?: Source;
    cast?: CastOptions;
    backend?: PlayerBackend;
    children: ReactNode;
    showNotification?: boolean;
}) => import("react").JSX.Element;
export declare const usePlayer: () => OmniPlayer;
//# sourceMappingURL=provider.web.d.ts.map