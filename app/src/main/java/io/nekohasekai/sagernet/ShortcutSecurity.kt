package io.nekohasekai.sagernet

import android.app.Activity

/**
 * ShortcutManager launches shortcut intents with the publisher application's privileges, including
 * intents targeting unexported activities. Keeping the control activities unexported is the caller
 * authentication boundary on every supported Android version.
 */
internal fun Activity.isTrustedShortcutLaunch() =
    !packageManager.getActivityInfo(componentName, 0).exported
