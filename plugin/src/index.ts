import type { ConfigPlugin } from "@expo/config-plugins";
import { createRunOncePlugin } from "@expo/config-plugins";
import pkg from "../../package.json";
import { withCast } from "./withCast";
import { withMediaNotifications } from "./withMediaNotifications";
import { withPip } from "./withPip";

const withOmni: ConfigPlugin = (config) => {
	return withCast(withMediaNotifications(withPip(config)));
};

export default createRunOncePlugin(withOmni, pkg.name, pkg.version);

export { withCast, withMediaNotifications, withPip };
