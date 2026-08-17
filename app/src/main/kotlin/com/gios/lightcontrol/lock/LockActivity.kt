package com.gios.lightcontrol.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gios.lightcontrol.Prefs
import com.gios.lightcontrol.ui.Gap
import com.gios.lightcontrol.ui.theme.Dim
import com.gios.lightcontrol.ui.theme.LightControlTheme
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The face you see while the phone is locked.
 *
 * **It is not a lock screen and cannot be.** Replacing the keyguard needs a system signature or
 * root; neither is on offer here. What this is instead is an activity marked `showWhenLocked`,
 * which the framework lets *occlude* the keyguard — the real one is still there, still holding the
 * device, still the only thing that can let anybody in. The phone is exactly as secure as it was;
 * all that changed is what is painted over the top while it waits.
 *
 * Two consequences follow, and they are the whole design:
 *
 *  - **The thumb does not work while this is merely sitting there, and cannot be made to.** v2.5
 *    shipped assuming it would, on the strength of the camera-from-lock shortcut. That shortcut
 *    only works on phones with an under-display sensor: AOSP keeps the keyguard's fingerprint
 *    listener armed while occluded for `isUdfps`, for a dreaming device, or while the bouncer is
 *    up — and for nothing else. **The LPIII's sensor is in the power button**, so an occluded
 *    keyguard stops listening and the press does nothing at all. Raising the bouncer is what turns
 *    it back on, which is why every route out of this screen goes through the bouncer. There is
 *    still no biometric code here and there must not be: a `BiometricPrompt` of our own would
 *    authenticate the user to *us* and leave the device locked, which is a screen that says yes
 *    and then does nothing.
 *  - **The code entry is not ours to draw either.** A passcode typed into our own keypad could not
 *    unlock anything. A tap asks for the real bouncer via
 *    [KeyguardManager.requestDismissKeyguard], which is AOSP's and cannot be restyled. So this
 *    face says what is happening and hands over; it does not pretend to take the code.
 *
 * The failure mode is deliberately dull. If this never starts, crashes, or is killed, what is
 * behind it is the stock lock screen, working. Nothing here can lock anyone out, which is the only
 * reason it is allowed to be started automatically at all — see `ControlService.onScreenOff`.
 */
class LockActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private var keyguard: KeyguardManager? = null

    /** Set once the face has been taken down, so a second signal cannot do it twice. */
    private var handedOff = false

    /**
     * The unlock, as seen from here.
     *
     * A backstop, not the mechanism. The service owns the real one — see `ControlService.screenOff`
     * — because the bouncer stops this activity and a stopped activity's receivers are gone. This
     * one is registered in `onCreate` rather than `onStart` so that it at least survives being
     * stopped, and exists for the case where the service is not running at all.
     */
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { takeDown() }
        }
    }

    /** Repaints the clock on the minute. */
    private val timeTick = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            now = System.currentTimeMillis()
        }
    }

    private var now by mutableStateOf(System.currentTimeMillis())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        keyguard = getSystemService(KeyguardManager::class.java)

        // Occlude the keyguard rather than dismiss it. Notably absent: `setTurnScreenOn`. This
        // activity is raised while the screen is off so that it is already on top when the phone
        // is next woken; asking to turn the screen on would wake the phone the moment it was put
        // down, which is the opposite of the feature.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }

        Lock.dismiss = { runCatching { takeDown() } }

        // Registered here, not in onStart, and unregistered in onDestroy. The bouncer stops this
        // activity, so a receiver tied to the visible lifecycle is unregistered at exactly the
        // moment the broadcast it exists for is sent. That was the v2.5 bug where a correct PIN
        // left this face on screen over an unlocked phone.
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(userPresent, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(userPresent, filter)
            }
        }

        // The first-class version of the same question, on the versions that have it. A listener
        // rather than a broadcast, so it cannot be missed for being unregistered at the wrong
        // moment, and it fires for every route the keyguard can be dismissed by.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                keyguard?.addKeyguardLockedStateListener(mainExecutor) { locked ->
                    if (!locked) takeDown()
                }
            }
        }

        setContent {
            LightControlTheme {
                // Back does nothing. Not a trap — the keyguard behind is still the gate — it only
                // stops the face flickering away to reveal the stock one underneath it.
                BackHandler(enabled = true) {}
                LockFace(
                    now = now,
                    wallpaperUri = prefs.lockImage,
                    showNotes = prefs.lockNotes,
                    destination = destinationLabel(),
                    onUnlock = ::askForCode,
                )
            }
        }

        // The mode for people who want the thumb and will trade the face for it: raise the real
        // bouncer straight away, where the sensor is listened for again. What you get is the stock
        // screen on every wake, with ours behind it — which is worth saying out loud in settings
        // rather than discovering.
        if (prefs.lockBouncerOnWake) askForCode()
    }

    override fun onStart() {
        super.onStart()
        // Tracked here rather than in onCreate: the activity outlives a screen that goes off and
        // on, and start and stop are what actually answer "is this on screen".
        Lock.showing = true
        runCatching { registerReceiver(timeTick, IntentFilter(Intent.ACTION_TIME_TICK)) }
        now = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        // Every return to the front re-asks the only question that matters. Cheap, and it closes
        // the gap where a dismissal happened by a route nothing here was listening to.
        if (keyguard?.isDeviceLocked == false) takeDown()
    }

    override fun onStop() {
        super.onStop()
        Lock.showing = false
        runCatching { unregisterReceiver(timeTick) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Lock.showing = false
        Lock.dismiss = null
        runCatching { unregisterReceiver(userPresent) }
    }

    /**
     * Raise the real bouncer.
     *
     * This is the only way in, and on this hardware it is also the only way the *fingerprint*
     * works: the keyguard arms its sensor listener again once the bouncer is showing. So a tap
     * here is not "I would rather type my code" — it is "wake the sensor up", and the thumb is
     * still the fast way through what appears.
     *
     * The success callback deliberately does no work. Dismissal is noticed in one place, by the
     * service, so the thumb and the code cannot end up doing subtly different things.
     */
    private fun askForCode() {
        val km = keyguard ?: return
        runCatching {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {})
        }
    }

    /**
     * Get out of the way. The service does the rest.
     *
     * Only the finish, on purpose. Where an unlock lands is the service's decision — it owns the
     * snapshot, the list and the fallback — and it makes it a beat after calling this, by which
     * time this task is gone and the launch has a clean stack to land on.
     */
    private fun takeDown() {
        if (handedOff) return
        handedOff = true
        Lock.showing = false
        Lock.dismiss = null
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    /**
     * What unlocking is going to open, in words, or null if it is just home.
     *
     * Shown because the point of the feature is that unlocking does something other than land you
     * on the dashboard, and a phone that opens a different app depending on what you were doing
     * last night is unnerving unless it says so first. Read from the same snapshot and the same
     * list the service will act on, so the label cannot promise one thing and the unlock deliver
     * another.
     */
    private fun destinationLabel(): String? {
        val pkg = Lock.pending ?: return null
        if (pkg !in prefs.resumeApps()) return null
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }
}

