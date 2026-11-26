package org.wargames;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.wargames.model.TerrainRegion;

/** Checks terrain/middle-earth.json (from scripts/generate_terrain.py) against the map art. */
class GeneratedTerrainTest {
    private static List<TerrainRegion> regions;

    @BeforeAll
    static void load() throws IOException {
        JsonNode data = new ObjectMapper().readTree(Path.of("../terrain/middle-earth.json").toFile());
        regions = new ArrayList<>();
        for (JsonNode region : data.get("regions")) {
            List<TerrainRegion.RegionPoint> points = new ArrayList<>();
            for (JsonNode point : region.get("points")) {
                points.add(new TerrainRegion.RegionPoint(point.get("x").asDouble(), point.get("y").asDouble()));
            }
            regions.add(new TerrainRegion("middle-earth", "middle-earth", region.get("name").asText(),
                region.get("terrainType").asText(), region.get("brushSize").asDouble(), points));
        }
    }

    private static String terrainAt(double x, double y) {
        return MovementPathCalculator.calculate(x, y, x, y, 1000, 81.5, 68.5, regions).destinationTerrain();
    }

    @Test
    void matchesLandmarksOnTheMapArt() {
        assertEquals("forest", terrainAt(64.3, 28.6));     // Mirkwood
        assertEquals("mountain", terrainAt(44.3, 28.6));   // Misty Mountains
        assertEquals("mountain", terrainAt(37.0, 70.0));   // White Mountains
        assertEquals("volcanic", terrainAt(76.0, 72.0));   // Mordor's ash plain
        assertEquals("plains", terrainAt(56.0, 62.0));     // open grassland east of the White Mountains
    }

    @Test
    void chargesRiversAndMountainsAlongARealRoute() {
        // West to east across the Anduin, north of the Brown Lands.
        MovementPathCalculator.Result anduin = MovementPathCalculator.calculate(
            48.5, 37.0, 53.5, 37.0, 1000, 81.5, 68.5, regions);
        assertTrue(anduin.riverCrossings() >= 1, "crossing the Anduin should cost a river crossing: " + anduin.segments() + " crossings=" + anduin.riverCrossings());

        // From the Rohan plains south into the White Mountains.
        MovementPathCalculator.Result mountains = MovementPathCalculator.calculate(
            40.0, 58.0, 37.0, 70.0, 1000, 81.5, 68.5, regions);
        assertTrue(mountains.segments().stream().anyMatch(s -> s.terrain().equals("mountain")),
            "the route into the White Mountains should include mountain terrain: " + mountains.segments());
    }
}
