import type React from "react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
	ActivityIndicator,
	Animated,
	PanResponder,
	Platform,
	Pressable,
	ScrollView,
	StatusBar,
	StyleSheet,
	Text,
	View,
} from "react-native";
import {
	type AndroidBackend,
	OmniProvider,
	OmniView,
	useEvent,
	usePlayer,
	usePlayerState,
} from "react-native-omni";

const PLAYLIST = [
	// {
	// 	title:"tset",
	// 	uri:"http://fuhen.local:8901/api/videos/bubble/master.m3u8?clientId=ea9dfc63-817b-4b99-bd8a-f32d50cf68c9",
	// },
	{
		title: "elephants dram",
		artist: "multi audio",
		album: "Adaptive",
		uri: "https://playertest.longtailvideo.com/adaptive/elephants_dream_v4/index.m3u8",
		imageLink: undefined,
	},
	{
		title: "Big Buck Bunny (HLS)",
		artist: "Blender Foundation",
		album: "Open Movie Project",
		artwork:
			"https://peach.blender.org/wp-content/uploads/title_anouncement.jpg",
		uri: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
		imageLink: undefined,
	},
	{
		title: "Sintel Trailer (MP4)",
		artist: "Blender Foundation",
		album: "Sintel",
		artwork:
			"https://download.blender.org/durian/trailer/sintel_trailer-480p.jpg",
		uri: "https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4",
		imageLink: undefined,
	},
] as const;

const SUBTITLES = [
	{
		id: "styletest",
		link: "data:text/vtt;base64,V0VCVlRUCgowMDowMDowMC4wMDAgLS0+IDAwOjEwOjAwLjAwMApTdHlsZSB0ZXN0IOKAlCB3aGl0ZSB0ZXh0LCBibGFjayBvdXRsaW5lLCBubyBib3gsIGFuZCBhIGxpbmUgbG9uZyBlbm91Z2ggdG8gd3JhcCBzbyB0aGUgbWFyZ2lucyBzaG93Lgo=",
		mimeType: "text/vtt",
		label: "style test",
		language: "en",
	},
	{
		id: "kusu",
		link: "https://jassub.pages.dev/subtitles/Kusriya%20S2%20OP1v3.ass",
		label: "ass test",
		language: "jp",
	},
	{
		id: "pgs",
		link: "https://raw.githubusercontent.com/Arcus92/libpgs-js/main/tests/files/test.sup",
		label: "PGS test",
	},
	{"id":"0","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/0.srt?format=vtt", "label":"Japanese (SDH)","language":"ja"},
	{"id":"1","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/1.srt?format=vtt", "label":"German (Forced)","language":"de"},
	{"id":"2","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/2.srt?format=vtt", "label":"English (Forced)","language":"en"},
	{"id":"3","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/3.srt?format=vtt", "label":"Spanish (Latin America) (Forced)","language":"es"},
	{"id":"4","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/4.srt?format=vtt", "label":"Spanish (Spain) (Forced)","language":"es"},
	{"id":"5","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/5.srt?format=vtt", "label":"French (Forced)","language":"fr"},
	{"id":"6","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/6.srt?format=vtt", "label":"Italian (Forced)","language":"it"},
	{"id":"7","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/7.srt?format=vtt", "label":"Korean (Forced)","language":"ko"},
	{"id":"8","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/8.srt?format=vtt", "label":"Polish (Forced)","language":"pl"},
	{"id":"9","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/9.srt?format=vtt", "label":"Portuguese (Brazil) (Forced)","language":"pt"},
	{"id":"10","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/10.srt?format=vtt", "label":"Thai (Forced)","language":"th"},
	{"id":"11","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/11.srt?format=vtt", "label":"Chinese (Traditional) (Forced)","language":"zh"},
	{"id":"12","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/12.srt?format=vtt", "label":"Arabic","language":"ar"},
	{"id":"13","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/13.srt?format=vtt", "label":"Czech","language":"cs"},
	{"id":"14","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/14.srt?format=vtt", "label":"Danish","language":"da"},
	{"id":"15","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/15.srt?format=vtt", "label":"German","language":"de"},
	{"id":"16","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/16.srt?format=vtt", "label":"Greek","language":"el"},
	{"id":"17","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/17.srt?format=vtt", "label":"English","language":"en"},
	{"id":"18","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/18.srt?format=vtt", "label":"English","language":"en"},
	{"id":"19","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/19.srt?format=vtt", "label":"English (SDH)","language":"en"},
	{"id":"20","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/20.srt?format=vtt", "label":"Spanish (Latin America)","language":"es"},
	{"id":"21","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/21.srt?format=vtt", "label":"Spanish (Spain)","language":"es"},
	{"id":"22","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/22.srt?format=vtt", "label":"Finnish","language":"fi"},
	{"id":"23","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/23.srt?format=vtt", "label":"French","language":"fr"},
	{"id":"24","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/24.srt?format=vtt", "label":"Hebrew","language":"he"},
	{"id":"25","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/25.srt?format=vtt", "label":"Croatian","language":"hr"},
	{"id":"26","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/26.srt?format=vtt", "label":"Hungarian","language":"hu"},
	{"id":"27","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/27.srt?format=vtt", "label":"Indonesian","language":"id"},
	{"id":"28","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/28.srt?format=vtt", "label":"Italian","language":"it"},
	{"id":"29","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/29.srt?format=vtt", "label":"Korean","language":"ko"},
	{"id":"30","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/30.srt?format=vtt", "label":"Malay","language":"ms"},
	{"id":"31","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/31.srt?format=vtt", "label":"Norwegian Bokmål","language":"nb"},
	{"id":"32","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/32.srt?format=vtt", "label":"Dutch","language":"nl"},
	{"id":"33","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/33.srt?format=vtt", "label":"Polish","language":"pl"},
	{"id":"34","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/34.srt?format=vtt", "label":"Portuguese (Brazil)","language":"pt"},
	{"id":"35","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/35.srt?format=vtt", "label":"Portuguese (Portugal)","language":"pt"},
	{"id":"36","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/36.srt?format=vtt", "label":"Romanian","language":"ro"},
	{"id":"37","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/37.srt?format=vtt", "label":"Russian","language":"ru"},
	{"id":"38","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/38.srt?format=vtt", "label":"Swedish","language":"sv"},
	{"id":"39","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/39.srt?format=vtt", "label":"Thai","language":"th"},
	{"id":"40","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/40.srt?format=vtt", "label":"Turkish","language":"tr"},
	{"id":"41","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/41.srt?format=vtt", "label":"Ukrainian","language":"uk"},
	{"id":"42","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/42.srt?format=vtt", "label":"Vietnamese","language":"vi"},
	{"id":"43","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/43.srt?format=vtt", "label":"Chinese (Simplified)","language":"zh"},
	{"id":"44","link":"http://fuhen.local:8901/video/L3ZpZGVvL0J1YmJsZSAoMjAyMikubWt2/subtitle/44.srt?format=vtt", "label":"Chinese (Traditional)","language":"zh"}
];

