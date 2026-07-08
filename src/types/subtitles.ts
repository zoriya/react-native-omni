/**
 * URLs to the web subtitle renderer assets (jassub for ASS/SSA, libpgs for
 * PGS). The consuming app must host these; each field falls back to a default
 * under `/jassub` and `/pgs` when omitted. Web-only — ignored on native.
 */
export interface SubtitleAssets {
	jassub?: {
		/** URL of `jassub-worker.js`. */
		workerUrl?: string;
		/** URL of `jassub-worker.wasm`. */
		wasmUrl?: string;
		/** URL of `jassub-worker-modern.wasm`. */
		modernWasmUrl?: string;
		/** URL of a fallback font used when the ASS file references none. */
		fontUrl?: string;
	};
	pgs?: {
		/** URL of `libpgs.worker.js`. */
		workerUrl?: string;
	};
}
