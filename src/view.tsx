import {
	getHostComponent,
	type HybridViewMethods,
} from "react-native-nitro-modules";
import OmniConfig from "../nitrogen/generated/shared/json/OmniViewConfig.json";
import { usePlayer } from "./provider";
import type { OmniPlayer } from "./specs/omni-player.nitro";
import type { Props } from "./specs/omni-view.nitro";
import type { OmniViewProps } from "./types/view";

const NativeView = getHostComponent<Props, HybridViewMethods>(
	"OmniView",
	() => OmniConfig,
);

export const OmniView = (props: OmniViewProps) => {
	const player = usePlayer() as OmniPlayer;
	return <NativeView player={player} {...props} />;
};