const START_TIMES = [0, 30, 60, 300, 600];
const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2];
const VOLUMES = [0, 0.25, 0.5, 0.75, 1];
const SEEK_STEP = 10;
// a second tap on the same side within this delay seeks instead of toggling the ui
const DOUBLE_TAP_DELAY = 300;

function formatTime(seconds: number): string {
	if (!Number.isFinite(seconds) || seconds < 0) {
		return "00:00";
	}

	const total = Math.floor(seconds);
	const hours = Math.floor(total / 3600);
	const mins = Math.floor((total % 3600) / 60)
		.toString()
		.padStart(2, "0");
	const secs = (total % 60).toString().padStart(2, "0");
	return hours > 0 ? `${hours}:${mins}:${secs}` : `${mins}:${secs}`;
}

type Panel =
	| "none"
	| "video"
	| "audio"
	| "subtitles"
	| "quality"
	| "speed"
	| "volume";

function Player({ onBack }: { onBack: () => void }): React.JSX.Element {
	const player = usePlayer();
	const status = usePlayerState("status");
	const isPlaying = usePlayerState("isPlaying");
	// refresh twice a second so the progress bar doesn't look choppy
	const currentTime = usePlayerState("currentTime", 0.5);
	const buffered = usePlayerState("buffered");
	const duration = usePlayerState("duration");
	const playbackRate = usePlayerState("playbackRate");
	const muted = usePlayerState("muted");
	const volume = usePlayerState("volume");
	const isAutoQuality = usePlayerState("isAutoQuality");
	const castStatus = usePlayerState("castStatus");
	const source = usePlayerState("source");
	const videos = usePlayerState("videos");
	const audios = usePlayerState("audios");
	const subtitles = usePlayerState("subtitles");
	const renditions = usePlayerState("renditions");

	// the ui never auto-hides, you have to tap once to toggle it
	const [visible, setVisible] = useState(true);
	const [panel, setPanel] = useState<Panel>("none");
	const [hint, setHint] = useState<{
		side: "left" | "right";
		amount: number;
	} | null>(null);
	const [scrub, setScrub] = useState<number | null>(null);

	const fade = useRef(new Animated.Value(1)).current;
	useEffect(() => {
		Animated.timing(fade, {
			toValue: visible ? 1 : 0,
			duration: 180,
			// react-native-web has no RCTAnimation module, so the native driver warns
			useNativeDriver: Platform.OS !== "web",
		}).start();
	}, [visible, fade]);

	const tap = useRef({
		side: "",
		at: 0,
		count: 0,
		timer: null as ReturnType<typeof setTimeout> | null,
	});
	const hintTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
	useEffect(
		() => () => {
			if (tap.current.timer) clearTimeout(tap.current.timer);
			if (hintTimer.current) clearTimeout(hintTimer.current);
		},
		[],
	);

	const onTapZone = (side: "left" | "right") => {
		const state = tap.current;
		const now = Date.now();
		if (state.side === side && now - state.at < DOUBLE_TAP_DELAY) {
			if (state.timer) clearTimeout(state.timer);
			state.timer = null;
			state.at = now;
			state.count += 1;
			player.seekBy(side === "left" ? -SEEK_STEP : SEEK_STEP);
			setHint({ side, amount: state.count * SEEK_STEP });
			if (hintTimer.current) clearTimeout(hintTimer.current);
			hintTimer.current = setTimeout(() => {
				setHint(null);
				tap.current.side = "";
				tap.current.count = 0;
			}, 800);
			return;
		}

		state.side = side;
		state.at = now;
		state.count = 0;
		if (state.timer) clearTimeout(state.timer);
		// single tap: wait for a possible second tap before toggling the ui
		state.timer = setTimeout(() => {
			state.timer = null;
			state.side = "";
			setVisible((v) => !v);
		}, DOUBLE_TAP_DELAY);
	};

	const barOrigin = useRef(0);
	const barWidth = useRef(0);
	const scrubbed = useRef(0);
	const durationRef = useRef(duration);
	durationRef.current = duration;

	const pan = useMemo(
		() =>
			PanResponder.create({
				onStartShouldSetPanResponder: () => true,
				onMoveShouldSetPanResponder: () => true,
				onPanResponderGrant: (e) => {
					barOrigin.current = e.nativeEvent.pageX - e.nativeEvent.locationX;
					const ratio = Math.max(
						0,
						Math.min(1, e.nativeEvent.locationX / (barWidth.current || 1)),
					);
					scrubbed.current = ratio * durationRef.current;
					setScrub(scrubbed.current);
				},
				onPanResponderMove: (_e, gesture) => {
					const x = gesture.moveX - barOrigin.current;
					const ratio = Math.max(0, Math.min(1, x / (barWidth.current || 1)));
					scrubbed.current = ratio * durationRef.current;
					setScrub(scrubbed.current);
				},
				onPanResponderRelease: () => {
					player.currentTime = scrubbed.current;
					setScrub(null);
				},
				onPanResponderTerminate: () => setScrub(null),
			}),
		[player],
	);

	const shownTime = scrub ?? currentTime;
	const progress = duration > 0 ? Math.min(1, shownTime / duration) * 100 : 0;
	const bufferedProgress =
		duration > 0 ? Math.min(1, buffered / duration) * 100 : 0;
	const selectedRendition = renditions.find((rendition) => rendition.selected);
	const activeSubtitle = subtitles.find((subtitle) => subtitle.selected);

	const option = (
		key: string,
		label: string,
		selected: boolean,
		onPress: () => void,
	) => (
		<Pressable
			key={key}
			style={[styles.option, selected && styles.optionSelected]}
			onPress={() => {
				onPress();
				setPanel("none");
			}}
		>
			<Text style={[styles.optionText, selected && styles.optionTextSelected]}>
				{label}
			</Text>
		</Pressable>
	);

	return (
		<View style={styles.playerRoot}>
			<StatusBar hidden />
			<OmniView
				style={styles.video}
				autoplay={true}
				subtitleAssets={{
					jassub: {
						fontUrl: "/jassub/default.woff2",
					},
				}}
			/>

			{/* tap zones sit under the controls (which are `box-none`, so blank
			    areas fall through to here) */}
			<View style={styles.tapZones}>
				<Pressable style={styles.tapZone} onPress={() => onTapZone("left")} />
				<Pressable style={styles.tapZone} onPress={() => onTapZone("right")} />
			</View>

			<Animated.View
				style={[
					styles.controls,
					{ opacity: fade, pointerEvents: visible ? "box-none" : "none" },
				]}
			>
				<View style={styles.scrim} />

				<View style={styles.topBar}>
					{/* unmounts the OmniView but keeps the provider (and playback) alive */}
					<Pressable style={styles.iconButton} onPress={onBack} hitSlop={8}>
						<Text style={styles.iconText}>←</Text>
					</Pressable>
					<View style={styles.topTitles}>
						<Text style={styles.topTitle} numberOfLines={1}>
							{source?.metadata?.title ?? "No media"}
						</Text>
						<Text style={styles.topSubtitle} numberOfLines={1}>
							{source?.metadata?.artist ?? status}
						</Text>
					</View>
					{castStatus !== "unsupported" && (
						<Pressable
							style={[
								styles.pill,
								(castStatus === "connected" || castStatus === "connecting") &&
									styles.pillActive,
							]}
							onPress={() => player.toggleCastStatus()}
						>
							<Text style={styles.pillText}>
								{castStatus === "connected"
									? "Casting"
									: castStatus === "connecting"
										? "Connecting"
										: "Cast"}
							</Text>
						</Pressable>
					)}
				</View>

				<View style={styles.centerRow}>
					<Pressable
						style={styles.roundButton}
						onPress={() => player.playPrev()}
					>
						<Text style={styles.roundText}>◀◀</Text>
					</Pressable>
					<Pressable
						style={[styles.roundButton, styles.playButton]}
						onPress={() => {
							if (isPlaying) player.pause();
							else player.play();
						}}
					>
						<Text style={styles.playText}>{isPlaying ? "▮▮" : "▶"}</Text>
					</Pressable>
					<Pressable
						style={styles.roundButton}
						onPress={() => player.playNext()}
					>
						<Text style={styles.roundText}>▶▶</Text>
					</Pressable>
				</View>

				<View style={styles.bottomBar}>
					<View style={styles.timeRow}>
						<Text style={styles.timeText}>{formatTime(shownTime)}</Text>
						<Text style={styles.timeText}>{formatTime(duration)}</Text>
					</View>

					<View
						style={styles.progressHitbox}
						onLayout={(e) => {
							barWidth.current = e.nativeEvent.layout.width;
						}}
						{...pan.panHandlers}
					>
						<View style={styles.progressTrack}>
							<View
								style={[
									styles.progressBuffered,
									{ width: `${bufferedProgress}%` },
								]}
							/>
							<View
								style={[styles.progressFill, { width: `${progress}%` }]}
							/>
						</View>
						<View
							style={[
								styles.progressThumb,
								{ left: `${progress}%` },
								scrub !== null && styles.progressThumbActive,
							]}
						/>
					</View>

					<View style={styles.actionRow}>
						<Pressable style={styles.pill} onPress={() => setPanel("speed")}>
							<Text style={styles.pillText}>{playbackRate.toFixed(2)}x</Text>
						</Pressable>
						<Pressable
							style={[styles.pill, muted && styles.pillActive]}
							onPress={() => setPanel("volume")}
						>
							<Text style={styles.pillText}>
								{muted ? "Muted" : `Vol ${Math.round(volume * 100)}%`}
							</Text>
						</Pressable>
						<Pressable style={styles.pill} onPress={() => setPanel("audio")}>
							<Text style={styles.pillText}>Audio ({audios.length})</Text>
						</Pressable>
						<Pressable
							style={[styles.pill, activeSubtitle && styles.pillActive]}
							onPress={() => setPanel("subtitles")}
						>
							<Text style={styles.pillText}>
								{activeSubtitle
									? (activeSubtitle.label ?? activeSubtitle.language ?? "Subs")
									: "Subs off"}
							</Text>
						</Pressable>
						<Pressable style={styles.pill} onPress={() => setPanel("quality")}>
							<Text style={styles.pillText}>
								{isAutoQuality
									? `Auto${selectedRendition ? ` ${selectedRendition.height}p` : ""}`
									: `${selectedRendition?.height ?? "?"}p`}
							</Text>
						</Pressable>
						{videos.length > 1 && (
							<Pressable style={styles.pill} onPress={() => setPanel("video")}>
								<Text style={styles.pillText}>Video ({videos.length})</Text>
							</Pressable>
						)}
					</View>
				</View>
			</Animated.View>

			{hint && (
				<View style={[styles.tapZones, { pointerEvents: "none" }]}>
					<View style={styles.tapZone}>
						{hint.side === "left" && (
							<View style={styles.hintBubble}>
								<Text style={styles.hintText}>-{hint.amount}s</Text>
							</View>
						)}
					</View>
					<View style={styles.tapZone}>
						{hint.side === "right" && (
							<View style={styles.hintBubble}>
								<Text style={styles.hintText}>+{hint.amount}s</Text>
							</View>
						)}
					</View>
				</View>
			)}

			{panel !== "none" && (
				<>
					<Pressable
						style={styles.sheetBackdrop}
						onPress={() => setPanel("none")}
					/>
					<View style={styles.sheet}>
						<View style={styles.sheetHeader}>
							<Text style={styles.sheetTitle}>
								{panel === "subtitles"
									? "Subtitles"
									: panel[0].toUpperCase() + panel.slice(1)}
							</Text>
							<Pressable onPress={() => setPanel("none")} hitSlop={10}>
								<Text style={styles.sheetClose}>✕</Text>
							</Pressable>
						</View>
						<ScrollView contentContainerStyle={styles.sheetContent}>
							{panel === "speed" &&
								RATES.map((rate) =>
									option(
										`rate-${rate}`,
										`${rate.toFixed(2)}x`,
										rate === playbackRate,
										() => {
											player.playbackRate = rate;
										},
									),
								)}
							{panel === "volume" && (
								<>
									{option("mute", muted ? "Unmute" : "Mute", muted, () => {
										player.muted = !muted;
									})}
									{VOLUMES.map((level) =>
										option(
											`vol-${level}`,
											`${Math.round(level * 100)}%`,
											!muted && Math.abs(volume - level) < 0.01,
											() => {
												player.muted = false;
												player.volume = level;
											},
										),
									)}
								</>
							)}
							{panel === "audio" &&
								(audios.length === 0 ? (
									<Text style={styles.emptyText}>No audio tracks</Text>
								) : (
									audios.map((audio) =>
										option(
											`audio-${audio.id}`,
											audio.label ?? audio.language ?? audio.id,
											audio.selected,
											() => player.selectAudio(audio),
										),
									)
								))}
							{panel === "video" &&
								videos.map((video) =>
									option(
										`video-${video.id}`,
										video.label ?? video.language ?? video.id,
										video.selected,
										() => player.selectVideo(video),
									),
								)}
							{panel === "subtitles" && (
								<>
									{option("subs-off", "Off", !activeSubtitle, () =>
										player.selectSubtitle(undefined),
									)}
									{subtitles.map((subtitle) =>
										option(
											`subtitle-${subtitle.id}`,
											subtitle.label ?? subtitle.language ?? subtitle.id,
											subtitle.selected,
											() => player.selectSubtitle(subtitle),
										),
									)}
								</>
							)}
							{panel === "quality" && (
								<>
									{option(
										"quality-auto",
										`Auto${isAutoQuality && selectedRendition ? ` (${selectedRendition.height}p)` : ""}`,
										isAutoQuality,
										() => player.selectRendition(undefined),
									)}
									{renditions.length === 0 ? (
										<Text style={styles.emptyText}>No renditions</Text>
									) : (
										renditions.map((rendition) =>
											option(
												`rendition-${rendition.id}`,
												`${rendition.width}x${rendition.height} (${Math.round(rendition.bitrate / 1000)} kbps)`,
												!isAutoQuality && rendition.selected,
												() => player.selectRendition(rendition),
											),
										)
									)}
								</>
							)}
						</ScrollView>
					</View>
				</>
			)}

			{status === "loading" && (
				<View style={styles.overlayCenter}>
					<ActivityIndicator size="large" color="#ffffff" />
				</View>
			)}
			{status === "error" && (
				<View style={styles.overlayCenter}>
					<Text style={styles.errorText}>Playback error</Text>
				</View>
			)}
		</View>
	);
}

