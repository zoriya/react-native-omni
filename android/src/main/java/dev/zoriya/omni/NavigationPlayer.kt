package dev.zoriya.omni

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
class NavigationPlayer(
    player: Player,
    private val hasPrev: () -> Boolean,
    private val hasNext: () -> Boolean,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
) : ForwardingSimpleBasePlayer(player) {
    override fun getState(): State {
        val state = super.getState()
        return state.buildUpon()
            .setAvailableCommands(
                state.availableCommands.buildUpon()
                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrev())
                    .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrev())
                    .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext())
                    .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext())
                    .build()
            )
            .build()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> = when (seekCommand) {
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
            onPrev()
            Futures.immediateVoidFuture()
        }

        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
            onNext()
            Futures.immediateVoidFuture()
        }

        else -> super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }
}
