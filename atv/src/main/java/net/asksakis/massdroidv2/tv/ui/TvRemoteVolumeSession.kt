package net.asksakis.massdroidv2.tv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.model.PlaybackState
import net.asksakis.massdroidv2.domain.model.Player
import net.asksakis.massdroidv2.domain.player.VolumeKeyController
import net.asksakis.massdroidv2.domain.player.VolumeKeyTarget
import net.asksakis.massdroidv2.domain.player.volumeKeyTarget
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository

/**
 * The way the remote's volume keys reach the selected player on a TV.
 *
 * Android TV never hands volume keys to the foreground activity: the window manager turns
 * them into audio events, and those go to the TV's own volume, or to the media session that
 * declares its playback as remote with a volume provider and is playing. So while the app is
 * in front and a Music Assistant player elsewhere is selected, this session is that session:
 * it mirrors the player's state and title, and its volume provider steps the player through
 * the same [VolumeKeyController] as the mini player's own buttons. With this TV's own output
 * selected, or nothing, the session is inactive and the keys set the TV as before.
 *
 * It is separate from the playback service's session, which is this TV's own stream and
 * must keep its local volume. Only one of them is playing at a time: a selected speaker
 * elsewhere means the TV's stream is not the one being listened to.
 *
 * Some TVs keep the keys for their own speaker whatever a session declares (Sony Bravia's
 * window manager sets the TV volume itself, seen as "volumeTarget" in its log). For those
 * the TV volume is followed instead: while the keys are ours, every change of the music
 * stream is one step on the player in the same direction, and the stream is parked in the
 * middle of its range so a key at either end still produces a change. The TV is not the
 * output then, so its level is of no consequence, and it is put back when the keys are
 * released to the TV.
 */
class TvRemoteVolumeSession(
    private val context: Context,
    private val playerRepository: PlayerRepository,
    private val settingsRepository: SettingsRepository,
    private val volumeKeyController: VolumeKeyController,
    private val scope: CoroutineScope,
) {
    private val session = MediaSessionCompat(context, "MassDroidTvRemote")
    private var mirror: Job? = null
    private var provider: VolumeProviderCompat? = null
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var following = false
    private var parkedFrom = -1
    private var parking = false
    private val streamFollower = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.getIntExtra(EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
            if (parking) return
            val now = intent.getIntExtra(EXTRA_STREAM_VALUE, -1)
            val before = intent.getIntExtra(EXTRA_PREV_STREAM_VALUE, -1)
            if (now < 0 || before < 0 || now == before) return
            volumeKeyController.step(up = now > before)?.let { provider?.currentVolume = it }
            volumeKeyController.flush()
            park()
        }
    }

    init {
        // Transport callbacks reach the selected player too, so a controller that picks this
        // session up (Google's cross-device media controls, for one) is not a dead card.
        session.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { onSelected { playerRepository.play(it) } }
            override fun onPause() { onSelected { playerRepository.pause(it) } }
            override fun onSkipToNext() { onSelected { playerRepository.next(it) } }
            override fun onSkipToPrevious() { onSelected { playerRepository.previous(it) } }
            override fun onStop() { onSelected { playerRepository.pause(it) } }
        })
        session.isActive = false
    }

    /** Follow the selection while the activity is in front. */
    fun start() {
        mirror?.cancel()
        mirror = scope.launch {
            combine(playerRepository.selectedPlayer, settingsRepository.sendspinClientId) { player, localId ->
                player to volumeKeyTarget(player, localId)
            }.collect { (player, target) ->
                if (player != null && target is VolumeKeyTarget.RemotePlayer) {
                    mirrorRemote(player, target.isGroup)
                } else {
                    deactivate()
                }
            }
        }
    }

    /** The activity left the front: the keys go back to the TV. */
    fun stop() {
        mirror?.cancel()
        mirror = null
        deactivate()
    }

    fun release() {
        stop()
        session.release()
    }

    private fun mirrorRemote(player: Player, isGroup: Boolean) {
        val level = (if (isGroup) player.groupVolume else null) ?: player.volumeLevel
        val current = provider ?: RemoteVolume(level).also { provider = it }
        current.currentVolume = level.coerceIn(0, MAX_VOLUME)
        session.setPlaybackToRemote(current)
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, player.displayName)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Music Assistant")
                .build()
        )
        // Only a playing session is offered the volume keys, so the state is the player's own:
        // with it paused the keys fall back to the TV, as the listener would expect of a
        // speaker that is silent anyway.
        val state = when (player.state) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_STOPPED
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
                .build()
        )
        session.isActive = true
        follow()
    }

    private fun deactivate() {
        unfollow()
        if (!session.isActive) return
        session.isActive = false
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
                .build()
        )
    }

    /** Take the TV's music stream as the key sensor, parked mid-range. */
    private fun follow() {
        if (following) return
        following = true
        parkedFrom = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        context.registerReceiver(streamFollower, IntentFilter(VOLUME_CHANGED_ACTION))
        park()
    }

    /** Give the stream back to the TV at the level it had. */
    private fun unfollow() {
        if (!following) return
        following = false
        runCatching { context.unregisterReceiver(streamFollower) }
        if (parkedFrom >= 0) setStream(parkedFrom)
        parkedFrom = -1
    }

    private fun park() {
        val mid = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
        if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != mid) setStream(mid)
    }

    private fun setStream(level: Int) {
        parking = true
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0) }
        // The change broadcast arrives on the main thread after this returns.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ parking = false }, PARK_SETTLE_MS)
    }

    private fun onSelected(action: suspend (String) -> Unit) {
        val id = playerRepository.selectedPlayer.value?.playerId ?: return
        scope.launch { runCatching { action(id) } }
    }

    /** The TV's volume events, as steps and levels on the selected player. */
    private inner class RemoteVolume(level: Int) :
        VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, MAX_VOLUME, level.coerceIn(0, MAX_VOLUME)) {

        override fun onAdjustVolume(direction: Int) {
            if (direction == 0) {
                // A key release, the way the activity path flushes the held level.
                volumeKeyController.flush()
                return
            }
            volumeKeyController.step(up = direction > 0)?.let { currentVolume = it }
        }

        override fun onSetVolumeTo(volume: Int) {
            volumeKeyController.setLevel(volume)?.let { currentVolume = it }
        }
    }

    private companion object {
        const val MAX_VOLUME = 100
        const val PARK_SETTLE_MS = 150L
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_STREAM_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV_STREAM_VALUE = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
