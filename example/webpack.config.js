const path = require("path");
const HtmlWebpackPlugin = require("html-webpack-plugin");

const appDirectory = path.resolve(__dirname);
const rootDirectory = path.resolve(__dirname, "..");

module.exports = {
	mode: "development",
	devtool: "eval-source-map",
	entry: path.resolve(__dirname, "index.web.js"),
	output: {
		path: path.resolve(__dirname, "dist"),
		filename: "bundle.web.js",
		publicPath: "/",
	},
	resolve: {
		extensions: [".web.tsx", ".web.ts", ".tsx", ".ts", ".web.js", ".js"],
		alias: {
			"react-native$": "react-native-web",
			"react-native-omni": path.resolve(rootDirectory, "src"),
			react: path.resolve(appDirectory, "node_modules", "react"),
			"react-dom": path.resolve(appDirectory, "node_modules", "react-dom"),
		},
	},
	module: {
		rules: [
			{
				test: /\.(js|ts|tsx)$/,
				exclude:
					/node_modules\/(?!(react-native|@react-native|react-native-web|react-native-omni)\/).*/,
				use: {
					loader: "babel-loader",
					options: {
						presets: [
							"@babel/preset-env",
							"@babel/preset-react",
							"@babel/preset-typescript",
						],
						plugins: [
							["@babel/plugin-transform-react-jsx", { runtime: "automatic" }],
							[
								"module-resolver",
								{
									extensions: [".js", ".ts", ".json", ".jsx", ".tsx"],
									alias: {
										"react-native-omni": path.resolve(rootDirectory, "src"),
									},
								},
							],
						],
					},
				},
			},
			{
				test: /\.(png|jpe?g|gif|svg)$/i,
				type: "asset/resource",
			},
			{
				// Disable webpack's `new URL(..., import.meta.url)` asset emission for
				// jassub. jassub references a `./default.woff2` it does not ship (which
				// would fail the build) and its wasm URLs; we self-host the wasm + font
				// and pass them via `subtitleAssets` instead. The worker itself is still
				// bundled (that goes through `new Worker(new URL(...))`, not this rule).
				test: /[\\/]jassub[\\/]/,
				parser: { url: false },
			},
		],
	},
	plugins: [
		new HtmlWebpackPlugin({
			template: path.resolve(__dirname, "public/index.html"),
		}),
	],
	devServer: {
		port: 3000,
		hot: true,
		historyApiFallback: true,
		// jassub's wasm is multithreaded (pthreads), which needs SharedArrayBuffer
		// and therefore a cross-origin-isolated page. `credentialless` keeps
		// cross-origin video/subtitle/font requests working without CORP headers.
		headers: {
			"Cross-Origin-Opener-Policy": "same-origin",
			"Cross-Origin-Embedder-Policy": "credentialless",
		},
		// Host the ASS/PGS subtitle renderer assets under `/jassub` and `/pgs`.
		// jassub/libpgs ship the worker + wasm; `public/jassub/default.woff2`
		// provides the fallback font jassub does not bundle.
		static: [
			{ directory: path.resolve(appDirectory, "public") },
			{
				directory: path.resolve(rootDirectory, "node_modules/jassub/dist/wasm"),
				publicPath: "/jassub",
			},
			{
				directory: path.resolve(rootDirectory, "node_modules/libpgs/dist"),
				publicPath: "/pgs",
			},
		],
	},
};
