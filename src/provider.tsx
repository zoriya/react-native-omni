import {
	createContext,
	type ReactNode,
	useContext,
	useEffect,
	useEffectEvent,
	useState,
} from "react";
import { NitroModules } from "react-native-nitro-modules";
import type {
	OmniPlayer as NativeOmniPlayer,
	OmniPlayerFactory,
} from "./specs/omni-player.nitro";
import type { AndroidBackend, OmniPlayer, PlayerBackend } from "./types/player";
import type { CastOptions, Source } from "./types/source";

const ProviderFactory =
	NitroModules.createHybridObject<OmniPlayerFactory>("OmniPlayerFactory");

const PlayerCtx = createContext<OmniPlayer>(null!);

export const OmniProvider = ({
	children,
	source,
	backend = { android: "vlc" },
	showNotification = false,
	cast,
}: {
	source?: Source;
	cast?: CastOptions;
	backend?: PlayerBackend;
	children: ReactNode;
	showNotification?: boolean;
}) => {
	const [player, setPlayer] = useState<NativeOmniPlayer | null>(null);

	const createPlayer = useEffectEvent((aBackend: AndroidBackend) =>
		ProviderFactory.createPlayer(source, { android: aBackend }, cast),
	);

	useEffect(() => {
		setPlayer(createPlayer(backend.android ?? "vlc"));
	}, [backend.android]);

	useEffect(() => {
		if (!player) return;
		return () => player.release();
	}, [player]);

	useEffect(() => {
		if (player) player.source = source;
	}, [player, source]);

	useEffect(() => {
		if (player) player.showNotification = showNotification;
	}, [player, showNotification]);

	if (!player) return null;
	return <PlayerCtx.Provider value={player}>{children}</PlayerCtx.Provider>;
};

export const usePlayer = () => {
	return useContext(PlayerCtx);
};
