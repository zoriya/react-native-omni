"use strict";

import { HlsJsVideo } from "@videojs/react/media/hlsjs-video";
import { Video } from "@videojs/react/video";
import { useEffect, useMemo, useRef, useSyncExternalStore } from "react";
import { usePlayerState } from "./events";
import { getSubtitleFormat } from "./player.web";
import { usePlayer, VideoPlayer } from "./provider.web";
import { jsx as _jsx, jsxs as _jsxs } from "react/jsx-runtime";
const SubtitleOverlay = ({
  video,
  assets,
  fonts
}) => {
  const player = usePlayer();
  const subtitle = useSyncExternalStore(player.subscribeOverlaySubtitle, player.getOverlaySubtitle, () => null);
  const assetsRef = useRef(assets);
  assetsRef.current = assets;
  const fontsRef = useRef(fonts);
  fontsRef.current = fonts;
  useEffect(() => {
    const el = video.current;
    if (!el || !subtitle) return;
    let renderer = null;
    let cancelled = false;
    const attach = created => {
      if (cancelled) created.destroy();else renderer = created;
    };
    if (getSubtitleFormat(subtitle) === "ass") {
      const jassub = assetsRef.current?.jassub;
      const fonts = fontsRef.current;
      // The video's source resolution (its highest available rendition).
      // jassub uses this as libass' storage size so subtitles stay sharp
      // even while a low-bitrate rendition is playing — otherwise the
      // raster tracks the currently-decoded (possibly tiny) frame size.
      const renditions = player.rendition;
      const videoWidth = Math.max(0, ...renditions.map(r => r.width));
      const videoHeight = Math.max(0, ...renditions.map(r => r.height));
      import("jassub").then(({
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
      import("libpgs").then(({
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
export const OmniView = ({
  style,
  autoplay,
  subtitleAssets
}) => {
  const player = usePlayer();
  const containerRef = useRef(null);
  const ref = useRef(undefined);
  const src = player.source?.src;
  const isHls = src?.mimeType?.toLowerCase().includes("mpegurl") || src?.uri.split(/[?#]/)[0]?.toLowerCase().endsWith(".m3u8") || false;
  const Tech = isHls ? HlsJsVideo : Video;
  const headersRef = useRef(src?.headers);
  headersRef.current = src?.headers;
  const castId = player.source?.castId;
  const castData = player.source?.castData;
  const config = useMemo(() => ({
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
  const castStatus = usePlayerState("castStatus");
  return /*#__PURE__*/_jsxs(VideoPlayer.Container, {
    ref: containerRef,
    style: {
      position: "relative",
      ...style
    },
    children: [src && /*#__PURE__*/_jsx(Tech, {
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
      children: (player.source?.subtitles ?? []).map(subtitle => /*#__PURE__*/_jsx("track", {
        id: subtitle.id,
        kind: "subtitles",
        src: subtitle.link,
        srcLang: subtitle.language,
        label: subtitle.label ?? subtitle.language ?? subtitle.id
      }, subtitle.id))
    }), castStatus !== "connected" && castStatus !== "connecting" && /*#__PURE__*/_jsx(SubtitleOverlay, {
      video: ref,
      assets: subtitleAssets,
      fonts: player.source?.fonts
    })]
  });
};
//# sourceMappingURL=view.web.js.map