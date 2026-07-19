export interface JassubAssets {
	/** URL of `jassub-worker.js`. */
	workerUrl?: string;
	/** URL of `jassub-worker.wasm`. */
	wasmUrl?: string;
	/** URL of `jassub-worker-modern.wasm`. */
	modernWasmUrl?: string;
	/** URL of a fallback font used when the ASS file references none. */
	fontUrl?: string;
}

export interface PgsAssets {
	/** URL of `libpgs.worker.js`. */
	workerUrl?: string;
}

export interface SubtitleAssets {
	jassub?: JassubAssets;
	pgs?: PgsAssets;
}
