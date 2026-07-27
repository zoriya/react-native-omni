"use strict";

Object.defineProperty(exports, "__esModule", {
  value: true
});
exports.OmniView = void 0;
var _hlsjsVideo = require("@videojs/react/media/hlsjs-video");
var _video = require("@videojs/react/video");
var _react = require("react");
var _events = require("./events");
var _player = require("./player.web");
var _provider = require("./provider.web");
var _jsxRuntime = require("react/jsx-runtime");
function _interopRequireWildcard(e, t) { if ("function" == typeof WeakMap) var r = new WeakMap(), n = new WeakMap(); return (_interopRequireWildcard = function (e, t) { if (!t && e && e.__esModule) return e; var o, i, f = { __proto__: null, default: e }; if (null === e || "object" != typeof e && "function" != typeof e) return f; if (o = t ? n : r) { if (o.has(e)) return o.get(e); o.set(e, f); } for (const t in e) "default" !== t && {}.hasOwnProperty.call(e, t) && ((i = (o = Object.defineProperty) && Object.getOwnPropertyDescriptor(e, t)) && (i.get || i.set) ? o(f, t, i) : f[t] = e[t]); return f; })(e, t); }
const SubtitleOverlay = ({
  video,
  assets,
  fonts
}) => {
  const player = (0, _provider.usePlayer)();
  const subtitle = (0, _react.useSyncExternalStore)(player.subscribeOverlaySubtitle, player.getOverlaySubtitle, () => null);
  const assetsRef = (0, _react.useRef)(assets);
  assetsRef.current = assets;
  const fontsRef = (0, _react.useRef)(fonts);
  fontsRef.current = fonts;
  (0, _react.useEffect)(() => {
    const el = video.current;
    if (!el || !subtitle) return;
    let renderer = null;
    let cancelled = false;
    const attach = created => {
      if (cancelled) created.destroy();else renderer = created;
    };
    if ((0, _player.getSubtitleFormat)(subtitle) === "ass") {
      const jassub = assetsRef.current?.jassub;
      const fonts = fontsRef.current;
      // The video's source resolution (its highest available rendition).
      // jassub uses this as libass' storage size so subtitles stay sharp
      // even while a low-bitrate rendition is playing — otherwise the
      // raster tracks the currently-decoded (possibly tiny) frame size.
      const renditions = player.rendition;
      const videoWidth = Math.max(0, ...renditions.map(r => r.width));
      const videoHeight = Math.max(0, ...renditions.map(r => r.height));
      Promise.resolve().then(() => _interopRequireWildcard(require("jassub"))).then(({
        default: JASSUB
      }) => {
        const instance = new JASSUB({
          video: el,
          subUrl: subtitle.link,
          ...(videoWidth && {
            videoWidth
          }),
          ...(videoHeight && {
            videoHeight
          }),
          ...(fonts?.length && {
            fonts
          }),
          ...(jassub?.workerUrl && {
            workerUrl: jassub.workerUrl
          }),
          ...(jassub?.wasmUrl && {
            wasmUrl: jassub.wasmUrl
          }),
          ...(jassub?.modernWasmUrl && {
            modernWasmUrl: jassub.modernWasmUrl
          }),
          ...(jassub?.fontUrl && {
            availableFonts: {
              "liberation sans": jassub.fontUrl
            },
            defaultFont: "liberation sans"
          })
        });
        attach({
          destroy: () => instance.destroy()
        });
      }).catch(e => console.error("[omni] failed to render ass subtitle", e));
    } else {
      const pgs = assetsRef.current?.pgs;
      Promise.resolve().then(() => _interopRequireWildcard(require("libpgs"))).then(({
        PgsRenderer
      }) => {
        const instance = new PgsRenderer({
          video: el,
          subUrl: subtitle.link,
          workerUrl: pgs?.workerUrl ?? new URL("libpgs/dist/libpgs.worker.js", import.meta.url).href
        });
        attach({
          destroy: () => instance.dispose()
        });
      }).catch(e => console.error("[omni] failed to render pgs subtitle", e));
    }
    return () => {
      cancelled = true;
      renderer?.destroy();
    };
  }, [video, subtitle, player]);
  return null;
};
const OmniView = ({
  style,
  autoplay,
  subtitleAssets
}) => {
  const player = (0, _provider.usePlayer)();
  const containerRef = (0, _react.useRef)(null);
  const ref = (0, _react.useRef)(undefined);
  const src = player.source?.src;
  const isHls = src?.mimeType?.toLowerCase().includes("mpegurl") || src?.uri.split(/[?#]/)[0]?.toLowerCase().endsWith(".m3u8") || false;
  const Tech = isHls ? _hlsjsVideo.HlsJsVideo : _video.Video;
  const headersRef = (0, _react.useRef)(src?.headers);
  headersRef.current = src?.headers;
  const castId = player.source?.castId;
  const castData = player.source?.castData;
  const config = (0, _react.useMemo)(() => ({
    hlsJs: {
      xhrSetup: xhr => {
        const headers = headersRef.current;
        if (!headers) return;
        for (const [key, value] of Object.entries(headers)) {
          if (value) xhr.setRequestHeader(key, value);
        }
      }
    },
    googleCast: {
      receiver: player.castOptions?.receiverApplicationId,
      customData: castData ?? null,
      ...(castId && {
        src: castId
      })
    }
  }), [player.castOptions, castData, castId]);

  // While casting, the receiver renders subtitles (the player forwards the
  // selection); skip rendering them locally on the idle <video>.
  const castStatus = (0, _events.usePlayerState)("castStatus");
  return /*#__PURE__*/(0, _jsxRuntime.jsxs)(_provider.VideoPlayer.Container, {
    ref: containerRef,
    style: {
      position: "relative",
      ...style
    },
    children: [src && /*#__PURE__*/(0, _jsxRuntime.jsx)(Tech, {
      ref: ref,
      src: src.uri,
      config: config,
      autoPlay: autoplay,
      playsInline: true,
      crossOrigin: "anonymous",
      style: {
        width: "100%",
        height: "100%",
        objectFit: "contain"
      },
      children: (player.source?.subtitles ?? []).map(subtitle => /*#__PURE__*/(0, _jsxRuntime.jsx)("track", {
        id: subtitle.id,
        kind: "subtitles",
        src: subtitle.link,
        srcLang: subtitle.language,
        label: subtitle.label ?? subtitle.language ?? subtitle.id
      }, subtitle.id))
    }), castStatus !== "connected" && castStatus !== "connecting" && /*#__PURE__*/(0, _jsxRuntime.jsx)(SubtitleOverlay, {
      video: ref,
      assets: subtitleAssets,
      fonts: player.source?.fonts
    })]
  });
};
exports.OmniView = OmniView;
//# sourceMappingURL=view.web.js.map