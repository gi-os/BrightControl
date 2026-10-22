package com.gios.lightcontrol.shortcuts

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import android.util.Xml
import com.gios.lightcontrol.Action
import org.xmlpull.v1.XmlPullParser

/**
 * The shortcuts other apps offer, read the two ways open to an app that is not the home screen.
 *
 * Android keeps every app's shortcuts in one table, and `LauncherApps.getShortcuts` answers only
 * the default launcher. So the list a long press on an icon shows is out of reach. Two older
 * routes are not:
 *
 *  - **Declared shortcuts.** An app lists its fixed shortcuts in an XML file named by the
 *    `android.app.shortcuts` meta-data on its launcher activity. That file is an ordinary resource
 *    any app can read, and each entry is a plain intent. This is where "Add task" lives.
 *  - **Made shortcuts.** An app with a "create shortcut" screen (`ACTION_CREATE_SHORTCUT`) asks a
 *    question — which project, which contact — and answers with an intent. That screen was built
 *    for home screens, but any app can open it and read the answer.
 *
 * What neither route reaches is the shortcuts an app publishes at run time, the recent chats and
 * the last-opened lists. Those exist only in the system's table.
 *
 * The other limit is who may start the screen. A launcher starts a shortcut *as the app that owns
 * it*, so the screen does not have to be exported. This app starts it as itself, so a screen the
 * app keeps private cannot be opened from here. Those are still listed, dimmed, so a missing
 * shortcut reads as the app's choice rather than as a bug.
 */
object AppShortcuts {

    /** One declared shortcut. */
    data class Declared(
        val pkg: String,
        val app: String,
        val label: String,
        val intent: Intent,
        /** False when the screen is private to its app, or needs a permission this app lacks. */
        val startable: Boolean,
    ) {
        fun action(): Action.Shortcut = Action.Shortcut(pkg, label, intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    /** One app's "create shortcut" screen. */
    data class Maker(val pkg: String, val app: String, val label: String, val component: ComponentName)

    /** What came back from a [Maker]. */
    sealed interface Made {
        data class Bound(val action: Action.Shortcut) : Made

        /** Backed out, or answered with nothing. */
        data object Nothing : Made

        /** Answered with a pin request, which only the default launcher can open. */
        data object HomeScreenOnly : Made

        /** Answered with a screen this app may not start. */
        data object Private : Made
    }

    private const val ANDROID = "http://schemas.android.com/apk/res/android"
    private const val META = "android.app.shortcuts"

    /**
     * Every declared shortcut on the phone, sorted by app and then in the app's own order.
     *
     * One manifest file per app, read from that app's resources. Each app is read inside its own
     * catch: a file one app wrote badly costs that app's rows and nothing else.
     */
    fun declared(pm: PackageManager, self: String): List<Declared> {
        val launchable = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = runCatching {
            pm.queryIntentActivities(launchable, PackageManager.GET_META_DATA or PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        return activities
            .map { it.activityInfo }
            .filter { it.metaData?.containsKey(META) == true }
            .distinctBy { it.packageName }
            .flatMap { info ->
                runCatching { read(pm, info, self) }.getOrDefault(emptyList())
            }
            .sortedBy { it.app.lowercase() }
    }

    private fun read(pm: PackageManager, info: ActivityInfo, self: String): List<Declared> {
        val pkg = info.packageName
        val app = info.applicationInfo.loadLabel(pm).toString()
        val res = pm.getResourcesForApplication(info.applicationInfo)
        val parser = info.loadXmlMetaData(pm, META) ?: return emptyList()
        val found = mutableListOf<Declared>()
        parser.use {
            var label: String? = null
            var enabled = true
            var last: Intent? = null
            while (true) {
                when (parser.next()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "shortcut" -> {
                            label = text(res, parser, "shortcutShortLabel")
                                ?: text(res, parser, "shortcutLongLabel")
                            enabled = parser.getAttributeBooleanValue(ANDROID, "enabled", true)
                            last = null
                        }
                        // A shortcut may carry several intents: the back stack, then the screen.
                        // The last one is the screen, and it is the one kept.
                        "intent" -> last = runCatching {
                            Intent.parseIntent(res, parser, Xml.asAttributeSet(parser))
                        }.getOrNull()
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "shortcut") {
                        val intent = last
                        val name = label?.takeIf { it.isNotBlank() }
                        if (enabled && intent != null && name != null) {
                            if (intent.action == null) intent.action = Intent.ACTION_VIEW
                            // Never let a shortcut with no target land in some other app.
                            if (intent.component == null && intent.`package` == null) {
                                intent.`package` = pkg
                            }
                            found += Declared(pkg, app, name, intent, startable(pm, intent, self))
                        }
                        label = null
                        last = null
                    }
                }
            }
        }
        return found
    }

    /** A string attribute, followed through to the app's own resources when it is a reference. */
    private fun text(res: Resources, parser: XmlResourceParser, attr: String): String? {
        val id = parser.getAttributeResourceValue(ANDROID, attr, 0)
        if (id != 0) return runCatching { res.getString(id) }.getOrNull()
        return parser.getAttributeValue(ANDROID, attr)
    }

    /** True if this app is allowed to start [intent]'s screen. */
    fun startable(pm: PackageManager, intent: Intent, self: String): Boolean = runCatching {
        val info = pm.resolveActivity(intent, 0)?.activityInfo ?: return false
        info.exported && (info.permission == null || info.packageName == self ||
            pm.checkPermission(info.permission, self) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    /** Every app's "create shortcut" screen, sorted by app. */
    fun makers(pm: PackageManager): List<Maker> = runCatching {
        pm.queryIntentActivities(Intent(Intent.ACTION_CREATE_SHORTCUT), 0)
            .map { it.activityInfo to it.loadLabel(pm).toString() }
            .filter { (info, _) -> info.exported }
            .map { (info, label) ->
                Maker(
                    pkg = info.packageName,
                    app = info.applicationInfo.loadLabel(pm).toString(),
                    label = label,
                    component = ComponentName(info.packageName, info.name),
                )
            }
            .sortedWith(compareBy({ it.app.lowercase() }, { it.label.lowercase() }))
    }.getOrDefault(emptyList())

    /** The intent that opens [maker]'s screen, for `startActivityForResult`. */
    fun ask(maker: Maker): Intent = Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(maker.component)

    /**
     * Turn a [Maker]'s answer into a binding.
     *
     * The answer has two possible shapes. The old one carries the shortcut's intent and name as
     * extras, and is what this reads. The new one carries only a pin request, and the shortcut in
     * it can be opened by the default launcher and nobody else, so it is reported as such rather
     * than bound to a press that would do nothing. AndroidX writes both shapes into one answer, so
     * most apps built in the last few years still give the old one.
     */
    @Suppress("DEPRECATION")
    fun made(pm: PackageManager, maker: Maker, data: Intent?, self: String): Made {
        data ?: return Made.Nothing
        val target: Intent? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)
            } else {
                data.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT)
            }
        }.getOrNull()
        if (target == null) {
            return if (data.hasExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST)) Made.HomeScreenOnly
            else Made.Nothing
        }
        if (target.component == null && target.`package` == null) target.`package` = maker.pkg
        if (!startable(pm, target, self)) return Made.Private
        val name = data.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)?.takeIf { it.isNotBlank() }
            ?: maker.label
        return Made.Bound(Action.Shortcut(maker.pkg, name, target.toUri(Intent.URI_INTENT_SCHEME)))
    }
}