/**
 * Top bar, picture, clock, what is waiting, and one line saying what to do.
 *
 * The last line is the only part that had to be got exactly right. "Waiting for fingerprint" is
 * the true state of the phone at that moment — the sensor is armed, the keyguard is listening —
 * and saying so is the difference between a screen that looks broken and one that looks patient.
 */
@Composable
private fun LockFace(
    now: Long,
    wallpaperUri: String?,
    showNotes: Boolean,
    destination: String?,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val status by rememberLockStatus()

    var notes by remember { mutableStateOf(LockNotes.notes.value) }
    LaunchedEffect(showNotes) {
        if (showNotes) LockNotes.notes.collectLatest { notes = it } else notes = emptyList()
    }

    var wallpaper by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(wallpaperUri) {
        wallpaper = LockWallpaper.load(context, wallpaperUri, config.screenWidthDp, config.screenHeightDp)
    }

    val time = remember(now) { SimpleDateFormat("H:mm", Locale.getDefault()).format(Date(now)) }
    val date = remember(now) {
        SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(Date(now)).uppercase()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // No ripple and no indication, per the SDK: LightOS has none anywhere, and a
            // Material splash across a photograph would be the one un-Light thing on the phone.
            .clickable(interactionSource = null, indication = null, onClick = onUnlock)
            // A swipe up as well as a tap, because that is the gesture this screen already looks
            // like it wants and the one every other phone has trained into people.
            .pointerInput(Unit) {
                detectVerticalDragGestures { _, drag -> if (drag < -SWIPE_TRIGGER_PX) onUnlock() }
            },
    ) {
        wallpaper?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Desaturated on purpose. The panel is greyscale and matte, so colour arrives as
                // mid-greys anyway; converting deliberately means choosing which greys rather than
                // letting the display choose, and it keeps a bright photo from swallowing the
                // white type over it.
                colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }),
            )
            // The scrim is what makes the type legible over any picture at all, which is the only
            // way to let someone choose their own without also making them test it.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {

            TopBar(status)

            Column(
                Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(time, style = MaterialTheme.typography.displayLarge, color = Color.White)
                Gap(4)
                Text(date, style = MaterialTheme.typography.labelSmall, color = Dim)
                if (notes.isNotEmpty()) {
                    Gap(28)
                    // Capped rather than scrolled. A lock screen you have to scroll is a lock
                    // screen you read instead of unlocking, and the count carries the rest.
                    notes.take(MAX_NOTES).forEach { NoteRow(it) }
                    if (notes.size > MAX_NOTES) {
                        Gap(6)
                        Text(
                            "+${notes.size - MAX_NOTES} MORE",
                            style = MaterialTheme.typography.labelSmall,
                            color = Dim,
                        )
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (destination != null) {
                    Text(
                        "UNLOCKS INTO ${destination.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Dim,
                        textAlign = TextAlign.Center,
                    )
                    Gap(12)
                }
                Text(
                    "WAITING FOR FINGERPRINT",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Gap(6)
                Text(
                    "or tap to enter your code",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TopBar(status: LockStatus) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            status.network,
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        status.alarm?.let {
            Text(
                "ALARM $it",
                style = MaterialTheme.typography.labelSmall,
                color = Dim,
                maxLines = 1,
            )
            Gap(0)
        }
        Text(
            buildString {
                if (status.charging) append("CHARGING · ")
                append(if (status.battery in 0..100) "${status.battery}%" else "—")
            },
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            maxLines = 1,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun NoteRow(note: LockNote) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            note.app.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Dim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val headline = note.title.ifBlank { note.text }
        if (headline.isNotBlank()) {
            Text(
                headline,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The body only when there is a title above it, so a notification with one line of text
        // shows that line once rather than twice.
        if (note.title.isNotBlank() && note.text.isNotBlank()) {
            Text(
                note.text,
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** How many notifications fit above the unlock line without pushing it off a 600dp panel. */
private const val MAX_NOTES = 4

/** Drag per frame that counts as a deliberate swipe up rather than a finger settling. */
private const val SWIPE_TRIGGER_PX = 12f
