package com.openbackgroundgeolocation.engine

/**
 * Decides whether a raw fix is worth recording.
 *
 * Pure functions over primitives — no `android.location.Location` — so the
 * rules that determine what reaches the customer's database can be unit
 * tested directly.
 */
object LocationFilters {

    /** Why a fix was rejected, for logging and tests. */
    enum class Rejection { NONE, POOR_ACCURACY, DUPLICATE, IMPOSSIBLE_SPEED }

    /**
     * @param accuracyMeters reported horizontal accuracy of the new fix
     * @param minimumAccuracyMeters reject fixes worse than this; `0` disables
     * @param isDuplicateCoordinate new fix has identical coordinates to the previous
     * @param allowIdenticalLocations keep duplicate coordinates anyway
     * @param distanceFilterMeters `0` means "report every fix" (realtime mode)
     * @param metersFromPrevious distance travelled since the previous fix
     * @param secondsSincePrevious elapsed time since the previous fix
     * @param speedJumpFilterMetersPerSecond reject implied speeds above this
     */
    fun evaluate(
        accuracyMeters: Float,
        minimumAccuracyMeters: Float,
        isDuplicateCoordinate: Boolean,
        allowIdenticalLocations: Boolean,
        distanceFilterMeters: Float,
        metersFromPrevious: Float,
        secondsSincePrevious: Double,
        speedJumpFilterMetersPerSecond: Float
    ): Rejection {
        // Accuracy gate first: a wildly inaccurate fix should never reach the
        // database, the odometer or the server.
        if (minimumAccuracyMeters > 0f && accuracyMeters > minimumAccuracyMeters) {
            return Rejection.POOR_ACCURACY
        }

        // In realtime mode (distanceFilter 0) identical coordinates are
        // meaningful — suppressing them would silently drop updates.
        if (isDuplicateCoordinate && !allowIdenticalLocations && distanceFilterMeters > 0f) {
            return Rejection.DUPLICATE
        }

        // Reject GPS "teleports": a jump no real vehicle could have made.
        if (secondsSincePrevious > 0) {
            val impliedSpeed = metersFromPrevious / secondsSincePrevious
            if (impliedSpeed > speedJumpFilterMetersPerSecond) {
                return Rejection.IMPOSSIBLE_SPEED
            }
        }
        return Rejection.NONE
    }

    /**
     * Distance filter scaled by speed ("elasticity"): at highway pace the
     * filter widens so a long drive does not produce thousands of points.
     * Speed is bucketed into 5 m/s bands to avoid re-requesting updates on
     * every minor fluctuation.
     */
    fun speedBand(speedMetersPerSecond: Float): Int =
        if (speedMetersPerSecond <= 0f) 0 else (speedMetersPerSecond / 5f).toInt()

    fun effectiveDistanceFilter(
        distanceFilterMeters: Float,
        speedBand: Int,
        elasticityMultiplier: Float,
        disableElasticity: Boolean
    ): Float {
        if (disableElasticity) return distanceFilterMeters
        return distanceFilterMeters * (1f + speedBand * elasticityMultiplier)
    }

    /**
     * Whether the step between two fixes counts as real travel.
     *
     * GPS wanders by several metres even on a phone lying on a table, so
     * naively summing every step inflates the odometer while parked — a
     * 12-second stationary interval can invent tens of metres an hour. Three
     * guards:
     *
     *  1. Only accumulate while the SDK considers the device to be moving.
     *  2. Ignore fixes less accurate than `desiredOdometerAccuracy`.
     *  3. Ignore steps shorter than the position uncertainty — if you appear
     *     to move 5 m but the fix is ±15 m, that displacement is statistically
     *     indistinguishable from noise.
     */
    fun contributesToOdometer(
        accuracyMeters: Float,
        desiredOdometerAccuracyMeters: Float,
        distanceMeters: Float,
        isMoving: Boolean,
        previousAccuracyMeters: Float = accuracyMeters
    ): Boolean {
        if (!isMoving) return false
        if (accuracyMeters > desiredOdometerAccuracyMeters) return false
        // Compare against the *combined* uncertainty of both fixes. Two points
        // each accurate to ±100 m can sit 200 m apart with the device having
        // not moved at all, so anything shorter than that proves nothing.
        if (distanceMeters < accuracyMeters + previousAccuracyMeters) return false
        return true
    }
}
