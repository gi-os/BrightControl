package com.gios.lightcontrol;

/**
 * The colour a sideloaded app asks BrightControl for, so it does not have to hold
 * WRITE_SECURE_SETTINGS itself.
 *
 * This file is the wire contract and it is byte-identical in BrightControl and in
 * light-common. An AIDL interface is identified by its descriptor, which is the package
 * and name written here, so the two copies must not drift: a renamed package or a
 * reordered method is a bind that succeeds and then throws.
 */
interface IColorProvider {

    /**
     * State what the caller wants on screen right now.
     *
     * state: 0 clears the request, 1 asks for colour, 2 asks for monochrome.
     *
     * token is the life of the request. Pass a plain Binder the calling process holds for as
     * long as it wants to be able to ask; when that process dies, the request is dropped.
     * A token rather than the connection because AIDL hands the server no per-client
     * identity — onBind returns one binder for every client and onUnbind fires when the last
     * of them goes — so there is nothing else here to attach a death to. Without one, an app
     * that is swiped away or crashes while holding colour leaves the phone repainted with
     * nothing left to take it back, and on this phone there is no settings screen to undo it
     * by hand.
     *
     * Returns 1 when BrightControl is driving the screen and the caller must not write the
     * daltonizer itself, 0 when BrightControl is present but inert (no grant, or its colour
     * switch is off) and the caller should fall back to its own writer if it has one, and
     * -1 when the request was refused.
     *
     * The request is honoured only while the caller is the app in front. BrightControl
     * knows which app that is and takes the caller identity from the kernel, so a request
     * can only ever repaint a screen its sender is already occupying.
     */
    int want(int state, IBinder token);

    /**
     * Which revision of this contract the other side speaks. 1 is the first.
     *
     * Asked rather than assumed because the two APKs version separately and a phone will
     * routinely carry a new library against an old BrightControl. A client that needs
     * something added later can find out instead of calling a method that is not there.
     */
    int protocol();
}
