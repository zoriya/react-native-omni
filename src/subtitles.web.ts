import type { Subtitle } from "./types/source";
import type { SubtitleAssets } from "./types/subtitles";

/**
 * ASS/SSA and PGS subtitles cannot be rendered by a native `<track>` element,
 * so they are drawn on a canvas overlay by jassub (ASS) and libpgs (PGS).
 * Everything else (WebVTT, in-manifest tracks) is handled natively.
 *
 * The worker/wasm/font assets must be hosted by the consuming app; these
 * defaults match the layout jassub / libpgs ship in their packages and can be
 * overridden through the `subtitleAssets` prop on `OmniProvider`.
 */
const DEFAULT_ASSETS = {
	jassub: {
		workerUrl: "/jassub/jassub-worker.js",
		wasmUrl: "/jassub/jassub-worker.wasm",
		modernWasmUrl: "/jassub/jassub-worker-modern.wasm",
		fontUrl: "/jassub/default.woff2",
	},
	pgs: {
		workerUrl: "/pgs/libpgs.worker.js",
	},
};

export type SubtitleFormat = "vtt" | "ass" | "pgs" | "native";

export const getSubtitleFormat = (subtitle: {
	mimeType?: string;
	link: string;
}): SubtitleFormat => {
	const mime = subtitle.mimeType?.toLowerCase() ?? "";
	const ext =
		subtitle.link.split(/[?#]/)[0]?.split(".").pop()?.toLowerCase() ?? "";
	if (
		mime.includes("ass") ||
		mime.includes("ssa") ||
		ext === "ass" ||
		ext === "ssa"
	)
		return "ass";
	if (mime.includes("pgs") || ext === "sup") return "pgs";
	if (mime.includes("vtt") || ext === "vtt") return "vtt";
	return "native";
};

/** Whether a subtitle needs the JS overlay renderer rather than `<track>`. */
export const isCustomSubtitle = (subtitle: {
	mimeType?: string;
	link: string;
}): boolean => {
	const format = getSubtitleFormat(subtitle);
	return format === "ass" || format === "pgs";
};

export interface SubtitleRenderer {
	destroy(): void;
}

/**
 * Create the overlay renderer for an ASS or PGS subtitle attached to `video`.
 * Returns `null` for formats that do not need an overlay. The heavy renderer
 * libraries are imported lazily so they only load when actually needed.
 */
export const createSubtitleRenderer = (
	video: HTMLVideoElement,
	subtitle: Subtitle,
	assets?: SubtitleAssets,
): Promise<SubtitleRenderer | null> => {
	const format = getSubtitleFormat(subtitle);

	if (format === "ass") {
		const jassubAssets = { ...DEFAULT_ASSETS.jassub, ...assets?.jassub };
		return import("jassub").then(({ default: JASSUB }) => {
			const jassub = new JASSUB({
				video,
				subUrl: subtitle.link,
				workerUrl: jassubAssets.workerUrl,
				wasmUrl: jassubAssets.wasmUrl,
				modernWasmUrl: jassubAssets.modernWasmUrl,
				availableFonts: { "liberation sans": jassubAssets.fontUrl },
				defaultFont: "liberation sans",
			});
			return { destroy: () => jassub.destroy() };
		});
	}

	if (format === "pgs") {
		const pgsAssets = { ...DEFAULT_ASSETS.pgs, ...assets?.pgs };
		return import("libpgs").then(({ PgsRenderer }) => {
			const pgs = new PgsRenderer({
				video,
				subUrl: subtitle.link,
				workerUrl: pgsAssets.workerUrl,
			});
			return { destroy: () => pgs.dispose() };
		});
	}

	return Promise.resolve(null);
};
