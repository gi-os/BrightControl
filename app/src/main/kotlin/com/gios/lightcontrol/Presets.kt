package com.gios.lightcontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Starting points: a whole working configuration, applied in one tap.
 *
 * ### Why this exists
 *
 * Every setting in this app is reachable and every one of them is explained, and that turns out
 * not to be the same thing as being usable. The question that keeps arriving is not "what does
 * this row do", it is "what am I supposed to switch on". People answer it by turning everything
 * on, which is the one configuration nobody tests: the lock face over a third-party launcher,
 * both edges live inside an app that already uses them, a home button bound three ways. Then the
 * navigation goes strange and the app looks broken rather than misconfigured.
 *
 * A preset is the answer to that question, written down. It is not a shortcut around the settings
 * screens — it is a known-good set of them that somebody can land on first and then change.
 *
 * ### Wipe, then write
 *
 * Applying a preset clears every stored setting before it writes its own, so the result is
 * exactly the preset and not a layer over whatever was there. This is the whole point. A merge
 * leaves a phone that matches no preset and no default, which is the state this feature exists to
 * get people out of. What survives the wipe is listed in [Prefs.resetToShipped] and is state
 * rather than settings: the adb pairing, the lock-screen photo, the crash log.
 *
 * ### The launcher is asked, not assumed
 *
 * Half of what a preset has to decide depends on which launcher is default, because the home
 * button means different things on either side of that. On stock LightOS the dashboard is home
 * and there is nothing to switch to. With Luma or Before or any other launcher installed, Light's
 * own dashboard becomes unreachable, and putting it back is the single most load-bearing binding
 * this app has. So the preset is told which phone it is landing on, and [Launcher.detect] works
 * that out from the system rather than making somebody answer a question the phone can answer.
 */
enum class Launcher {

    /** Stock. LightOS is the default launcher, so home already goes where home should. */
    LightOs,

    /** Luma, Before, or anything else. Light's dashboard needs a binding to stay reachable. */
    ThirdParty,
    ;

    companion object {

        /** LightOS's launcher. Its dashboard and its lock screen are one activity. */
        const val LIGHTOS_PKG = "com.lightos"

        /**
         * Which side of the line this phone is on, read from the system's own home resolution.
         *
         * `resolveActivity` on a HOME intent answers with the default launcher, or with the
         * resolver activity when the user has never chosen one. Both of those are the
         * third-party answer as far as a preset is concerned -- the resolver is on screen
         * precisely because something other than LightOS is installed and competing.
         */
        fun detect(context: Context): Launcher {
            val pkg = homePackage(context) ?: return LightOs
            return if (pkg.startsWith(LIGHTOS_PKG)) LightOs else ThirdParty
        }

        /** The default launcher's package id, or null when the system will not say. */
        fun homePackage(context: Context): String? = runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager
                .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo
                ?.packageName
                ?.takeIf { it.isNotBlank() && it != "android" }
        }.getOrNull()

        /** What to call it on screen. The app's own name when it has one, the id when it does not. */
        fun homeLabel(context: Context): String {
            val pkg = homePackage(context) ?: return "LightOS"
            if (pkg.startsWith(LIGHTOS_PKG)) return "LightOS"
            return runCatching {
                val info = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(info).toString().takeIf { it.isNotBlank() }
            }.getOrNull() ?: pkg
        }
    }
}

/**
 * One starting point, and what it will do before it does it.
 *
 * [lines] is not decoration. A preset writes about forty settings at once and the person tapping
 * it has no way to see that happen, so the screen lists it first and the list is generated from
 * the same launcher answer the preset itself is given. It also happens to be the only page in
 * this app that says what a *whole* working setup looks like, which is the thing the people
 * asking for a setup guide are actually asking for.
 */
sealed interface Preset {

    val id: String
    val label: String
    val sub: String

    /** What this preset will set, in the order a person would look for it. */
    fun lines(launcher: Launcher, launcherName: String): List<String>

    /** Write it. The store has already been cleared by [Presets.apply]. */
    fun write(prefs: Prefs, launcher: Launcher, launcherPkg: String?)

