import { createContext, type ReactNode, useContext, useEffect } from "react";
import { NitroModules } from "react-native-nitro-modules";
import type { OmniPlayerFactory } from "./specs/omni-player.nitro";
import type { OmniPlayer } from "./types/player";
import type { Source } from "./types/source";
import type { SubtitleAssets } from "./types/subtitles";
import { useLazyRef } from "./utils/lazy-ref";

const ProviderFactory =
	NitroModules.createHybridObject<OmniPlayerFactory>("OmniPlayerFactory");

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	showNotification = false,
}: {
	source: Source;
	children: ReactNode;
	showNotification?: boolean;
	/** Web-only: URLs for the ASS/PGS subtitle renderer assets. Ignored here. */
	subtitleAssets?: SubtitleAssets;
}) => {
	const player = useLazyRef(() => ProviderFactory.createPlayer(source));

	useEffect(() => {
		player.setSource(source);
	}, [source]);

	useEffect(() => {
		player.showNotification = showNotification;
	}, [showNotification]);

	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => {
	return useContext(PlayerCtx);
};
