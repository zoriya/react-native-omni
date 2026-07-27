"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.OmniView = void 0;
var _react = require("react");
var _reactNativeNitroModules = require("react-native-nitro-modules");
var _OmniViewConfig = _interopRequireDefault(require("../nitrogen/generated/shared/json/OmniViewConfig.json"));
var _provider = require("./provider");
var _jsxRuntime = require("react/jsx-runtime");
function _interopRequireDefault(e) { return e && e.__esModule ? e : { default: e }; }
const NativeView = /*#__PURE__*/(0, _react.memo)((0, _reactNativeNitroModules.getHostComponent)("OmniView", () => _OmniViewConfig.default));
const OmniView = exports.OmniView = /*#__PURE__*/(0, _react.memo)(({
  // Web-only; not forwarded to the native view.
  subtitleAssets: _subtitleAssets,
  ...props
}) => {
  const player = (0, _provider.usePlayer)();
  return /*#__PURE__*/(0, _jsxRuntime.jsx)(NativeView, {
    player: player,
    ...props
  });
});
//# sourceMappingURL=view.js.map