    /**
     * Factory settings: the phone as a fresh install would find it.
     *
     * It writes nothing at all, which is the honest implementation -- every default in this app
     * lives in the getter that reads it, so an empty store *is* the default configuration. That
     * also makes this the repair tool. A phone somebody has turned everything on in comes back
     * here in one tap without an uninstall, and an uninstall is the one repair that costs the adb
     * grants.
     */
    data object Default : Preset {

        override val id = "default"
        override val label = "DEFAULT"
        override val sub = "the app as it ships"

        override fun lines(launcher: Launcher, launcherName: String) = listOf(
            "The wheel changes brightness in apps that do not scroll",
            "Wheel click is the flashlight, camera button opens the camera",
            "Home goes home, holding it reaches the LightOS dashboard",
            "Left edge swipes back, a long swipe opens the app switcher",
            "The brightness and volume readouts are on",
            "The lock face, banners and per-app color are off",
        )

        override fun write(prefs: Prefs, launcher: Launcher, launcherPkg: String?) = Unit
    }

    /**
     * What the developer runs, on his own phone, every day.
     *
     * Everything here is switched on somewhere in this app already. What a preset adds is the
     * knowledge of which combination is a phone somebody has actually lived with for a month --
     * which turns out to be the part that cannot be written in a row's subtitle.
     *
     * Two things are deliberately left off. Keyboard replace is a prototype and says so, and the
     * hotspot needs a pairing this preset cannot do for you.
     */
    data object DevsChoice : Preset {

        override val id = "devs"
        override val label = "DEV'S CHOICE"
        override val sub = "the setup the person who writes this app uses"

        override fun lines(launcher: Launcher, launcherName: String) = buildList {
            add("Wheel click: flashlight; hold opens the app switcher; double tap switches the turn")
            add("Camera button: the camera; hold puts the lock face up")
            if (launcher == Launcher.ThirdParty) {
                add("Home: $launcherName; hold reaches the LightOS dashboard; double tap the switcher")
                add("The switcher's pinned Home row opens $launcherName")
            } else {
                add("Home: the dashboard; hold opens system settings; double tap the switcher")
            }
            add("Both edges live: swipe in to go back, a long swipe opens the switcher")
            add("The buttons keep working on LightOS's own screens, so the torch works locked")
            add("The lock face is on, with notes, music and callers")
            add("Banners are on, so apps this phone hides can still reach you")
            add("Per-app color is on, and follows the built-in table")
            add("Brightness and volume readouts on, with the volume strip tappable")
        }

