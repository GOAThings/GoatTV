package cc.slokker.itv.data

import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.decomposedStringWithCompatibilityMapping

@Suppress("CAST_NEVER_SUCCEEDS")
internal actual fun nfkd(s: String): String = NSString.create(string = s).decomposedStringWithCompatibilityMapping
