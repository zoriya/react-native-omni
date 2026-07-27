"use strict";

import { createPlayer } from "@videojs/react";
import { videoFeatures } from "@videojs/react/video";
import { createContext, useContext, useEffect, useRef } from "react";
import { WebOmniPlayer } from "./player.web";
import { useLazyRef } from "./utils/lazy-ref";
import { jsx as _jsx } from "react/jsx-runtime";
export const VideoPlayer = createPlayer({
  features: videoFeatures
});
const PlayerCtx = /*#__PURE__*/createContext(null);
export const OmniProvider = ({
  children,
  source,
  cast,
  // Web always uses video.js; accepted for API parity with native.
  backend: _backend,
  showNotification = false
}) => {
  return /*#__PURE__*/_jsx(VideoPlayer.Provider, {
    children: /*#__PURE__*/_jsx(PlayerInitializer, {
      source: source,
      cast: cast,
      showNotification: showNotification,
      children: children
    })
  });
};
const PlayerInitializer = ({
  children,
  source,
  cast,
  showNotification
}) => {
  const store = VideoPlayer.usePlayer();
  const player = useLazyRef(() => new WebOmniPlayer(store));
  const seekedForSrc = useRef(undefined);
  useEffect(() => {
    player.source = source;
    const uri = source?.src?.uri;
    if (uri !== seekedForSrc.current) {
      seekedForSrc.current = uri;
      if (source?.startTime) store.seek(source.startTime);
    }
  }, [source, store]);
  useEffect(() => {
    player.castOptions = cast ?? null;
  }, [cast]);
  useEffect(() => {
    player.showNotification = showNotification;
  }, [showNotification]);
  return /*#__PURE__*/_jsx(PlayerCtx.Provider, {
    value: player,
    children: children
  });
};
export const usePlayer = () => useContext(PlayerCtx);
//# sourceMappingURL=provider.web.js.map