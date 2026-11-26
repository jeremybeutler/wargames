package org.wargames.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "terrain_regions")
public class TerrainRegion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String mapKey;

    @Column(nullable = false)
    private String mapLayer;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String terrainType;

    @Column(nullable = false)
    private double brushSize;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "terrain_region_points", joinColumns = @JoinColumn(name = "region_id"))
    @OrderColumn(name = "point_order")
    @Embedded
    private List<RegionPoint> points = new ArrayList<>();

    protected TerrainRegion() {}

    public TerrainRegion(String mapKey, String mapLayer, String name, String terrainType,
                         double brushSize, List<RegionPoint> points) {
        this.mapKey = mapKey;
        this.mapLayer = mapLayer;
        this.name = name;
        this.terrainType = terrainType;
        this.brushSize = brushSize;
        setPoints(points);
    }

    public Long getId() { return id; }
    public String getMapKey() { return mapKey; }
    public void setMapKey(String mapKey) { this.mapKey = mapKey; }
    public String getMapLayer() { return mapLayer; }
    public void setMapLayer(String mapLayer) { this.mapLayer = mapLayer; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTerrainType() { return terrainType; }
    public void setTerrainType(String terrainType) { this.terrainType = terrainType; }
    public double getBrushSize() { return brushSize; }
    public void setBrushSize(double brushSize) { this.brushSize = brushSize; }
    public List<RegionPoint> getPoints() { return points; }
    public void setPoints(List<RegionPoint> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
    }

    @Embeddable
    public static class RegionPoint {
        private double x;
        private double y;

        protected RegionPoint() {}

        public RegionPoint(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }
}
