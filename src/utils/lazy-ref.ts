import { useState } from "react";

export const useLazyRef = <T>(init: () => T): T => {
	const [ret] = useState<T>(init);
	return ret
};
