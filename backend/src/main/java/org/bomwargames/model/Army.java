package org.bomwargames.model;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "armies")
public class Army {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    private String name;
    private String faction;
    private String side;
    private Double x;
    private Double y;
    private Integer unitCount;
    private String description;

    protected Army() {}

    public Army(Campaign campaign, String name, String faction, String side,
                Double x, Double y, Integer unitCount, String description) {
        this.campaign = campaign;
        this.name = name;
        this.faction = faction;
        this.side = side;
        this.x = x;
        this.y = y;
        this.unitCount = unitCount;
        this.description = description;
    }

    public Long getId() { return id; }
    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFaction() { return faction; }
    public void setFaction(String faction) { this.faction = faction; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public Integer getUnitCount() { return unitCount; }
    public void setUnitCount(Integer unitCount) { this.unitCount = unitCount; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