function Home({
	backend,
	onSwitchBackend,
	currentIndex,
	hasSource,
	startTime,
	onSetStartTime,
	onPlayIndex,
	onOpenPlayer,
	onStop,
	logs,
}: {
	backend: AndroidBackend;
	onSwitchBackend: (backend: AndroidBackend) => void;
	currentIndex: number;
	hasSource: boolean;
	startTime: number;
	onSetStartTime: (startTime: number) => void;
	onPlayIndex: (index: number) => void;
	onOpenPlayer: () => void;
	onStop: () => void;
	logs: { id: number; message: string }[];
}): React.JSX.Element {
	const player = usePlayer();
	const status = usePlayerState("status");
	const isPlaying = usePlayerState("isPlaying");
	const currentTime = usePlayerState("currentTime");
	const duration = usePlayerState("duration");
	const castStatus = usePlayerState("castStatus");
	const source = usePlayerState("source");

	return (
		<ScrollView style={styles.home} contentContainerStyle={styles.homeContent}>
			<Text style={styles.heading}>react-native-omni</Text>
			<Text style={styles.subheading}>
				Pick a media to open the player. The back button only unmounts the
				OmniView: the provider stays mounted, so on native the player (and its
				notification) keeps running in the background.
			</Text>

			{hasSource && (
				<View style={styles.card}>
					<Text style={styles.cardTitle}>
						{source?.metadata?.title ?? "(none)"}
					</Text>
					<Text style={styles.cardText}>
						{status} · {formatTime(currentTime)} / {formatTime(duration)} ·
						cast: {castStatus}
					</Text>
					<View style={styles.row}>
						<Pressable
							style={styles.button}
							onPress={() => {
								if (isPlaying) player.pause();
								else player.play();
							}}
						>
							<Text style={styles.buttonText}>
								{isPlaying ? "Pause" : "Play"}
							</Text>
						</Pressable>
						<Pressable style={styles.button} onPress={onOpenPlayer}>
							<Text style={styles.buttonText}>Open player</Text>
						</Pressable>
						<Pressable style={styles.button} onPress={onStop}>
							<Text style={styles.buttonText}>Unload</Text>
						</Pressable>
					</View>
				</View>
			)}

			{Platform.OS === "android" && (
				<>
					<Text style={styles.sectionTitle}>Backend</Text>
					<View style={styles.row}>
						<Pressable
							style={[styles.button, backend === "vlc" && styles.buttonSelected]}
							onPress={() => onSwitchBackend("vlc")}
						>
							<Text style={styles.buttonText}>VLC</Text>
						</Pressable>
						<Pressable
							style={[
								styles.button,
								backend === "exoplayer" && styles.buttonSelected,
							]}
							onPress={() => onSwitchBackend("exoplayer")}
						>
							<Text style={styles.buttonText}>ExoPlayer</Text>
						</Pressable>
					</View>
				</>
			)}

			<Text style={styles.sectionTitle}>Start time</Text>
			<Text style={styles.cardText}>
				Applied when a media is (re)loaded. Picking a new value while something
				is loaded reloads the source at that position.
			</Text>
			<View style={styles.row}>
				{START_TIMES.map((seconds) => (
					<Pressable
						key={`start-${seconds}`}
						style={[
							styles.button,
							seconds === startTime && styles.buttonSelected,
						]}
						onPress={() => onSetStartTime(seconds)}
					>
						<Text style={styles.buttonText}>
							{seconds === 0 ? "Off" : formatTime(seconds)}
						</Text>
					</Pressable>
				))}
			</View>

			<Text style={styles.sectionTitle}>Library</Text>
			{PLAYLIST.map((item, index) => (
				<Pressable
					key={item.uri}
					style={[
						styles.listItem,
						hasSource && index === currentIndex && styles.listItemActive,
					]}
					onPress={() => onPlayIndex(index)}
				>
					<Text style={styles.listTitle}>{item.title}</Text>
					<Text style={styles.listSubtitle}>
						{item.artist} · {item.album}
					</Text>
				</Pressable>
			))}

			<Text style={styles.sectionTitle}>Logs</Text>
			<View style={styles.card}>
				{logs.length === 0 ? (
					<Text style={styles.cardText}>Event log will appear here.</Text>
				) : (
					logs.map((entry) => (
						<Text key={entry.id} style={styles.cardText}>
							{entry.message}
						</Text>
					))
				)}
			</View>
		</ScrollView>
	);
}

