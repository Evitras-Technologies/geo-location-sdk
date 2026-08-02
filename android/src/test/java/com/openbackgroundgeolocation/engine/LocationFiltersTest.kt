package com.openbackgroundgeolocation.engine

import com.openbackgroundgeolocation.engine.LocationFilters.Rejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules deciding which fixes reach the database, the odometer and the
 * customer's server.
 */
class LocationFiltersTest {

    /** A clean fix that should pass every filter, with overridable fields. */
    private fun evaluate(
        accuracy: Float = 8f,
        minimumAccuracy: Float = 0f,
        duplicate: Boolean = false,
        allowIdentical: Boolean = false,
        distanceFilter: Float = 10f,
        metersMoved: Float = 15f,
        secondsElapsed: Double = 5.0,
        speedJumpFilter: Float = 300f
    ) = LocationFilters.evaluate(
        accuracyMeters = accuracy,
        minimumAccuracyMeters = minimumAccuracy,
        isDuplicateCoordinate = duplicate,
        allowIdenticalLocations = allowIdentical,
        distanceFilterMeters = distanceFilter,
        metersFromPrevious = metersMoved,
        secondsSincePrevious = secondsElapsed,
        speedJumpFilterMetersPerSecond = speedJumpFilter
    )

    // ── Accuracy gate ─────────────────────────────────────────────────────────

    @Test
    fun `good fix passes`() {
        assertEquals(Rejection.NONE, evaluate())
    }

    @Test
    fun `fix worse than the accuracy limit is rejected`() {
        assertEquals(Rejection.POOR_ACCURACY, evaluate(accuracy = 120f, minimumAccuracy = 50f))
    }

    @Test
    fun `fix exactly at the accuracy limit is accepted`() {
        assertEquals(Rejection.NONE, evaluate(accuracy = 50f, minimumAccuracy = 50f))
    }

    @Test
    fun `accuracy limit of zero disables the gate`() {
        assertEquals(Rejection.NONE, evaluate(accuracy = 5_000f, minimumAccuracy = 0f))
    }

    // ── Duplicate coordinates ─────────────────────────────────────────────────

    @Test
    fun `duplicate coordinate is rejected in normal mode`() {
        assertEquals(Rejection.DUPLICATE, evaluate(duplicate = true, metersMoved = 0f))
    }

    @Test
    fun `duplicate coordinate is kept in realtime mode`() {
        // distanceFilter 0 means "report every fix" — suppressing duplicates
        // there would silently drop updates while parked.
        assertEquals(
            Rejection.NONE,
            evaluate(duplicate = true, distanceFilter = 0f, metersMoved = 0f)
        )
    }

    @Test
    fun `duplicate coordinate is kept when explicitly allowed`() {
        assertEquals(
            Rejection.NONE,
            evaluate(duplicate = true, allowIdentical = true, metersMoved = 0f)
        )
    }

    // ── Speed-jump (GPS teleport) filter ──────────────────────────────────────

    @Test
    fun `impossible jump is rejected`() {
        // 10 km in 1 second.
        assertEquals(
            Rejection.IMPOSSIBLE_SPEED,
            evaluate(metersMoved = 10_000f, secondsElapsed = 1.0)
        )
    }

    @Test
    fun `highway speed is accepted`() {
        // 40 m/s ≈ 144 km/h — fast but physically real.
        assertEquals(Rejection.NONE, evaluate(metersMoved = 400f, secondsElapsed = 10.0))
    }

    @Test
    fun `zero elapsed time skips the speed check instead of dividing by zero`() {
        assertEquals(Rejection.NONE, evaluate(metersMoved = 5_000f, secondsElapsed = 0.0))
    }

    @Test
    fun `accuracy is checked before the duplicate rule`() {
        // A fix that is both inaccurate and duplicate reports the accuracy
        // problem, which is the more useful diagnosis.
        assertEquals(
            Rejection.POOR_ACCURACY,
            evaluate(accuracy = 200f, minimumAccuracy = 50f, duplicate = true)
        )
    }

    // ── Elasticity ────────────────────────────────────────────────────────────

    @Test
    fun `walking speed stays in the base band`() {
        assertEquals(0, LocationFilters.speedBand(1.4f))
    }

    @Test
    fun `city driving lands in a higher band`() {
        assertEquals(2, LocationFilters.speedBand(13.9f)) // ~50 km/h
    }

    @Test
    fun `negative speed is treated as stationary`() {
        assertEquals(0, LocationFilters.speedBand(-1f))
    }

    @Test
    fun `elasticity widens the distance filter with speed`() {
        val walking = LocationFilters.effectiveDistanceFilter(10f, 0, 1f, false)
        val driving = LocationFilters.effectiveDistanceFilter(10f, 2, 1f, false)
        assertEquals(10f, walking, 1e-4f)
        assertEquals(30f, driving, 1e-4f) // 10 * (1 + 2*1)
    }

    @Test
    fun `disabling elasticity keeps the filter constant`() {
        assertEquals(
            10f,
            LocationFilters.effectiveDistanceFilter(10f, 5, 1f, true),
            1e-4f
        )
    }

    // ── Odometer ──────────────────────────────────────────────────────────────

    @Test
    fun `real movement contributes to the odometer`() {
        assertTrue(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 10f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 50f,
                isMoving = true
            )
        )
    }

    @Test
    fun `inaccurate fix does not inflate the odometer`() {
        assertFalse(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 250f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 300f,
                isMoving = true
            )
        )
    }

    @Test
    fun `stationary device never accumulates distance`() {
        // Regression: 12-second stationary fixes were summing GPS drift into
        // the odometer, inventing ~50 m while the phone sat on a table.
        assertFalse(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 8f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 6f,
                isMoving = false
            )
        )
    }

    @Test
    fun `network-positioning jitter does not inflate the odometer`() {
        // Regression from a real trace: a phone indoors bounced between two
        // network-derived positions ~400 m apart, both with ±100 m accuracy,
        // and the odometer climbed 2 km while the device sat still. The step
        // must exceed the *combined* uncertainty of both fixes.
        assertFalse(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 100f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 150f,
                isMoving = true,
                previousAccuracyMeters = 100f
            )
        )
    }

    @Test
    fun `a 400m jump between coarse fixes is rejected as an impossible speed`() {
        // The same trace, caught earlier in the pipeline: 400 m in 5 s is
        // 80 m/s (≈290 km/h). The old 300 m/s default let it through.
        assertEquals(
            Rejection.IMPOSSIBLE_SPEED,
            evaluate(distance = 400f, elapsedSeconds = 5.0, speedJumpFilter = 55f)
        )
    }

    @Test
    fun `genuine highway travel still passes the tightened filter`() {
        // 30 m/s ≈ 108 km/h must not be mistaken for a teleport.
        assertEquals(
            Rejection.NONE,
            evaluate(distance = 150f, elapsedSeconds = 5.0, speedJumpFilter = 55f)
        )
    }

    @Test
    fun `step smaller than the position uncertainty is treated as noise`() {
        // Appearing to move 5 m with a ±15 m fix is indistinguishable from
        // jitter, so it must not count as travel.
        assertFalse(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 15f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 5f,
                isMoving = true
            )
        )
    }

    @Test
    fun `step larger than the uncertainty counts even on a mediocre fix`() {
        assertTrue(
            LocationFilters.contributesToOdometer(
                accuracyMeters = 15f,
                desiredOdometerAccuracyMeters = 100f,
                distanceMeters = 40f,
                isMoving = true
            )
        )
    }
}
