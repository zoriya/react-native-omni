import { createContext, type ReactNode, useContext, useEffect } from "react";
import { NitroModules } from "react-native-nitro-modules";
import type { OmniPlayerFactory } from "./specs/omni-player.nitro";
import type { OmniPlayer } from "./types/player";
import type { CastOptions, Source } from "./types/source";
import { useLazyRef } from "./utils/lazy-ref";

const ProviderFactory =
	NitroModules.createHybridObject<OmniPlayerFactory>("OmniPlayerFactory");

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	showNotification = false,
	cast: _,
}: {
	source?: Source;
	cast?: CastOptions;
	children: ReactNode;
	showNotification?: boolean;
}) => {
	const player = useLazyRef(() => ProviderFactory.createPlayer(source));

	useEffect(() => {
		player.source = source;
	}, [source]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => {
	return useContext(PlayerCtx);
};
