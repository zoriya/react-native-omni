"use strict";
var __importDefault = (this && this.__importDefault) || function (mod) {
    return (mod && mod.__esModule) ? mod : { "default": mod };
};
Object.defineProperty(exports, "__esModule", { value: true });
exports.withPip = exports.withMediaNotifications = exports.withCast = void 0;
const config_plugins_1 = require("@expo/config-plugins");
const package_json_1 = __importDefault(require("../../package.json"));
const withCast_1 = require("./withCast");
Object.defineProperty(exports, "withCast", { enumerable: true, get: function () { return withCast_1.withCast; } });
const withMediaNotifications_1 = require("./withMediaNotifications");
Object.defineProperty(exports, "withMediaNotifications", { enumerable: true, get: function () { return withMediaNotifications_1.withMediaNotifications; } });
const withPip_1 = require("./withPip");
Object.defineProperty(exports, "withPip", { enumerable: true, get: function () { return withPip_1.withPip; } });
const withOmni = (config) => {
    return (0, withCast_1.withCast)((0, withMediaNotifications_1.withMediaNotifications)((0, withPip_1.withPip)(config)));
};
exports.default = (0, config_plugins_1.createRunOncePlugin)(withOmni, package_json_1.default.name, package_json_1.default.version);
