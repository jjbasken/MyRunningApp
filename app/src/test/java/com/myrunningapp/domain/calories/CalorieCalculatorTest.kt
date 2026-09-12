package com.myrunningapp.domain.calories

import com.myrunningapp.domain.Units
import com.myrunningapp.domain.model.ActivityType
import com.myrunningapp.domain.model.Profile
import com.myrunningapp.domain.model.Sex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalorieCalculatorTest {

    private val defaultProfile = Profile(weightKg = 70.0, heightCm = 175.0, age = 35, sex = Sex.MALE)

    @Test
    fun `resting oxygen uptake lands near the textbook 3 point 5`() {
        // The MET formula assumes 3.5 mL/kg/min for everyone; the profile only
        // nudges that, so a wild departure means the conversion is wrong.
        val resting = CalorieCalculator.restingVo2(defaultProfile)
        assertEquals(3.22, resting, 0.01)
    }

    @Test
    fun `a heavier person has a lower resting uptake per kilogram`() {
        val heavier = defaultProfile.copy(weightKg = 110.0)
        assertTrue(CalorieCalculator.restingVo2(heavier) < CalorieCalculator.restingVo2(defaultProfile))
    }

    @Test
    fun `resting uptake stays sane for extreme profiles`() {
        val tiny = Profile(weightKg = 30.0, heightCm = 120.0, age = 90, sex = Sex.FEMALE)
        val huge = Profile(weightKg = 200.0, heightCm = 210.0, age = 18, sex = Sex.MALE)
        listOf(tiny, huge).forEach {
            val resting = CalorieCalculator.restingVo2(it)
            assertTrue("resting $resting out of range", resting in 2.0..5.0)
        }
    }

    @Test
    fun `a five kilometre run in twenty five minutes burns about 378 calories`() {
        val kcal = CalorieCalculator.calories(
            activityType = ActivityType.RUN,
            distanceMeters = 5000.0,
            movingDurationSec = 1500,
            profile = defaultProfile,
        )
        assertEquals(378, kcal)
    }

    @Test
    fun `the same distance walked over an hour burns fewer calories`() {
        val kcal = CalorieCalculator.calories(
            activityType = ActivityType.WALK,
            distanceMeters = 5000.0,
            movingDurationSec = 3600,
            profile = defaultProfile,
        )
        assertEquals(243, kcal)
    }

    @Test
    fun `a heavier runner burns more over the same run`() {
        val kcal = CalorieCalculator.calories(
            ActivityType.RUN, 5000.0, 1500, defaultProfile.copy(weightKg = 90.0),
        )
        assertEquals(482, kcal)
    }

    @Test
    fun `walking and running the same speed differ`() {
        val walk = CalorieCalculator.calories(ActivityType.WALK, 3000.0, 1800, defaultProfile)
        val run = CalorieCalculator.calories(ActivityType.RUN, 3000.0, 1800, defaultProfile)
        assertTrue("running should cost more than walking", run > walk)
    }

    @Test
    fun `a run with no moving time burns nothing`() {
        assertEquals(0, CalorieCalculator.calories(ActivityType.RUN, 0.0, 0, defaultProfile))
    }

    @Test
    fun `standing still still burns the resting rate`() {
        // Zero distance over ten minutes: no work done, but the body ticks over.
        val kcal = CalorieCalculator.calories(ActivityType.RUN, 0.0, 600, defaultProfile)
        assertEquals(11, kcal)
    }

    @Test
    fun `nonsense inputs do not produce nonsense calories`() {
        assertEquals(0, CalorieCalculator.calories(ActivityType.RUN, -100.0, 600, defaultProfile))
        assertEquals(0, CalorieCalculator.calories(ActivityType.RUN, 1000.0, -5, defaultProfile))
        assertEquals(
            0,
            CalorieCalculator.calories(
                ActivityType.RUN, 1000.0, 300, defaultProfile.copy(weightKg = 0.0),
            ),
        )
    }

    @Test
    fun `a ride costs less than a run over the same distance and time`() {
        val distance = 8000.0
        val duration = 1800L
        val bike = CalorieCalculator.calories(ActivityType.BIKE, distance, duration, defaultProfile)
        val run = CalorieCalculator.calories(ActivityType.RUN, distance, duration, defaultProfile)
        assertTrue("bike=$bike run=$run", bike < run)
    }

    @Test
    fun `a ride costs less than a walk over the same distance`() {
        val distance = 5000.0
        val duration = 1200L
        val bike = CalorieCalculator.calories(ActivityType.BIKE, distance, duration, defaultProfile)
        val walk = CalorieCalculator.calories(ActivityType.WALK, distance, duration, defaultProfile)
        assertTrue("bike=$bike walk=$walk", bike < walk)
    }

    @Test
    fun `an hour at 15 mph lands near the compendium's 10 METs`() {
        // 10 METs for a 70 kg adult is about 700 kcal in an hour. The single
        // speed coefficient is a fit, not an identity, so allow 15%.
        val meters = Units.milesToMeters(15.0)
        val kcal = CalorieCalculator.calories(
            ActivityType.BIKE, meters, 3600, defaultProfile.copy(weightKg = 70.0),
        )
        assertTrue("kcal=$kcal", kcal in 595..805)
    }
}
