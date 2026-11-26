package org.wargames;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.wargames.model.TerrainRegion;

/**
 * The Westeros map for The Iron Throne: its places, the Wall, terrain strokes and which pixels
 * are land. Built from files in the classpath that the map scripts generate:
 * westeros-map.json (scripts/build_westeros_map.py), westeros-terrain.json and
 * westeros-land-mask.png (scripts/generate_terrain.py --profile westeros).
 *
 * Coordinates are map percentages. The map is 2:1, like Middle-earth's, so a distance in
 * "% of map width" is hypot(dx, dy / 2).
 */
@Component
public class ThronesMap {
    record Place(String name, String kind, double x, double y, String region, boolean port) {}

    private final List<Place> places;
    private final List<double[]> wall;
    private final List<TerrainRegion> terrain;
    private final boolean[][] land;   // [row][column]
    private final int maskWidth;
    private final int maskHeight;

    public ThronesMap() {
        this(read("/westeros-map.json"), read("/westeros-terrain.json"), readMask("/westeros-land-mask.png"));
    }

    ThronesMap(JsonNode map, JsonNode terrainJson, BufferedImage mask) {
        List<Place> loaded = new ArrayList<>();
        for (JsonNode place : map.get("places")) {
            loaded.add(new Place(place.get("name").asText(), place.get("kind").asText(),
                place.get("x").asDouble(), place.get("y").asDouble(), place.get("region").asText(),
                place.path("port").asBoolean(false)));
        }
        places = List.copyOf(loaded);
        List<double[]> line = new ArrayList<>();
        for (JsonNode point : map.get("wall")) line.add(new double[] {point.get(0).asDouble(), point.get(1).asDouble()});
        line.sort(Comparator.comparingDouble(point -> point[0]));
        wall = List.copyOf(line);

        List<TerrainRegion> regions = new ArrayList<>();
        for (JsonNode region : terrainJson.get("regions")) {
            List<TerrainRegion.RegionPoint> points = new ArrayList<>();
            for (JsonNode point : region.get("points")) {
                points.add(new TerrainRegion.RegionPoint(point.get("x").asDouble(), point.get("y").asDouble()));
            }
            regions.add(new TerrainRegion("westeros", "westeros", region.get("name").asText(),
                region.get("terrainType").asText(), region.get("brushSize").asDouble(), points));
        }
        terrain = List.copyOf(regions);

        maskWidth = mask.getWidth();
        maskHeight = mask.getHeight();
        land = new boolean[maskHeight][maskWidth];
        for (int row = 0; row < maskHeight; row++) {
            for (int column = 0; column < maskWidth; column++) {
                land[row][column] = (mask.getRGB(column, row) & 0xFFFFFF) != 0;
            }
        }
    }

    List<Place> places() { return places; }
    List<double[]> wall() { return wall; }
    List<TerrainRegion> terrain() { return terrain; }

    Place place(String name) {
        return places.stream().filter(place -> place.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalStateException("The Westeros map has no place named " + name));
    }

    /** Distance in % of map width. */
    static double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, (y1 - y2) * 0.5);
    }

    boolean isLand(double x, double y) {
        int column = (int) Math.floor(x / 100 * maskWidth);
        int row = (int) Math.floor(y / 100 * maskHeight);
        if (column < 0 || row < 0 || column >= maskWidth || row >= maskHeight) return false;
        return land[row][column];
    }

    /** True if water lies within {@code reach} (% of map width) of the point. */
    boolean nearWater(double x, double y, double reach) {
        for (int ring = 1; ring <= 4; ring++) {
            double r = reach * ring / 4;
            for (int step = 0; step < 16; step++) {
                double angle = Math.PI * 2 * step / 16;
                if (!isLand(x + Math.cos(angle) * r, y + Math.sin(angle) * r * 2)) return true;
            }
        }
        return false;
    }

    /** The fraction (0..1) of a straight path that runs over water, sampled finely. */
    double waterFraction(double x1, double y1, double x2, double y2) {
        int samples = Math.max(8, (int) Math.ceil(distance(x1, y1, x2, y2) / 0.15));
        int water = 0;
        for (int index = 0; index <= samples; index++) {
            double t = (double) index / samples;
            if (!isLand(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)) water++;
        }
        return (double) water / (samples + 1);
    }

    /** The region of the nearest named place: "The North", "Dorne", "Essos" and so on. */
    String regionAt(double x, double y) {
        return places.stream()
            .min(Comparator.comparingDouble(place -> distance(x, y, place.x(), place.y())))
            .map(Place::region).orElse("");
    }

    /** The Wall's height (y %) at x, extended flat beyond its ends. */
    double wallY(double x) {
        if (x <= wall.get(0)[0]) return wall.get(0)[1];
        for (int index = 1; index < wall.size(); index++) {
            double[] a = wall.get(index - 1);
            double[] b = wall.get(index);
            if (x <= b[0]) return a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]);
        }
        return wall.get(wall.size() - 1)[1];
    }

    /** Wall band half-height in y % around the line, where a party counts as standing on it. */
    static final double WALL_BAND = 0.9;

    /** -1 beyond the Wall (north), 1 south of it, 0 standing on it. */
    int wallSide(double x, double y) {
        double line = wallY(x);
        if (y < line - WALL_BAND) return -1;
        if (y > line + WALL_BAND) return 1;
        return 0;
    }

    boolean southOfWall(double x, double y) {
        return wallSide(x, y) > 0;
    }

    private static JsonNode read(String resource) {
        try (InputStream input = ThronesMap.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return new ObjectMapper().readTree(input);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static BufferedImage readMask(String resource) {
        try (InputStream input = ThronesMap.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Missing " + resource);
            return ImageIO.read(input);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
