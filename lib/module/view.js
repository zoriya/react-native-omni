"use strict";

import { memo } from "react";
import { getHostComponent } from "react-native-nitro-modules";
import OmniConfig from "../nitrogen/generated/shared/json/OmniViewConfig.json";
import { usePlayer } from "./provider";
import { jsx as _jsx } from "react/jsx-runtime";
const NativeView = /*#__PURE__*/memo(getHostComponent("OmniView", () => OmniConfig));
export const OmniView = /*#__PURE__*/memo(({
  // Web-only; not forwarded to the native view.
  subtitleAssets: _subtitleAssets,
  ...props
}) => {
  const player = usePlayer();
  return /*#__PURE__*/_jsx(NativeView, {
    player: player,
    ...props
  });
});
//# sourceMappingURL=view.js.map