package dev.amrakshay.atvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADB transport reads its state out of `dumpsys`, which is human-readable
 * output and not an API. These fix the shapes seen on a real device so a change
 * in them fails here rather than as a volume bar that never moves.
 */
class AdbRemoteTest {

    /** Verbatim from an E-Gate projector (t950s_be311, Android 13). */
    private val state = """
        |   Muted: false
        |   Muted Internally: false
        |   Min: 0
        |   Max: 100
        |   streamVolume:57
        |   Current: 2 (speaker): 57, 80 (bt_a2dp): 78, 400 (hdmi): 50
        |   Devices: speaker(2)
        |    topResumedActivity=ActivityRecord{7a130c u0 org.videolan.vlc/.StartActivity} t8}
    """.trimMargin()

    @Test
    fun `volume comes off the STREAM_MUSIC block`() {
        val volume = AdbRemote.parseVolume(state)!!
        assertEquals(57, volume.level)
        assertEquals(100, volume.max)
        assertEquals(false, volume.muted)
    }

    @Test
    fun `Muted Internally is not mistaken for Muted`() {
        val muted = AdbRemote.parseVolume(state.replace("Muted: false", "Muted: true"))!!
        assertTrue(muted.muted)
    }

    @Test
    fun `foreground package is read without the activity`() {
        assertEquals("org.videolan.vlc", AdbRemote.parseForegroundPackage(state))
    }

    @Test
    fun `a device that says nothing useful leaves the state alone`() {
        assertNull(AdbRemote.parseVolume("nothing here"))
        assertNull(AdbRemote.parseForegroundPackage("nothing here"))
    }

    private val appsOutput = """
        |7 activities found:
        |  Activity #0:
        |    com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity
        |    org.videolan.vlc/.StartActivity
        |    com.amrakshay.movieserve/.MainActivity
        |    com.htc.htclauncherhighenglishd08/com.mao.launcher.activity.BarrelActivity
        |${AdbRemote.HOME_MARKER}
        |    com.htc.htclauncherhighenglishd08/com.mao.launcher.activity.BarrelActivity
        |13
    """.trimMargin()

    @Test
    fun `apps are listed once each, launchers left out`() {
        val apps = AdbRemote.parseApps(appsOutput)
        assertEquals(listOf("YouTube", "VLC", "Movieserve"), apps.map { it.label })
        assertEquals("org.videolan.vlc/.StartActivity", apps[1].target)
    }

    @Test
    fun `the same package listed under both categories appears once`() {
        val twice = appsOutput.replace(
            "    org.videolan.vlc/.StartActivity",
            "    org.videolan.vlc/.StartActivity\n    org.videolan.vlc/.OtherActivity",
        )
        assertEquals(1, AdbRemote.parseApps(twice).count { it.label == "VLC" })
    }

    @Test
    fun `the Android version is the last line`() {
        assertEquals("13", AdbRemote.parseVersion(appsOutput))
    }

    @Test
    fun `package names are made presentable when they are not known`() {
        assertEquals("YouTube", AdbRemote.labelFor("com.google.android.youtube.tv"))
        assertEquals("Netflix", AdbRemote.labelFor("com.netflix.mediaclient"))
        // No entry for this one, so the last word that is not boilerplate wins.
        assertEquals("Movieserve", AdbRemote.labelFor("com.amrakshay.movieserve"))
        assertEquals("Updater", AdbRemote.labelFor("com.droidlogic.updater"))
        // A known prefix wins over the generic rule, flavour suffix and all.
        assertEquals("AirScreen", AdbRemote.labelFor("com.ionitech.airscreen.tv"))
    }

    @Test
    fun `the banner names the device`() {
        val banner = "device::ro.product.name=t950s_be311;ro.product.model=SMART TV;features=cmd"
        assertEquals("SMART TV", AdbRemote.deviceFromBanner(banner).model)
    }

    @Test
    fun `shell quoting survives an apostrophe`() {
        // A search for "bob's burgers" must not end the quoted argument early
        // and hand the rest of the title to the shell as commands.
        assertEquals("'bob'\\''s burgers'", AdbRemote.singleQuote("bob's burgers"))
    }
}
