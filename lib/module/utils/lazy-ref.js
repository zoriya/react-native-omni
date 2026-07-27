"use strict";

import { useState } from "react";
export const useLazyRef = init => {
  const [ret] = useState(init);
  return ret;
};
//# sourceMappingURL=lazy-ref.js.map