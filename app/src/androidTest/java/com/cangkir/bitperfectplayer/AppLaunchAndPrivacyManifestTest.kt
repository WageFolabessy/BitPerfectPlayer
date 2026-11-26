package com.cangkir.bitperfectplayer

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device instrumentation smoke test verifying debug variant isolation
 * (`com.cangkir.bitperfectplayer.debug`), strict 100%-offline privacy manifest boundaries at
 * runtime, and clean [MainActivity] launch without uncaught crashes.
 */
@RunWith(AndroidJUnit4::class)
class AppLaunchAndPrivacyManifestTest {

    @Test
    fun targetContext_usesDebugPackageAndExcludesAllForbiddenPermissions() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.cangkir.bitperfectplayer.debug", targetContext.packageName)

        val pkgInfo = getPackagePermissions(targetContext.packageManager, targetContext.packageName)
        val requestedPermissions = pkgInfo.requestedPermissions?.toSet().orEmpty()

        val forbiddenPermissions = listOf(
            "android.permission.INTERNET",
            "android.permission.RECORD_AUDIO",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
        )
        forbiddenPermissions.forEach { forbidden ->
            assertFalse(
                "Forbidden permission '$forbidden' must never be declared in runtime manifest",
                forbidden in requestedPermissions,
            )
        }
        assertTrue(
            "MODIFY_AUDIO_SETTINGS should be present for API 34+ USB mixer negotiation",
            "android.permission.MODIFY_AUDIO_SETTINGS" in requestedPermissions,
        )
    }

    @Test
    fun mainActivity_launchesCleanlyWithoutCrashing() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse("MainActivity must remain active after launch", activity.isFinishing)
                assertEquals("com.cangkir.bitperfectplayer.debug", activity.packageName)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun getPackagePermissions(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }
}
