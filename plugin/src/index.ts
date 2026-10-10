import type { ConfigPlugin } from "@expo/config-plugins";
import { createRunOncePlugin } from "@expo/config-plugins";
import pkg from "../../package.json";
import { withCast } from "./withCast";
import { withCastReceiver } from "./withCastReceiver";
import { withMediaNotifications } from "./withMediaNotifications";
import { withPip } from "./withPip";

type OmniPluginProps =
	| {
			// receive casts on android tv (cast connect)
			castReceiver?: boolean;
	  }
	| undefined;

const withOmni: ConfigPlugin<OmniPluginProps> = (config, props) => {
	config = withCast(withMediaNotifications(withPip(config)));
	if (props?.castReceiver) config = withCastReceiver(config);
	return config;
};

export default createRunOncePlugin(withOmni, pkg.name, pkg.version);

export { withCast, withCastReceiver, withMediaNotifications, withPip };
