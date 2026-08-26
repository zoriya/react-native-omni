const path = require("node:path");
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
			// react/react-dom are hoisted to the workspace root, not example/node_modules
			react: path.dirname(require.resolve("react/package.json")),
			"react-dom": path.dirname(require.resolve("react-dom/package.json")),
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
					// presets/plugins come from example/babel.config.js
					// (@react-native/babel-preset). Stacking @babel/preset-env on top of
					// it enables class-properties twice with conflicting `loose` values,
					// which babel warns about once per file.
					options: { cacheDirectory: true },
				},
			},
			{
				test: /\.(png|jpe?g|gif|svg)$/i,
				type: "asset/resource",
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
		allowedHosts: "all",
		// jassub's wasm is multithreaded (pthreads), which needs SharedArrayBuffer
		// and therefore a cross-origin-isolated page. `credentialless` keeps
		// cross-origin video/subtitle/font requests working without CORP headers.
		headers: {
			"Cross-Origin-Opener-Policy": "same-origin",
			"Cross-Origin-Embedder-Policy": "credentialless",
		},
		static: [{ directory: path.resolve(appDirectory, "public") }],
	},
};
