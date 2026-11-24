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
@Table(name = "places")
public class Place {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    private String name;
    private String alternateName;
    private String kind;
    private String mapLayer;
    private Double x;
    private Double y;
    private Integer tier;
    private String description;

    protected Place() {}

    public Place(Campaign campaign, String name, String alternateName, String kind, String mapLayer,
                 Double x, Double y, Integer tier, String description) {
        this.campaign = campaign;
        this.name = name;
        this.alternateName = alternateName;
        this.kind = kind;
        this.mapLayer = mapLayer;
        this.x = x;
        this.y = y;
        this.tier = tier;
        this.description = description;
    }

    public Long getId() { return id; }
    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAlternateName() { return alternateName; }
    public void setAlternateName(String alternateName) { this.alternateName = alternateName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getMapLayer() { return mapLayer; }
    public void setMapLayer(String mapLayer) { this.mapLayer = mapLayer; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
    public Integer getTier() { return tier; }
    public void setTier(Integer tier) { this.tier = tier; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
