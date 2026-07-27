"use strict";

import { useEffect, useState } from "react";
import { usePlayer } from "./provider";
function capitalize(str) {
  return str.charAt(0).toUpperCase() + str.slice(1);
}
export const useEvent = (event, callback) => {
  const player = usePlayer();
  useEffect(() => {
    player.eventMap[`addOn${capitalize(event)}Listener`](callback);
    return () => player.eventMap[`removeOn${capitalize(event)}Listener`](callback);
  }, [player, event, callback]);
};
export function usePlayerState(key, refresh) {
  const player = usePlayer();
  const [ret, setState] = useState(player[key]);
  useEffect(() => {
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
  useEffect(() => {
    if (!refresh || refresh <= 0) return;
    const int = setInterval(() => {
      setState(player[key]);
    }, refresh * 1000);
    return () => clearInterval(int);
  }, [refresh, key, player]);
  return ret;
}
//# sourceMappingURL=events.js.map