package org.wargames;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.wargames.model.TerrainRegion;

final class MovementPathCalculator {
    private static final double MAP_WIDTH = 1774;
    private static final double MAP_HEIGHT = 887;
    private static final double INTERVAL_EPSILON = 1e-9;
    private static final double PARALLEL_SINE_THRESHOLD = 0.5;

    private MovementPathCalculator() {}

    static Result calculate(double startX, double startY, double endX, double endY,
                            double mapWidthMiles, double mountDoomX, double mountDoomY,
                            List<TerrainRegion> regions) {
        Point start = mapPoint(startX, startY);
        Point end = mapPoint(endX, endY);
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double pathLength = Math.hypot(dx, dy);
        double distanceMiles = pathLength / MAP_WIDTH * mapWidthMiles;
        if (pathLength <= INTERVAL_EPSILON) {
            return new Result(distanceMiles, 0, 0,
                destinationTerrain(endX, endY, mountDoomX, mountDoomY, regions), List.of());
        }

        Map<Terrain, List<Interval>> coverage = new HashMap<>();
        List<Interval> riverCoverage = new ArrayList<>();
        List<Interval> riverTravel = new ArrayList<>();
        List<Double> riverIntersections = new ArrayList<>();
        Point doom = mapPoint(mountDoomX, mountDoomY);
        Interval mountDoom = circleInterval(start, end, doom, MAP_WIDTH * 0.005);
        if (mountDoom != null) addIntervals(coverage, Terrain.MOUNT_DOOM, List.of(mountDoom));

        for (TerrainRegion region : regions) {
            Terrain terrain = Terrain.from(region.getTerrainType());
            if (terrain == null || region.getPoints().isEmpty()) continue;
            List<Point> points = region.getPoints().stream()
                .map(point -> mapPoint(point.getX(), point.getY())).toList();
            double radius = MAP_HEIGHT * region.getBrushSize() / 200.0;
            for (int index = 0; index < points.size(); index++) {
                Point segmentStart = points.get(index);
                Point segmentEnd = points.get(Math.min(index + 1, points.size() - 1));
                List<Interval> intervals = capsuleIntervals(start, end, segmentStart, segmentEnd, radius);
                if (terrain == Terrain.RIVER) {
                    riverCoverage.addAll(intervals);
                    if (isParallel(dx, dy, segmentStart, segmentEnd)) {
                        riverTravel.addAll(intervals);
                    } else {
                        Double intersection = intersectionParameter(start, end, segmentStart, segmentEnd);
                        if (intersection != null) riverIntersections.add(intersection);
                    }
                } else {
                    addIntervals(coverage, terrain, intervals);
                }
            }
        }

        List<Interval> mergedRiverCoverage = merge(riverCoverage);
        List<Interval> mergedRiverTravel = merge(riverTravel);
        addIntervals(coverage, Terrain.RIVER, mergedRiverTravel);
        int riverCrossings = countRiverCrossings(riverIntersections, mergedRiverCoverage, mergedRiverTravel);
        List<Interval> boundaries = new ArrayList<>();
        boundaries.add(new Interval(0, 1));
        coverage.values().forEach(intervals -> boundaries.addAll(intervals));
        List<Double> cuts = new ArrayList<>();
        cuts.add(0.0);
        cuts.add(1.0);
        for (Interval interval : boundaries) {
            cuts.add(interval.start);
            cuts.add(interval.end);
        }
        cuts.sort(Comparator.naturalOrder());

        List<Segment> segments = new ArrayList<>();
        double terrainMovementPoints = 0;
        double previous = cuts.get(0);
        for (int index = 1; index < cuts.size(); index++) {
            double next = cuts.get(index);
            if (next - previous <= INTERVAL_EPSILON) continue;
            double midpoint = (previous + next) / 2;
            Terrain terrain = terrainAt(midpoint, coverage);
            double miles = distanceMiles * (next - previous);
            double movementPoints = miles / terrain.milesPerPoint;
            terrainMovementPoints += movementPoints;
            appendSegment(segments, terrain, miles, movementPoints);
            previous = next;
        }
        String destinationTerrain = destinationTerrain(endX, endY, mountDoomX, mountDoomY, regions);
        return new Result(distanceMiles, terrainMovementPoints + riverCrossings, riverCrossings,
            destinationTerrain, List.copyOf(segments));
    }

