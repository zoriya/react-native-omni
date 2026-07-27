"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.usePlayer = exports.OmniProvider = void 0;
var _react = require("react");
var _reactNativeNitroModules = require("react-native-nitro-modules");
var _lazyRef = require("./utils/lazy-ref");
var _jsxRuntime = require("react/jsx-runtime");
const ProviderFactory = _reactNativeNitroModules.NitroModules.createHybridObject("OmniPlayerFactory");
const PlayerCtx = /*#__PURE__*/(0, _react.createContext)(null);
const OmniProvider = ({
  children,
  source,
  backend = {
    android: "vlc"
  },
  showNotification = false,
  cast
}) => {
  const player = (0, _lazyRef.useLazyRef)(() => ProviderFactory.createPlayer(source, backend, cast));
  (0, _react.useEffect)(() => {
    player.source = source;
  }, [source]);
  (0, _react.useEffect)(() => {
    player.showNotification = showNotification;
  }, [showNotification]);
  return /*#__PURE__*/(0, _jsxRuntime.jsx)(PlayerCtx.Provider, {
    value: player,
    children: children
  });
};
exports.OmniProvider = OmniProvider;
const usePlayer = () => {
  return (0, _react.useContext)(PlayerCtx);
};
exports.usePlayer = usePlayer;
//# sourceMappingURL=provider.js.map