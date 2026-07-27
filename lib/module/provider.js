"use strict";

import { createContext, useContext, useEffect } from "react";
import { NitroModules } from "react-native-nitro-modules";
import { useLazyRef } from "./utils/lazy-ref";
import { jsx as _jsx } from "react/jsx-runtime";
const ProviderFactory = NitroModules.createHybridObject("OmniPlayerFactory");
const PlayerCtx = /*#__PURE__*/createContext(null);
export const OmniProvider = ({
  children,
  source,
  backend = {
    android: "vlc"
  },
  showNotification = false,
  cast
}) => {
  const player = useLazyRef(() => ProviderFactory.createPlayer(source, backend, cast));
  useEffect(() => {
    player.source = source;
  }, [source]);
  useEffect(() => {
    player.showNotification = showNotification;
  }, [showNotification]);
  return /*#__PURE__*/_jsx(PlayerCtx.Provider, {
    value: player,
    children: children
  });
};
export const usePlayer = () => {
  return useContext(PlayerCtx);
};
//# sourceMappingURL=provider.js.map