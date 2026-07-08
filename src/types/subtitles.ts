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
