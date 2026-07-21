package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPermissionTest {

    @Test
    fun appDoesNotRequestNotificationPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val requestedPermissions = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()

        assertFalse(
            "Notification permission must not return to the first-connect flow",
            Manifest.permission.POST_NOTIFICATIONS in requestedPermissions,
        )
    }
}
