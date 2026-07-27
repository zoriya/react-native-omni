"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.usePlayer = exports.VideoPlayer = exports.OmniProvider = void 0;
var _react = require("@videojs/react");
var _video = require("@videojs/react/video");
var _react2 = require("react");
var _player = require("./player.web");
var _lazyRef = require("./utils/lazy-ref");
var _jsxRuntime = require("react/jsx-runtime");
const VideoPlayer = exports.VideoPlayer = (0, _react.createPlayer)({
  features: _video.videoFeatures
});
const PlayerCtx = /*#__PURE__*/(0, _react2.createContext)(null);
const OmniProvider = ({
  children,
  source,
  cast,
  // Web always uses video.js; accepted for API parity with native.
  backend: _backend,
  showNotification = false
}) => {
  return /*#__PURE__*/(0, _jsxRuntime.jsx)(VideoPlayer.Provider, {
    children: /*#__PURE__*/(0, _jsxRuntime.jsx)(PlayerInitializer, {
      source: source,
      cast: cast,
      showNotification: showNotification,
      children: children
    })
  });
};
exports.OmniProvider = OmniProvider;
const PlayerInitializer = ({
  children,
  source,
  cast,
  showNotification
}) => {
  const store = VideoPlayer.usePlayer();
  const player = (0, _lazyRef.useLazyRef)(() => new _player.WebOmniPlayer(store));
  const seekedForSrc = (0, _react2.useRef)(undefined);
  (0, _react2.useEffect)(() => {
    player.source = source;
    const uri = source?.src?.uri;
    if (uri !== seekedForSrc.current) {
      seekedForSrc.current = uri;
      if (source?.startTime) store.seek(source.startTime);
    }
  }, [source, store]);
  (0, _react2.useEffect)(() => {
    player.castOptions = cast ?? null;
  }, [cast]);
  (0, _react2.useEffect)(() => {
    player.showNotification = showNotification;
  }, [showNotification]);
  return /*#__PURE__*/(0, _jsxRuntime.jsx)(PlayerCtx.Provider, {
    value: player,
    children: children
  });
};
const usePlayer = () => (0, _react2.useContext)(PlayerCtx);
exports.usePlayer = usePlayer;
//# sourceMappingURL=provider.web.js.map