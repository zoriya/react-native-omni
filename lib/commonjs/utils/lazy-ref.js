"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.useLazyRef = void 0;
var _react = require("react");
const useLazyRef = init => {
  const [ret] = (0, _react.useState)(init);
  return ret;
};
exports.useLazyRef = useLazyRef;
//# sourceMappingURL=lazy-ref.js.map