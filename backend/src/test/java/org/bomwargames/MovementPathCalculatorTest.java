package org.bomwargames;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.bomwargames.model.TerrainRegion;
import org.junit.jupiter.api.Test;

class MovementPathCalculatorTest {
    @Test
    void chargesEachTerrainForOnlyTheDistanceSpentInsideItsStroke() {
        TerrainRegion forest = region("forest", 2, point(50, 40), point(50, 60));

        MovementPathCalculator.Result result = MovementPathCalculator.calculate(
            10, 50, 90, 50, 1000, 83, 67, List.of(forest));

        assertEquals(800, result.miles(), 0.001);
        assertEquals(53.6667, result.movementPoints(), 0.01);
        assertEquals("plains", result.destinationTerrain());
        assertEquals(3, result.segments().size());
        assertEquals("forest", result.segments().get(1).terrain());
    }

    @Test
    void chargesOneMovementPointForCrossingARiver() {
        TerrainRegion river = region("river", 2, point(50, 40), point(50, 60));

        MovementPathCalculator.Result result = MovementPathCalculator.calculate(
            10, 50, 90, 50, 1000, 83, 67, List.of(river));

        assertEquals(1, result.riverCrossings());
        assertEquals(800 / 15.0 + 1, result.movementPoints(), 0.01);
    }

    @Test
    void usesTheFixedRiverRateWhenTravelingAlongARiverStroke() {
        TerrainRegion river = region("river", 2, point(40, 50), point(60, 50));

        MovementPathCalculator.Result result = MovementPathCalculator.calculate(
            10, 50, 90, 50, 1000, 83, 67, List.of(river));

        assertEquals(0, result.riverCrossings());
        MovementPathCalculator.Segment riverSegment = result.segments().stream()
            .filter(segment -> segment.terrain().equals("river"))
            .findFirst()
            .orElseThrow();
        assertEquals(riverSegment.miles() / 10, riverSegment.movementPoints(), 0.01);
    }

    @Test
    void chargesMountainRateForCrossingAMountainStroke() {
        TerrainRegion mountain = region("mountain", 2, point(50, 40), point(50, 60));

        MovementPathCalculator.Result result = MovementPathCalculator.calculate(
            10, 50, 90, 50, 1000, 83, 67, List.of(mountain));

        MovementPathCalculator.Segment mountainSegment = result.segments().stream()
            .filter(segment -> segment.terrain().equals("mountain"))
            .findFirst()
            .orElseThrow();
        // A 2% brush is 17.74 map units wide: 10 map miles at 1000 miles across.
        assertEquals(10, mountainSegment.miles(), 0.01);
        assertEquals(10 / 7.5, mountainSegment.movementPoints(), 0.01);
        assertEquals(790 / 15.0 + 10 / 7.5, result.movementPoints(), 0.01);
    }

    @Test
    void acceptsTheLegacyPluralMountainKey() {
        TerrainRegion mountain = region("mountains", 2, point(50, 40), point(50, 60));

        MovementPathCalculator.Result result = MovementPathCalculator.calculate(
            10, 50, 50, 50, 1000, 83, 67, List.of(mountain));

        assertEquals("mountain", result.destinationTerrain());
        assertEquals("mountain", result.segments().get(result.segments().size() - 1).terrain());
    }

    private static TerrainRegion region(String type, double brushSize,
                                        TerrainRegion.RegionPoint... points) {
        return new TerrainRegion("middle-earth", "middle-earth", "test", type, brushSize,
            List.of(points));
    }

    private static TerrainRegion.RegionPoint point(double x, double y) {
        return new TerrainRegion.RegionPoint(x, y);
    }
}
