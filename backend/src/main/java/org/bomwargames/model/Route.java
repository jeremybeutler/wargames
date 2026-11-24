package org.bomwargames.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "routes")
public class Route {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    private String name;
    private String color;
    private String description;

    @ElementCollection
    @CollectionTable(name = "route_points", joinColumns = @JoinColumn(name = "route_id"))
    @OrderColumn(name = "point_order")
    @Embedded
    private List<RoutePoint> points = new ArrayList<>();

    protected Route() {}

    public Route(Campaign campaign, String name, String color, String description, List<RoutePoint> points) {
        this.campaign = campaign;
        this.name = name;
        this.color = color;
        this.description = description;
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
    }

    public Long getId() { return id; }
    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<RoutePoint> getPoints() { return points; }
    public void setPoints(List<RoutePoint> points) {
        this.points = points == null ? new ArrayList<>() : new ArrayList<>(points);
    }

    @Embeddable
    public static class RoutePoint {
        private double x;
        private double y;

        protected RoutePoint() {}

        public RoutePoint(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public double getX() { return x; }
        public void setX(double x) { this.x = x; }
        public double getY() { return y; }
        public void setY(double y) { this.y = y; }
    }
}
