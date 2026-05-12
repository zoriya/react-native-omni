import { useCallback, useEffect, useMemo, useState } from "react";
import type React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import {
	OmniProvider,
	OmniView,
	useEvent,
	usePlayer,
	usePlayerState,
} from "react-native-omni";

const PLAYLIST = [
	{
		title: "elephants dram",
		artist: "multi audio",
		album: "Adaptive",
		uri: "https://playertest.longtailvideo.com/adaptive/elephants_dream_v4/index.m3u8",
	},
	{
		title: "Big Buck Bunny (HLS)",
		artist: "Blender Foundation",
		album: "Open Movie Project",
		artwork:
			"https://peach.blender.org/wp-content/uploads/title_anouncement.jpg",
		uri: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
	},
	{
		title: "Sintel Trailer (MP4)",
		artist: "Blender Foundation",
		album: "Sintel",
		artwork:
			"https://download.blender.org/durian/trailer/sintel_trailer-480p.jpg",
		uri: "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/Sintel.mp4",
	},
] as const;


function formatTime(seconds: number): string {
	if (!Number.isFinite(seconds) || seconds < 0) {
		return "00:00";
	}

	const total = Math.floor(seconds);
	const mins = Math.floor(total / 60)
		.toString()
		.padStart(2, "0");
	const secs = (total % 60).toString().padStart(2, "0");
	return `${mins}:${secs}`;
}