function Shell({
	backend,
	onSwitchBackend,
	currentIndex,
	hasSource,
	startTime,
	onSetStartTime,
	onPlayIndex,
	onPrev,
	onNext,
	onStop,
}: {
	backend: AndroidBackend;
	onSwitchBackend: (backend: AndroidBackend) => void;
	currentIndex: number;
	hasSource: boolean;
	startTime: number;
	onSetStartTime: (startTime: number) => void;
	onPlayIndex: (index: number) => void;
	onPrev: () => void;
	onNext: () => void;
	onStop: () => void;
}): React.JSX.Element {
	const [screen, setScreen] = useState<"home" | "player">("home");
	const [logs, setLogs] = useState<{ id: number; message: string }[]>([]);
	const logId = useRef(0);

	const pushLog = useCallback((message: string) => {
		setLogs((prev) => {
			const next = [{ id: logId.current++, message }, ...prev];
			return next.slice(0, 8);
		});
	}, []);

	const handlePrev = useCallback(() => {
		pushLog("Prev selected");
		onPrev();
	}, [onPrev, pushLog]);

	const handleNext = useCallback(() => {
		pushLog("Next selected");
		onNext();
	}, [onNext, pushLog]);

	useEvent(
		"error",
		useCallback(
			(type, message) => {
				pushLog(`Error (${type}): ${message}`);
			},
			[pushLog],
		),
	);
	useEvent(
		"audioFocusChange",
		useCallback(
			(focus) => {
				pushLog(`Audio focus: ${focus}`);
			},
			[pushLog],
		),
	);
	useEvent("prev", handlePrev);
	useEvent("next", handleNext);
	useEvent(
		"end",
		useCallback(() => {
			pushLog("Playback ended");
			handleNext();
		}, [handleNext, pushLog]),
	);

	if (screen === "player") {
		return <Player onBack={() => setScreen("home")} />;
	}
	return (
		<Home
			backend={backend}
			onSwitchBackend={onSwitchBackend}
			currentIndex={currentIndex}
			hasSource={hasSource}
			startTime={startTime}
			onSetStartTime={onSetStartTime}
			onPlayIndex={(index) => {
				onPlayIndex(index);
				setScreen("player");
			}}
			onOpenPlayer={() => setScreen("player")}
			onStop={onStop}
			logs={logs}
		/>
	);
}