    private static String destinationTerrain(double x, double y, double mountDoomX, double mountDoomY,
                                              List<TerrainRegion> regions) {
        if (sameLocation(x, y, mountDoomX, mountDoomY)) return Terrain.MOUNT_DOOM.key;
        Point target = mapPoint(x, y);
        Terrain terrainAtPoint = Terrain.PLAINS;
        for (TerrainRegion region : regions) {
            Terrain terrain = Terrain.from(region.getTerrainType());
            if (terrain == null || region.getPoints().isEmpty()) continue;
            double radius = MAP_HEIGHT * region.getBrushSize() / 200.0;
            List<Point> points = region.getPoints().stream()
                .map(point -> mapPoint(point.getX(), point.getY())).toList();
            for (int index = 0; index < points.size(); index++) {
                if (distanceToSegment(target, points.get(index),
                        points.get(Math.min(index + 1, points.size() - 1))) <= radius) {
                    terrainAtPoint = slower(terrainAtPoint, terrain);
                }
            }
        }
        return terrainAtPoint.key;
    }

    private static void addIntervals(Map<Terrain, List<Interval>> coverage, Terrain terrain,
                                    List<Interval> intervals) {
        if (intervals.isEmpty()) return;
        coverage.computeIfAbsent(terrain, ignored -> new ArrayList<>()).addAll(intervals);
    }

    private static Terrain terrainAt(double parameter, Map<Terrain, List<Interval>> coverage) {
        Terrain selected = Terrain.PLAINS;
        for (Map.Entry<Terrain, List<Interval>> entry : coverage.entrySet()) {
            if (entry.getValue().stream().anyMatch(interval ->
                    parameter >= interval.start - INTERVAL_EPSILON
                        && parameter <= interval.end + INTERVAL_EPSILON)) {
                selected = slower(selected, entry.getKey());
            }
        }
        return selected;
    }

    private static Terrain slower(Terrain first, Terrain second) {
        if (second.milesPerPoint < first.milesPerPoint) return second;
        if (second.milesPerPoint > first.milesPerPoint) return first;
        if (second.priority < first.priority) return second;
        return first;
    }

    private static void appendSegment(List<Segment> segments, Terrain terrain, double miles,
                                      double movementPoints) {
        if (!segments.isEmpty() && segments.get(segments.size() - 1).terrain.equals(terrain.key)) {
            Segment previous = segments.remove(segments.size() - 1);
            segments.add(new Segment(terrain.key, previous.miles + miles,
                previous.movementPoints + movementPoints));
        } else {
            segments.add(new Segment(terrain.key, miles, movementPoints));
        }
    }

    private static int countRiverCrossings(List<Double> intersections, List<Interval> riverCoverage,
                                           List<Interval> riverTravel) {
        intersections.sort(Comparator.naturalOrder());
        int count = 0;
        double previous = Double.NEGATIVE_INFINITY;
        for (double intersection : intersections) {
            boolean travelingAlongRiver = riverTravel.stream().anyMatch(interval ->
                intersection >= interval.start - 1e-6 && intersection <= interval.end + 1e-6);
            if (travelingAlongRiver || riverCoverage.stream().noneMatch(interval ->
                    intersection >= interval.start - 1e-6 && intersection <= interval.end + 1e-6)) {
                continue;
            }
            if (intersection - previous > 1e-6) {
                count++;
                previous = intersection;
            }
        }
        return count;
    }

