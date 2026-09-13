package com.gios.lightcontrol.keys

/** Which way [com.gios.lightcontrol.Action.SwitchLayer] goes on this press. */
enum class Layer {
    /** Bring LightOS's dashboard over. */
    LightOs,

    /** Leave for whichever launcher the user chose. */
    Launcher,
}

/**
 * Which side of the phone a press of the layer toggle is heading for.
 *
 * Read from the app in front rather than from a remembered side. A remembered side goes wrong the
 * moment anything the toggle did not do moves the phone -- an app opened from a notification, a
 * call, a launcher coming forward on its own -- and it goes wrong invisibly: the button simply
 * takes you the way you have just come. What is on screen cannot be out of date.
 *
 * **Everything that is not LightOS counts as the far side**, third-party app and launcher alike.
 * That is what a thumb means by the gesture: not "cycle through the two homes" but "put the
 * dashboard over", from wherever it is standing, with one finger.
 *
 * A null front -- a service that has just been rebound and seen no window event yet -- answers
 * [Layer.LightOs]. Being wrong there costs one press that goes to a dashboard you were already
 * looking at, and it corrects itself on the next window change. The other default would cost a
 * press that walks out of LightOS when you asked to go into it.
 */
object LayerSwitch {
    fun to(front: String?, lightOsPrefix: String): Layer =
        if (front != null && front.startsWith(lightOsPrefix)) Layer.Launcher else Layer.LightOs
}
