"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.useEvent = void 0;
exports.usePlayerState = usePlayerState;
var _react = require("react");
var _provider = require("./provider");
function capitalize(str) {
  return str.charAt(0).toUpperCase() + str.slice(1);
}
const useEvent = (event, callback) => {
  const player = (0, _provider.usePlayer)();
  (0, _react.useEffect)(() => {
    player.eventMap[`addOn${capitalize(event)}Listener`](callback);
    return () => player.eventMap[`removeOn${capitalize(event)}Listener`](callback);
  }, [player, event, callback]);
};
exports.useEvent = useEvent;
function usePlayerState(key, refresh) {
  const player = (0, _provider.usePlayer)();
  const [ret, setState] = (0, _react.useState)(player[key]);
  (0, _react.useEffect)(() => {
    const em = player.eventMap;
    switch (key) {
      case "currentTime":
      case "buffered":
      case "duration":
      case "playbackRate":
      case "volume":
        em.addStateListener(key, setState);
        return () => em.removeStateListener(key, setState);
      case "isPlaying":
      case "muted":
      case "isAutoQuality":
        em.addStateBoolListener(key, setState);
        return () => em.removeStateBoolListener(key, setState);
      case "status":
        em.addPlayerStatusListener(setState);
        return () => em.removePlayerStatusListener(setState);
      case "castStatus":
        em.addCastStatusListener(setState);
        return () => em.removeCastStatusListener(setState);
    }
  }, [player, key]);
  if (key === "currentTime") refresh ??= 1;
  (0, _react.useEffect)(() => {
    if (!refresh || refresh <= 0) return;
    const int = setInterval(() => {
      setState(player[key]);
    }, refresh * 1000);
    return () => clearInterval(int);
  }, [refresh, key, player]);
  return ret;
}
//# sourceMappingURL=events.js.map