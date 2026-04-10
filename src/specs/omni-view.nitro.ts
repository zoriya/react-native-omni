import type {
	HybridView,
	HybridViewMethods,
	HybridViewProps,
} from "react-native-nitro-modules";
import type { OmniViewProps } from "../types/view";
import type { OmniPlayer } from "./omni-player.nitro";

export interface Props extends HybridViewProps, OmniViewProps {
	player: OmniPlayer;
}

export type OmniView = HybridView<
	Props,
	HybridViewMethods,
	{ android: "kotlin" }
>;
