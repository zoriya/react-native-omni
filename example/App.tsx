import { useCallback, useMemo, useState } from "react";
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
		title: "Big Buck Bunny (HLS)",
		uri: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
	},
	{
		title: "Sintel Trailer (MP4)",
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
	const [logs, setLogs] = useState<string[]>([]);

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

	return (
		<View style={styles.container}>
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

			<ScrollView
				style={styles.logCard}
				contentContainerStyle={styles.logContent}
			>
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
		</View>
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
				hasPrev: true,
				hasNext: true,
			},
		}),
		[currentIndex],
	);

	return (
		<OmniProvider source={source}>
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
