package io.github.mesmerprism.questautobootexample;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public final class BootPlanTest {
    @Test public void freshInstallNeverLaunches() {
        assertEquals(BootPlan.Step.STOP_DISABLED,
            BootPlan.next(false, true, true, true, false, false, 0));
    }

    @Test public void missingExactFrontDoorStopsWithoutFallback() {
        assertEquals(BootPlan.Step.STOP_TARGET,
            BootPlan.next(true, false, true, true, false, false, 0));
    }

    @Test public void wearerWaitIsBoundedAndRequiresBothSignals() {
        assertEquals(BootPlan.Step.WAIT,
            BootPlan.next(true, true, true, true, true, false, 1));
        assertEquals(BootPlan.Step.WAIT,
            BootPlan.next(true, true, true, false, true, true, 1));
        assertEquals(BootPlan.Step.LAUNCH,
            BootPlan.next(true, true, true, true, true, true, 1));
        assertEquals(BootPlan.Step.STOP_EXPIRED,
            BootPlan.next(true, true, true, true, true, true, BootPlan.DEADLINE_MS));
    }

    @Test public void bootCompletionAndMonotonicDeadlineGateLaunch() {
        assertEquals(BootPlan.Step.WAIT,
            BootPlan.next(true, true, false, true, false, false, 0));
        assertEquals(BootPlan.Step.LAUNCH,
            BootPlan.next(true, true, true, false, false, false, 0));
        assertEquals(BootPlan.Step.STOP_EXPIRED,
            BootPlan.next(true, true, true, true, false, false, -1));
    }

    @Test public void choiceIdBindsBothComponentAndCategory() {
        String a = FrontDoors.idFor("org.example/.Demo", FrontDoors.VR, "identity1");
        assertEquals(32, a.length());
        assertFalse(a.equals(FrontDoors.idFor("org.example/.Demo", FrontDoors.TWO_D, "identity1")));
        assertFalse(a.equals(FrontDoors.idFor("org.other/.Demo", FrontDoors.VR, "identity1")));
        assertFalse(a.equals(FrontDoors.idFor("org.example/.Demo", FrontDoors.VR, "identity2")));
    }
}