function App(): React.JSX.Element {
	const [currentIndex, setCurrentIndex] = useState(0);
	// Start with no media loaded so we can verify the app boots (and the media
	// notification stays hidden) before any source is set.
	const [hasSource, setHasSource] = useState(false);
	const [backend, setBackend] = useState<AndroidBackend>("vlc");
	// position the next loaded source starts at (0 = don't send startTime at all)
	const [startTime, setStartTime] = useState(0);

	// Switching the backend just updates the prop: OmniProvider recreates (and
	// disposes) the native player internally, so the change applies live.
	const handleSwitchBackend = useCallback((next: AndroidBackend) => {
		setBackend(next);
	}, []);

	const handlePrev = useCallback(() => {
		setCurrentIndex((index) => (index === 0 ? PLAYLIST.length - 1 : index - 1));
	}, []);

	const handleNext = useCallback(() => {
		setCurrentIndex((index) => (index + 1) % PLAYLIST.length);
	}, []);

	const handlePlayIndex = useCallback((index: number) => {
		setCurrentIndex(index);
		setHasSource(true);
	}, []);

	const handleStop = useCallback(() => {
		setHasSource(false);
	}, []);

	const source = useMemo(
		() => ({
			src: {
				uri: PLAYLIST[currentIndex].uri,
				headers: {},
			},
			startTime: startTime || undefined,
			subtitles: SUBTITLES,
			fonts: [
				"https://jassub.pages.dev/fonts/FOT-TsukuCOldMinPr6NR.OTF",
				"https://jassub.pages.dev/fonts/arial.ttf",
			],
			metadata: {
				title: PLAYLIST[currentIndex].title,
				artist: PLAYLIST[currentIndex].artist,
				album: PLAYLIST[currentIndex].album,
				imageLink: PLAYLIST[currentIndex].imageLink,
				hasPrev: true,
				hasNext: true,
			},
		}),
		[currentIndex, startTime],
	);

	return (
		<OmniProvider
			source={hasSource ? source : undefined}
			backend={{ android: backend }}
			cast={{
				receiverApplicationId: "D8FB0FC1",
				notificationUrl: "omniexample://remote",
			}}
			showNotification
		>
			<Shell
				backend={backend}
				onSwitchBackend={handleSwitchBackend}
				currentIndex={currentIndex}
				hasSource={hasSource}
				startTime={startTime}
				onSetStartTime={setStartTime}
				onPlayIndex={handlePlayIndex}
				onPrev={handlePrev}
				onNext={handleNext}
				onStop={handleStop}
			/>
		</OmniProvider>
	);
}

