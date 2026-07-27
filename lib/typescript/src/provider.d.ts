import { type ReactNode } from "react";
import type { OmniPlayer, PlayerBackend } from "./types/player";
import type { CastOptions, Source } from "./types/source";
export declare const OmniProvider: ({ children, source, backend, showNotification, cast, }: {
    source?: Source;
    cast?: CastOptions;
    backend?: PlayerBackend;
    children: ReactNode;
    showNotification?: boolean;
}) => import("react").JSX.Element;
export declare const usePlayer: () => OmniPlayer;
//# sourceMappingURL=provider.d.ts.map