        override fun write(prefs: Prefs, launcher: Launcher, launcherPkg: String?) {
            prefs.enabled = true
            prefs.wheelCursor = true
            prefs.logKeys = true

            // Buttons. The wheel's double tap and home's are what the app ships with; they are
            // written anyway so this reads as one description of a phone rather than a diff.
            prefs.setAction(Button.WheelClick, Gesture.Tap, Action.Torch)
            // **The switcher, not the lock face, and not for symmetry.**
            //
            // A hold bound to Switcher is the one hold that fires at the threshold rather than at
            // the release -- see ControlService.onButton. That matters here specifically:
            // light-reports#79 was the wheel hold letting go a fraction early and turning the
            // flashlight on instead, which is a thing you cannot learn to stop doing because
            // there is no way to feel how long is long enough. Any other action on this hold
            // brings that report back.
            prefs.setAction(Button.WheelClick, Gesture.Hold, Action.Switcher)
            prefs.setAction(Button.WheelClick, Gesture.DoubleTap, Action.SwitchTurn)

            prefs.setAction(Button.Camera, Gesture.Tap, Action.OpenCamera)
            prefs.setAction(Button.Camera, Gesture.Hold, Action.ShowLock)
            prefs.setAction(Button.Camera, Gesture.DoubleTap, Action.None)

            // **Home, which is the binding a preset can actually break a phone with.**
            //
            // The tap is DefaultHome on both sides of the launcher question, and that is not a
            // missed opportunity to be clever. DefaultHome agrees with what the phone was going
            // to do anyway, so the service is free to shadow the press instead of swallowing it
            // -- see Action.picksDestination. A tap that names somewhere else is a race with the
            // launcher the system is already summoning, and losing it is how somebody ends up on
            // the idle face wondering what this app did.
            prefs.setAction(Button.Home, Gesture.Tap, Action.DefaultHome)
            prefs.setAction(
                Button.Home,
                Gesture.Hold,
                // On a stock phone LightOsHome is where the tap already went, so the hold is
                // spent on the other thing LightOS ships no way to reach.
                if (launcher == Launcher.ThirdParty) Action.LightOsHome else Action.OpenSettings,
            )
            prefs.setAction(Button.Home, Gesture.DoubleTap, Action.Switcher)

            // The one pair that already works on this phone. Consuming them would be taking a
            // function away to add one.
            prefs.setAction(Button.VolumeUp, Gesture.Tap, Action.PassThrough)
            prefs.setAction(Button.VolumeDown, Gesture.Tap, Action.PassThrough)

            // The wheel
            prefs.unknownAppTurn = TurnAction.Brightness
            prefs.lightOsScreens = true
            prefs.lightOsBrightness = true
            prefs.cameraOnLightOs = true
            prefs.switcherOnLightOs = true

            // Edges. Both on, both at the shipped bindings -- the left goes back and the right
            // opens the switcher, mirrored, which needs no opinion about which hand you hold it in.
            prefs.leftEdgeOn = true
            prefs.rightEdgeOn = true
            prefs.edgeIndicator = true
            prefs.edgeHaptics = true

            // The switcher, and the row pinned under it. On a stock phone Home is the dashboard
            // and the row has nothing to add, so it is left to point at the default.
            prefs.switcherHomeRow = true
            if (launcher == Launcher.ThirdParty && launcherPkg != null) {
                prefs.switcherHomePkg = launcherPkg
            }

            // The screen
            prefs.showReadout = true
            prefs.showVolume = true
            prefs.volumePin = true
            prefs.colorAutoSwitch = true

            prefs.lockScreen = true
            prefs.lockNotes = true
            prefs.lockMedia = true
            prefs.lockCalls = true
            prefs.callBoost = true

            prefs.banner = true
            prefs.bannerWake = true

            // A prototype stays off, whoever is asking.
            prefs.keyboardReplace = false
        }
    }
}

/**
 * The catalog, and the one way to apply one.
 *
 * ### An exported file beats anything written here
 *
 * [Preset.DevsChoice] is a description of a phone, maintained by hand, and a description drifts
 * from the phone it describes the first week nobody updates it. So a preset can also arrive as a
 * settings file in `assets/presets/`, in exactly the format "Save settings to a file" writes --
 * which means keeping this honest is an export, a copy and a commit rather than an edit to this
 * class. When the file is there it wins, and [source] says which one landed so the screen can be
 * specific instead of vague.
 */
object Presets {

    val all: List<Preset> = listOf(Preset.Default, Preset.DevsChoice)

    /** Where a preset's values came from, for the line under the button after it is applied. */
    enum class Source { BuiltIn, File }

    fun find(id: String): Preset? = all.firstOrNull { it.id == id }

    /**
     * Clear every setting, then write [preset].
     *
     * The order is the contract. See the wipe note on [Preset].
     */
    fun apply(context: Context, preset: Preset): Source {
        val prefs = Prefs(context)
        val launcher = Launcher.detect(context)
        val launcherPkg = Launcher.homePackage(context)

        prefs.resetToShipped()

        val fromFile = readAsset(context, preset)
        if (fromFile != null && prefs.importJson(fromFile) > 0) return Source.File

        preset.write(prefs, launcher, launcherPkg)
        return Source.BuiltIn
    }

    /** True when this preset ships a settings file and will use it. */
    fun hasFile(context: Context, preset: Preset): Boolean = readAsset(context, preset) != null

    private fun readAsset(context: Context, preset: Preset): String? = runCatching {
        context.assets.open("presets/${preset.id}.json").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }.getOrNull()
}
