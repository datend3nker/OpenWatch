package tech.mmarca.openwatch.link

import kotlin.math.roundToInt
import tech.mmarca.openlink.OpenLinkExerciseKind
import tech.mmarca.openlink.OpenLinkResistance
import tech.mmarca.openlink.OpenLinkWeightUnit
import tech.mmarca.openlink.OpenLinkWorkoutExercise
import tech.mmarca.openlink.OpenLinkWorkoutPlan
import tech.mmarca.openlink.OpenLinkWorkoutSet
import tech.mmarca.openwatch.wearlink.WearPlan
import tech.mmarca.openwatch.wearlink.WearPlanExercise
import tech.mmarca.openwatch.wearlink.WearPlanKind
import tech.mmarca.openwatch.wearlink.WearPlanResistance
import tech.mmarca.openwatch.wearlink.WearPlanSet
import tech.mmarca.openwatch.wearlink.WearPlanWeightUnit

/**
 * A client's plan as the watch link carries it. The library has already checked the limits and cleaned
 * the labels ([OpenLinkWorkoutPlan.valid]); this only changes the shape: the weight in tenths, a done
 * time of 0 as none, an unset rest as 0.
 */
internal fun OpenLinkWorkoutPlan.toWearPlan(): WearPlan = WearPlan(
    sessionId = sessionId,
    revision = revision,
    currentSetId = currentSetId,
    exercises = exercises.map { it.toWear() },
)

private fun OpenLinkWorkoutExercise.toWear() = WearPlanExercise(
    id = exerciseId,
    label = label,
    kind = when (kind) {
        OpenLinkExerciseKind.WEIGHTED -> WearPlanKind.WEIGHTED
        OpenLinkExerciseKind.CARDIO -> WearPlanKind.CARDIO
    },
    resistance = when (resistance) {
        OpenLinkResistance.NONE -> WearPlanResistance.NONE
        OpenLinkResistance.EXTERNAL -> WearPlanResistance.EXTERNAL
        OpenLinkResistance.BODYWEIGHT -> WearPlanResistance.BODYWEIGHT
    },
    supersetWithNext = supersetWithNext,
    segmentType = segmentType,
    restMinSeconds = restMinSeconds ?: 0,
    restMaxSeconds = restMaxSeconds ?: 0,
    restAfterFailureSeconds = restAfterFailureSeconds ?: 0,
    sets = sets.map { it.toWear() },
)

private fun OpenLinkWorkoutSet.toWear() = WearPlanSet(
    id = setId,
    repsMin = repsMin,
    repsMax = repsMax,
    targetSeconds = targetSeconds,
    targetMeters = targetMeters,
    weightTenths = weight?.let { (it * 10).roundToInt() },
    unit = if (weightUnit == OpenLinkWeightUnit.LB) WearPlanWeightUnit.LB else WearPlanWeightUnit.KG,
    done = done,
    repsDone = repsDone,
    secondsDone = secondsDone,
    metersDone = metersDone,
    doneAtMillis = doneAtMillis.takeIf { it > 0L },
    updatedAtMillis = updatedAtMillis,
)