function PlayerExample({
	onPrev,
	onNext,
	trackLabel,
}: {
	onPrev: () => void;
	onNext: () => void;
	trackLabel: string;
}): React.JSX.Element {
	const player = usePlayer();
	const status = usePlayerState("status");
	const isPlaying = usePlayerState("isPlaying");
	const currentTime = usePlayerState("currentTime");
	const duration = usePlayerState("duration");
	const playbackRate = usePlayerState("playbackRate");

	const muted = usePlayerState("muted");
	const volume = usePlayerState("volume");
	const isAutoQuality = usePlayerState("isAutoQuality");
	const [logs, setLogs] = useState<string[]>([]);
	const [tracks, setTracks] = useState(() => ({
		videos: [...player.videos],
		audios: [...player.audios],
		subtitles: [...player.subtitles],
		renditions: [...player.rendition],
	}));

	const refreshTracks = useCallback(() => {
		setTracks({
			videos: [...player.videos],
			audios: [...player.audios],
			subtitles: [...player.subtitles],
			renditions: [...player.rendition],
		});
	}, [player]);

	useEffect(() => {
		refreshTracks();
	}, [refreshTracks, trackLabel]);

	const pushLog = useCallback((message: string) => {
		setLogs((prev) => {
			const next = [message, ...prev];
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
	useEvent(
		"videoTrackChange",
		useCallback(
			(track) => {
				pushLog(`Video track: ${track.label ?? track.id}`);
				refreshTracks();
			},
			[pushLog, refreshTracks],
		),
	);
	useEvent(
		"audioTrackChange",
		useCallback(
			(track) => {
				pushLog(`Audio track: ${track.label ?? track.id}`);
				refreshTracks();
			},
			[pushLog, refreshTracks],
		),
	);
	useEvent(
		"subtitleChange",
		useCallback(
			(track) => {
				pushLog(`Subtitle: ${track?.label ?? track?.id ?? "off"}`);
				refreshTracks();
			},
			[pushLog, refreshTracks],
		),
	);
	useEvent(
		"renditionChange",
		useCallback(
			(rendition) => {
				pushLog(`Rendition: ${rendition.width}x${rendition.height}`);
				refreshTracks();
			},
			[pushLog, refreshTracks],
		),
	);

	const togglePlayback = () => {
		if (isPlaying) {
			player.pause();
			return;
		}
		player.play();
	};

	const toggleMute = () => {
		player.muted = !muted;
	};

	const changeVolume = (delta: number) => {
		const nextVolume = Math.max(0, Math.min(1, volume + delta));
		player.volume = Number(nextVolume.toFixed(2));
	};

	const cyclePlaybackRate = () => {
		const rates = [0.75, 1, 1.25, 1.5, 2];
		const index = rates.findIndex((rate) => rate === playbackRate);
		const nextIndex = (index + 1) % rates.length;
		player.playbackRate = rates[nextIndex];
	};

	const selectVideo = (video: (typeof tracks.videos)[number]) => {
		player.selectVideo(video);
		refreshTracks();
	};

	const selectAudio = (audio: (typeof tracks.audios)[number]) => {
		player.selectAudio(audio);
		refreshTracks();
	};

	const selectSubtitle = (subtitle?: (typeof tracks.subtitles)[number]) => {
		player.selectSubtitle(subtitle);
		refreshTracks();
	};

	return (
		<ScrollView style={styles.container}>
			<Text style={styles.heading}>react-native-omni</Text>
			<Text style={styles.subheading}>{trackLabel}</Text>

			<OmniView style={styles.video} autoplay={true} showNotification={true} />

			<View style={styles.row}>
				<Pressable style={styles.button} onPress={togglePlayback}>
					<Text style={styles.buttonText}>{isPlaying ? "Pause" : "Play"}</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={() => player.seekBy(-10)}>
					<Text style={styles.buttonText}>-10s</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={() => player.seekBy(10)}>
					<Text style={styles.buttonText}>+10s</Text>
				</Pressable>
			</View>

			<View style={styles.row}>
				<Pressable style={styles.button} onPress={() => player.playPrev()}>
					<Text style={styles.buttonText}>Prev</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={() => player.playNext()}>
					<Text style={styles.buttonText}>Next</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={toggleMute}>
					<Text style={styles.buttonText}>{muted ? "Unmute" : "Mute"}</Text>
				</Pressable>
			</View>

			<View style={styles.row}>
				<Pressable style={styles.button} onPress={() => changeVolume(-0.1)}>
					<Text style={styles.buttonText}>Vol -</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={() => changeVolume(0.1)}>
					<Text style={styles.buttonText}>Vol +</Text>
				</Pressable>
				<Pressable style={styles.button} onPress={cyclePlaybackRate}>
					<Text style={styles.buttonText}>{playbackRate.toFixed(2)}x</Text>
				</Pressable>
			</View>

			<View style={styles.statsCard}>
				<Text style={styles.statText}>Status: {status}</Text>
				<Text style={styles.statText}>
					Time: {formatTime(currentTime)} / {formatTime(duration)}
				</Text>
				<Text style={styles.statText}>
					Volume: {(volume * 100).toFixed(0)}%
				</Text>
			</View>

			<View style={styles.selectorCard}>
				<Text style={styles.selectorHeading}>Tracks & Renditions</Text>
				<Text style={styles.selectorTitle}>Video</Text>
				<View style={styles.selectorRow}>
					{tracks.videos.length === 0 ? (
						<Text style={styles.emptyTrackText}>No video tracks</Text>
					) : (
						tracks.videos.map((video, index) => (
							<Pressable
								key={`video-${video.id}-${index}`}
								style={[
									styles.trackButton,
									video.selected && styles.selectedTrackButton,
								]}
								onPress={() => selectVideo(video)}
							>
								<Text
									style={[
										styles.trackButtonText,
										video.selected && styles.selectedTrackButtonText,
									]}
								>
									{video.label ?? video.language ?? video.id}
								</Text>
							</Pressable>
						))
					)}
				</View>

				<Text style={styles.selectorTitle}>Audio</Text>
				<View style={styles.selectorRow}>
					{tracks.audios.length === 0 ? (
						<Text style={styles.emptyTrackText}>No audio tracks</Text>
					) : (
						tracks.audios.map((audio, index) => (
							<Pressable
								key={`audio-${audio.id}-${index}`}
								style={[
									styles.trackButton,
									audio.selected && styles.selectedTrackButton,
								]}
								onPress={() => selectAudio(audio)}
							>
								<Text
									style={[
										styles.trackButtonText,
										audio.selected && styles.selectedTrackButtonText,
									]}
								>
									{audio.label ?? audio.language ?? audio.id}
								</Text>
							</Pressable>
						))
					)}
				</View>

				<Text style={styles.selectorTitle}>Subtitles</Text>
				<View style={styles.selectorRow}>
					<Pressable
						style={[
							styles.trackButton,
							!tracks.subtitles.some((subtitle) => subtitle.selected) &&
								styles.selectedTrackButton,
						]}
						onPress={() => selectSubtitle(undefined)}
					>
						<Text
							style={[
								styles.trackButtonText,
								!tracks.subtitles.some((subtitle) => subtitle.selected) &&
									styles.selectedTrackButtonText,
							]}
						>
							Off
						</Text>
					</Pressable>
					{tracks.subtitles.length === 0 ? (
						<Text style={styles.emptyTrackText}>No subtitles</Text>
					) : (
						tracks.subtitles.map((subtitle, index) => (
							<Pressable
								key={`subtitle-${subtitle.id}-${index}`}
								style={[
									styles.trackButton,
									subtitle.selected && styles.selectedTrackButton,
								]}
								onPress={() => selectSubtitle(subtitle)}
							>
								<Text
									style={[
										styles.trackButtonText,
										subtitle.selected && styles.selectedTrackButtonText,
									]}
								>
									{subtitle.label ?? subtitle.language ?? subtitle.id}
								</Text>
							</Pressable>
						))
					)}
				</View>

				<Text style={styles.selectorTitle}>Renditions</Text>
				<View style={styles.selectorRow}>
					<Pressable
						style={[
							styles.trackButton,
							isAutoQuality && styles.selectedTrackButton,
						]}
						onPress={() => player.selectRendition(undefined)}
					>
						<Text
							style={[
								styles.trackButtonText,
								isAutoQuality && styles.selectedTrackButtonText,
							]}
						>
							Auto
						</Text>
					</Pressable>
					{tracks.renditions.length === 0 ? (
						<Text style={styles.emptyTrackText}>No renditions</Text>
					) : (
						tracks.renditions.map((rendition, index) => (
							<Pressable
								key={`rendition-${rendition.id}-${index}`}
								style={[
									styles.trackButton,
									rendition.selected && styles.selectedTrackButton,
								]}
								onPress={() => player.selectRendition(rendition)}
							>
								<Text
									style={[
										styles.trackButtonText,
										rendition.selected && styles.selectedTrackButtonText,
									]}
								>
									{rendition.width}x{rendition.height} (
									{Math.round(rendition.bitrate / 1000)} kbps)
								</Text>
							</Pressable>
						))
					)}
				</View>
			</View>

			<ScrollView
				style={styles.logCard}
				contentContainerStyle={styles.logContent}
			>
				<Text style={styles.selectorTitle}>Logs</Text>
				{logs.length === 0 ? (
					<Text style={styles.logText}>Event log will appear here.</Text>
				) : (
					logs.map((entry, index) => (
						<Text key={`${entry}-${index}`} style={styles.logText}>
							{entry}
						</Text>
					))
				)}
			</ScrollView>
		</ScrollView>
	);
}

function App(): React.JSX.Element {
	const [currentIndex, setCurrentIndex] = useState(0);

	const handlePrev = useCallback(() => {
		setCurrentIndex((index) => (index === 0 ? PLAYLIST.length - 1 : index - 1));
	}, []);

	const handleNext = useCallback(() => {
		setCurrentIndex((index) => (index + 1) % PLAYLIST.length);
	}, []);

	const source = useMemo(
		() => ({
			src: [
				{
					uri: PLAYLIST[currentIndex].uri,
					headers: {},
				},
			],
			subtitles: [],
			metadata: {
				title: PLAYLIST[currentIndex].title,
				artist: PLAYLIST[currentIndex].artist,
				album: PLAYLIST[currentIndex].album,
				imageLink: PLAYLIST[currentIndex].artwork,
				hasPrev: true,
				hasNext: true,
			},
		}),
		[currentIndex],
	);

	return (
		<OmniProvider source={source} showNotification>
			<PlayerExample
				onPrev={handlePrev}
				onNext={handleNext}
				trackLabel={PLAYLIST[currentIndex].title}
			/>
		</OmniProvider>
	);
}

const styles = StyleSheet.create({
	container: {
		flex: 1,
		backgroundColor: "#0b1020",
		paddingHorizontal: 16,
		marginTop: 64,
		paddingVertical: 12,
		gap: 12,
	},
	heading: {
		fontSize: 24,
		fontWeight: "700",
		color: "#e8ecff",
	},
	subheading: {
		fontSize: 14,
		color: "#a7b4df",
	},
	video: {
		width: "100%",
		aspectRatio: 16 / 9,
		borderRadius: 14,
		overflow: "hidden",
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
	buttonText: {
		color: "#f2f4ff",
		fontSize: 14,
		fontWeight: "600",
	},
	statsCard: {
		backgroundColor: "#101833",
		borderRadius: 10,
		padding: 12,
		gap: 4,
	},
	statText: {
		color: "#cfd9ff",
		fontSize: 13,
	},
	selectorCard: {
		backgroundColor: "#101833",
		borderRadius: 10,
		padding: 12,
		gap: 8,
	},
	selectorHeading: {
		color: "#e8ecff",
		fontSize: 14,
		fontWeight: "700",
	},
	selectorTitle: {
		color: "#cfd9ff",
		fontSize: 12,
		fontWeight: "600",
	},
	selectorRow: {
		flexDirection: "row",
		flexWrap: "wrap",
		gap: 8,
	},
	trackButton: {
		backgroundColor: "#1a2442",
		paddingVertical: 8,
		paddingHorizontal: 10,
		borderRadius: 8,
		borderWidth: 1,
		borderColor: "#2d3f74",
	},
	selectedTrackButton: {
		backgroundColor: "#2f4fa0",
		borderColor: "#6e93f9",
	},
	trackButtonText: {
		color: "#cfd9ff",
		fontSize: 12,
	},
	selectedTrackButtonText: {
		color: "#f4f7ff",
		fontWeight: "700",
	},
	emptyTrackText: {
		color: "#8fa1d8",
		fontSize: 12,
	},
	logCard: {
		flex: 1,
		backgroundColor: "#101833",
		borderRadius: 10,
	},
	logContent: {
		padding: 12,
		gap: 6,
	},
	logText: {
		color: "#9fb0e8",
		fontSize: 12,
	},
});

export default App;
