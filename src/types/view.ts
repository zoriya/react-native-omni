import type { SubtitleAssets } from "./subtitles";

export interface OmniViewProps {
	autoplay?: boolean;
	autoPip?: boolean;
	/** Web-only: URLs for the ASS/PGS subtitle renderer assets. */
	subtitleAssets?: SubtitleAssets;
}