    private static boolean isParallel(double pathX, double pathY, Point start, Point end) {
        double segmentX = end.x - start.x;
        double segmentY = end.y - start.y;
        double denominator = Math.hypot(pathX, pathY) * Math.hypot(segmentX, segmentY);
        return denominator > INTERVAL_EPSILON
            && Math.abs(cross(pathX, pathY, segmentX, segmentY)) / denominator
                <= PARALLEL_SINE_THRESHOLD;
    }

    private static Double intersectionParameter(Point pathStart, Point pathEnd, Point lineStart, Point lineEnd) {
        double pathX = pathEnd.x - pathStart.x;
        double pathY = pathEnd.y - pathStart.y;
        double lineX = lineEnd.x - lineStart.x;
        double lineY = lineEnd.y - lineStart.y;
        double denominator = cross(pathX, pathY, lineX, lineY);
        if (Math.abs(denominator) <= INTERVAL_EPSILON) return null;
        double offsetX = lineStart.x - pathStart.x;
        double offsetY = lineStart.y - pathStart.y;
        double pathParameter = cross(offsetX, offsetY, lineX, lineY) / denominator;
        double lineParameter = cross(offsetX, offsetY, pathX, pathY) / denominator;
        if (pathParameter < -INTERVAL_EPSILON || pathParameter > 1 + INTERVAL_EPSILON
                || lineParameter < -INTERVAL_EPSILON || lineParameter > 1 + INTERVAL_EPSILON) {
            return null;
        }
        return clamp(pathParameter);
    }

    private static List<Interval> capsuleIntervals(Point pathStart, Point pathEnd, Point lineStart,
                                                    Point lineEnd, double radius) {
        double segmentX = lineEnd.x - lineStart.x;
        double segmentY = lineEnd.y - lineStart.y;
        double lengthSquared = segmentX * segmentX + segmentY * segmentY;
        if (lengthSquared <= INTERVAL_EPSILON) {
            Interval circle = circleInterval(pathStart, pathEnd, lineStart, radius);
            return circle == null ? List.of() : List.of(circle);
        }

        double pathX = pathEnd.x - pathStart.x;
        double pathY = pathEnd.y - pathStart.y;
        double projectionStart = ((pathStart.x - lineStart.x) * segmentX
            + (pathStart.y - lineStart.y) * segmentY) / lengthSquared;
        double projectionSlope = (pathX * segmentX + pathY * segmentY) / lengthSquared;
        List<Interval> intervals = new ArrayList<>();

        Interval before = clipLinear(projectionStart, projectionSlope,
            Double.NEGATIVE_INFINITY, 0);
        addClippedCircle(intervals, pathStart, pathEnd, lineStart, radius, before);

        Interval middle = clipLinear(projectionStart, projectionSlope, 0, 1);
        if (middle != null) {
            double crossStart = cross(segmentX, segmentY, pathStart.x - lineStart.x,
                pathStart.y - lineStart.y);
            double crossSlope = cross(segmentX, segmentY, pathX, pathY);
            Interval strip = clipLinear(crossStart, crossSlope,
                -radius * Math.sqrt(lengthSquared), radius * Math.sqrt(lengthSquared));
            Interval overlap = intersect(middle, strip);
            if (overlap != null) intervals.add(overlap);
        }

        Interval after = clipLinear(projectionStart, projectionSlope, 1,
            Double.POSITIVE_INFINITY);
        addClippedCircle(intervals, pathStart, pathEnd, lineEnd, radius, after);
        return merge(intervals);
    }

    private static void addClippedCircle(List<Interval> intervals, Point pathStart, Point pathEnd,
                                         Point center, double radius, Interval clip) {
        if (clip == null) return;
        Interval circle = circleInterval(pathStart, pathEnd, center, radius);
        Interval overlap = intersect(circle, clip);
        if (overlap != null) intervals.add(overlap);
    }

