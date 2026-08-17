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
 *  - **The fingerprint is not ours to read.** The keyguard keeps listening to the sensor while it
 *    is occluded — the same mechanism that lets you unlock out of the camera-from-lock shortcut —
 *    so a thumb dismisses the keyguard *underneath* this activity and the system broadcasts
 *    `ACTION_USER_PRESENT`. There is no biometric code here and there must not be: a
 *    `BiometricPrompt` of our own would authenticate the user to *us* and leave the device locked,
 *    which is a screen that says yes and then does nothing.
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

    /** Set once the handoff has run, so a second broadcast cannot fire it twice. */
    private var handedOff = false

    /**
     * The unlock itself.
     *
     * `ACTION_USER_PRESENT` is the one signal that means the *device* is open rather than that some
     * prompt returned success, so both paths — thumb and code — are read through it. It cannot be
     * declared in a manifest; it reaches receivers registered in code only, which is no obstacle
     * here because this activity is on screen and therefore neither cached nor frozen when it
     * arrives.
     */
    private val userPresent = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching { handOff() }
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
        // activity is started *as the screen goes off* so that it is already on top when the phone
        // is next woken — starting it on wake instead races the keyguard and shows a frame of the
        // stock screen every time. Asking to turn the screen on would then wake the phone the
        // moment it was put down, which is the opposite of the feature.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
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
                    onTap = ::askForCode,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Tracked here rather than in onCreate, because the activity outlives a screen that goes
        // off and on: start and stop are what actually answer "is this on screen", and the service
        // asks that question every time the phone is put down.
        Lock.showing = true
        runCatching {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(userPresent, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(userPresent, filter)
            }
            registerReceiver(timeTick, IntentFilter(Intent.ACTION_TIME_TICK))
        }
        now = System.currentTimeMillis()
        // The belt to the broadcast's braces. A device already unlocked when this started — the
        // screen going off inside the grace period after an unlock, say — will never send another
        // `ACTION_USER_PRESENT`, and the face would sit there over an open phone.
        if (keyguard?.isDeviceLocked == false) handOff()
    }

    override fun onStop() {
        super.onStop()
        Lock.showing = false
        runCatching { unregisterReceiver(userPresent) }
        runCatching { unregisterReceiver(timeTick) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Lock.showing = false
    }

    /**
     * Ask the real keyguard for the code.
     *
     * The success callback is deliberately not where the handoff happens — `ACTION_USER_PRESENT`
     * is, and it arrives for this path too. One route through means the thumb and the code cannot
     * end up doing subtly different things, which is the kind of split that only shows itself on
     * the one morning the sensor is wet.
     */
    private fun askForCode() {
        val km = keyguard ?: return
        runCatching {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {})
        }
    }

    /**
     * Get out of the way, then tell the service the phone is open.
     *
     * Order matters. Finishing first means the resume launch lands on a stack this activity is
     * already off, so the app it opens is what Back leaves — rather than this face, over a phone
     * that is no longer locked.
     */
    private fun handOff() {
        if (handedOff) return
        handedOff = true
        Lock.showing = false
        val resume = Lock.onUnlock
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
        // Posted, so the finish is through before the launch: starting an activity from a task
        // that is mid-teardown is the one shape of this that reliably lands behind the wrong
        // window.
        window.decorView.post { runCatching { resume?.invoke() } }
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
    onTap: () -> Unit,
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
            .clickable(interactionSource = null, indication = null, onClick = onTap),
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

/** How many notifications fit above the fingerprint line without pushing it off a 600dp panel. */
private const val MAX_NOTES = 4
