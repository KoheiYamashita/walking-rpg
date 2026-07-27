package com.walkingrpg.shared.platform

import platform.UIKit.UIDevice

private class IOSPlatform : Platform {
    override val name: String =
        UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun currentPlatform(): Platform = IOSPlatform()
