package com.phairplay.service

/**
 * SurfaceProviderPolicy — guards [PhairPlayService] surface provider registration.
 *
 * WHY: MainActivity is destroyed on Home while mirroring continues in the service.
 * A new Activity may register its provider before the old instance's onDestroy runs;
 * only the registered owner may clear the provider.
 */
object SurfaceProviderPolicy {

    /** True when [clearingOwner] may clear the currently registered surface provider. */
    fun shouldClear(registeredOwner: Any?, clearingOwner: Any): Boolean =
        registeredOwner === clearingOwner
}
