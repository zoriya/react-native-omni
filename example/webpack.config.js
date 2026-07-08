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
        exclude: /node_modules\/(?!(react-native|@react-native|react-native-web|react-native-omni)\/).*/,
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
        // jassub references its bundled `./default.woff2` via
        // `new URL(..., import.meta.url)` in a branch that only runs when no
        // `defaultFont` is provided. We always pass one, so disable webpack's
        // eager URL-asset parsing for jassub to avoid resolving a file it does
        // not ship.
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
  },
};