const styles = StyleSheet.create({
	// player
	playerRoot: {
		flex: 1,
		backgroundColor: "#000000",
	},
	video: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
	},
	tapZones: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
		flexDirection: "row",
	},
	tapZone: {
		flex: 1,
		alignItems: "center",
		justifyContent: "center",
	},
	hintBubble: {
		backgroundColor: "rgba(0,0,0,0.55)",
		paddingHorizontal: 18,
		paddingVertical: 12,
		borderRadius: 999,
	},
	hintText: {
		color: "#ffffff",
		fontSize: 18,
		fontWeight: "700",
	},
	controls: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
		justifyContent: "space-between",
	},
	scrim: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
		backgroundColor: "rgba(0,0,0,0.35)",
		pointerEvents: "none",
	},
	topBar: {
		flexDirection: "row",
		alignItems: "center",
		gap: 12,
		paddingHorizontal: 16,
		paddingTop: 16,
	},
	topTitles: {
		flex: 1,
	},
	topTitle: {
		color: "#ffffff",
		fontSize: 16,
		fontWeight: "700",
	},
	topSubtitle: {
		color: "#c9d2ee",
		fontSize: 12,
	},
	iconButton: {
		width: 40,
		height: 40,
		borderRadius: 20,
		alignItems: "center",
		justifyContent: "center",
		backgroundColor: "rgba(255,255,255,0.12)",
	},
	iconText: {
		color: "#ffffff",
		fontSize: 20,
		fontWeight: "700",
	},
	centerRow: {
		flexDirection: "row",
		alignItems: "center",
		justifyContent: "center",
		gap: 28,
		pointerEvents: "box-none",
	},
	roundButton: {
		width: 56,
		height: 56,
		borderRadius: 28,
		alignItems: "center",
		justifyContent: "center",
		backgroundColor: "rgba(255,255,255,0.12)",
	},
	roundText: {
		color: "#ffffff",
		fontSize: 16,
		fontWeight: "700",
	},
	playButton: {
		width: 76,
		height: 76,
		borderRadius: 38,
		backgroundColor: "rgba(255,255,255,0.2)",
	},
	playText: {
		color: "#ffffff",
		fontSize: 26,
		fontWeight: "700",
	},
	bottomBar: {
		paddingHorizontal: 16,
		// leave room for the logbox snack bar (dev builds) at the bottom
		paddingBottom: 120,
		gap: 6,
	},
	timeRow: {
		flexDirection: "row",
		justifyContent: "space-between",
	},
	timeText: {
		color: "#e8ecff",
		fontSize: 12,
		fontVariant: ["tabular-nums"],
	},
	progressHitbox: {
		justifyContent: "center",
		paddingVertical: 12,
	},
	progressTrack: {
		height: 4,
		borderRadius: 2,
		backgroundColor: "rgba(255,255,255,0.25)",
		overflow: "hidden",
		pointerEvents: "none",
	},
	progressBuffered: {
		position: "absolute",
		top: 0,
		bottom: 0,
		left: 0,
		backgroundColor: "rgba(255,255,255,0.4)",
		pointerEvents: "none",
	},
	progressFill: {
		position: "absolute",
		top: 0,
		bottom: 0,
		left: 0,
		backgroundColor: "#6e93f9",
		pointerEvents: "none",
	},
	progressThumb: {
		position: "absolute",
		width: 12,
		height: 12,
		borderRadius: 6,
		marginLeft: -6,
		backgroundColor: "#ffffff",
		pointerEvents: "none",
	},
	progressThumbActive: {
		width: 18,
		height: 18,
		borderRadius: 9,
		marginLeft: -9,
	},
	actionRow: {
		flexDirection: "row",
		flexWrap: "wrap",
		gap: 8,
	},
	pill: {
		backgroundColor: "rgba(255,255,255,0.12)",
		paddingHorizontal: 12,
		paddingVertical: 8,
		borderRadius: 999,
	},
	pillActive: {
		backgroundColor: "#2f4fa0",
	},
	pillText: {
		color: "#f2f4ff",
		fontSize: 12,
		fontWeight: "600",
	},
	sheetBackdrop: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
		backgroundColor: "rgba(0,0,0,0.4)",
	},
	sheet: {
		position: "absolute",
		left: 0,
		right: 0,
		bottom: 0,
		maxHeight: "60%",
		backgroundColor: "#0d1326",
		borderTopLeftRadius: 16,
		borderTopRightRadius: 16,
		paddingBottom: 12,
	},
	sheetHeader: {
		flexDirection: "row",
		alignItems: "center",
		justifyContent: "space-between",
		paddingHorizontal: 16,
		paddingVertical: 14,
	},
	sheetTitle: {
		color: "#e8ecff",
		fontSize: 15,
		fontWeight: "700",
	},
	sheetClose: {
		color: "#a7b4df",
		fontSize: 16,
		fontWeight: "700",
	},
	sheetContent: {
		paddingHorizontal: 12,
		paddingBottom: 12,
		gap: 6,
	},
	option: {
		paddingHorizontal: 14,
		paddingVertical: 12,
		borderRadius: 10,
		backgroundColor: "#151f3c",
	},
	optionSelected: {
		backgroundColor: "#2f4fa0",
	},
	optionText: {
		color: "#cfd9ff",
		fontSize: 14,
	},
	optionTextSelected: {
		color: "#ffffff",
		fontWeight: "700",
	},
	emptyText: {
		color: "#8fa1d8",
		fontSize: 13,
		paddingHorizontal: 14,
		paddingVertical: 12,
	},
	overlayCenter: {
		position: "absolute",
		top: 0,
		left: 0,
		right: 0,
		bottom: 0,
		alignItems: "center",
		justifyContent: "center",
		pointerEvents: "none",
	},
	errorText: {
		color: "#ff9d9d",
		fontSize: 16,
		fontWeight: "700",
	},

	// home
	home: {
		flex: 1,
		backgroundColor: "#0b1020",
	},
	homeContent: {
		paddingHorizontal: 16,
		paddingTop: 64,
		paddingBottom: 32,
		gap: 12,
	},
	heading: {
		fontSize: 24,
		fontWeight: "700",
		color: "#e8ecff",
	},
	subheading: {
		fontSize: 13,
		color: "#a7b4df",
	},
	sectionTitle: {
		color: "#cfd9ff",
		fontSize: 12,
		fontWeight: "700",
		marginTop: 8,
	},
	card: {
		backgroundColor: "#101833",
		borderRadius: 10,
		padding: 12,
		gap: 6,
	},
	cardTitle: {
		color: "#e8ecff",
		fontSize: 15,
		fontWeight: "700",
	},
	cardText: {
		color: "#9fb0e8",
		fontSize: 12,
	},
	row: {
		flexDirection: "row",
		gap: 10,
	},
	button: {
		flex: 1,
		backgroundColor: "#1a2442",
		paddingVertical: 12,
		borderRadius: 10,
		alignItems: "center",
		borderWidth: 1,
		borderColor: "#2d3f74",
	},
	buttonSelected: {
		backgroundColor: "#2f4fa0",
		borderColor: "#6e93f9",
	},
	buttonText: {
		color: "#f2f4ff",
		fontSize: 14,
		fontWeight: "600",
	},
	listItem: {
		backgroundColor: "#101833",
		borderRadius: 10,
		padding: 14,
		borderWidth: 1,
		borderColor: "#1d2a4f",
		gap: 2,
	},
	listItemActive: {
		borderColor: "#6e93f9",
	},
	listTitle: {
		color: "#e8ecff",
		fontSize: 15,
		fontWeight: "600",
	},
	listSubtitle: {
		color: "#8fa1d8",
		fontSize: 12,
	},
});

export default App;