    private static Interval circleInterval(Point start, Point end, Point center, double radius) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double offsetX = start.x - center.x;
        double offsetY = start.y - center.y;
        double a = dx * dx + dy * dy;
        if (a <= INTERVAL_EPSILON) {
            return Math.hypot(offsetX, offsetY) <= radius ? new Interval(0, 1) : null;
        }
        double b = 2 * (offsetX * dx + offsetY * dy);
        double c = offsetX * offsetX + offsetY * offsetY - radius * radius;
        double discriminant = b * b - 4 * a * c;
        if (discriminant < 0) return null;
        double root = Math.sqrt(discriminant);
        double first = (-b - root) / (2 * a);
        double second = (-b + root) / (2 * a);
        return intersect(new Interval(first, second), new Interval(0, 1));
    }

    private static Interval clipLinear(double valueAtZero, double slope, double lower, double upper) {
        if (Math.abs(slope) <= INTERVAL_EPSILON) {
            return valueAtZero >= lower && valueAtZero <= upper ? new Interval(0, 1) : null;
        }
        double first = (lower - valueAtZero) / slope;
        double second = (upper - valueAtZero) / slope;
        return intersect(new Interval(Math.min(first, second), Math.max(first, second)),
            new Interval(0, 1));
    }

    private static Interval intersect(Interval first, Interval second) {
        if (first == null || second == null) return null;
        double start = Math.max(first.start, second.start);
        double end = Math.min(first.end, second.end);
        return end < start - INTERVAL_EPSILON ? null : new Interval(start, end);
    }

    private static List<Interval> merge(List<Interval> intervals) {
        if (intervals.isEmpty()) return List.of();
        List<Interval> sorted = intervals.stream()
            .filter(interval -> interval != null && interval.end >= interval.start)
            .sorted(Comparator.comparingDouble(Interval::start))
            .toList();
        if (sorted.isEmpty()) return List.of();
        List<Interval> merged = new ArrayList<>();
        Interval current = sorted.get(0);
        for (int index = 1; index < sorted.size(); index++) {
            Interval next = sorted.get(index);
            if (next.start <= current.end + INTERVAL_EPSILON) {
                current = new Interval(current.start, Math.max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static double distanceToSegment(Point point, Point start, Point end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double lengthSquared = dx * dx + dy * dy;
        double projection = lengthSquared <= INTERVAL_EPSILON ? 0
            : clamp(((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared);
        return Math.hypot(point.x - (start.x + projection * dx),
            point.y - (start.y + projection * dy));
    }

    private static double cross(double firstX, double firstY, double secondX, double secondY) {
        return firstX * secondY - firstY * secondX;
    }

    private static Point mapPoint(double x, double y) {
        return new Point(x * MAP_WIDTH / 100, y * MAP_HEIGHT / 100);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static boolean sameLocation(double x1, double y1, double x2, double y2) {
        return Math.hypot(x1 - x2, y1 - y2) < 0.5;
    }

    record Result(double miles, double movementPoints, int riverCrossings,
                  String destinationTerrain, List<Segment> segments) {}

    record Segment(String terrain, double miles, double movementPoints) {}

    private record Point(double x, double y) {}

    private record Interval(double start, double end) {}

    private enum Terrain {
        MOUNT_DOOM("mount-doom", 6, 0),
        VOLCANIC("volcanic", 6, 1),
        MOUNTAIN("mountain", 7.5, 2),
        FOREST("forest", 10, 4),
        RIVER("river", 10, 3),
        PLAINS("plains", 15, 5);

        final String key;
        final double milesPerPoint;
        final int priority;

        Terrain(String key, double milesPerPoint, int priority) {
            this.key = key;
            this.milesPerPoint = milesPerPoint;
            this.priority = priority;
        }

        static Terrain from(String key) {
            // Rooms and strokes saved before the key was unified used the plural form.
            if ("mountains".equals(key)) return MOUNTAIN;
            for (Terrain terrain : values()) if (terrain.key.equals(key)) return terrain;
            return null;
        }
    }
}
