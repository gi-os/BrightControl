package com.gios.lightcontrol

import com.gios.lightcontrol.notify.CallBannerRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallBannerRuleTest {

    private fun shows(
        enabled: Boolean = true,
        ringing: Boolean = true,
        awake: Boolean = true,
        front: String? = "app.luma",
        dialer: String? = "com.lightos",
        swipedAway: Boolean = false,
        switcherUp: Boolean = false,
    ) = CallBannerRule.shows(enabled, ringing, awake, front, dialer, swipedAway, switcherUp)

    @Test fun ringingInAnAppShows() = assertTrue(shows())

    @Test fun unknownFrontStillShows() = assertTrue(shows(front = null))

    @Test fun settingOffHides() = assertFalse(shows(enabled = false))

    @Test fun answeredCallHides() = assertFalse(shows(ringing = false))

    @Test fun lockedOrAsleepHides() = assertFalse(shows(awake = false))

    @Test fun swipedAwayStaysAway() = assertFalse(shows(swipedAway = true))

    @Test fun switcherUpHides() = assertFalse(shows(switcherUp = true))

    @Test fun lightOsInFrontHides() = assertFalse(shows(front = "com.lightos.dialer"))

    @Test fun thirdPartyDialerInFrontHides() =
        assertFalse(shows(front = "com.example.dialer", dialer = "com.example.dialer"))
}
