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
@Table(name = "campaign_events")
public class CampaignEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "campaign_id", nullable = false)
    private Campaign campaign;

    private Integer eventNumber;
    private String title;
    private String description;
    private String placeName;
    private String dateLabel;
    private Double x;
    private Double y;

    protected CampaignEvent() {}

    public CampaignEvent(Campaign campaign, Integer eventNumber, String title, String description,
                         String placeName, String dateLabel, Double x, Double y) {
        this.campaign = campaign;
        this.eventNumber = eventNumber;
        this.title = title;
        this.description = description;
        this.placeName = placeName;
        this.dateLabel = dateLabel;
        this.x = x;
        this.y = y;
    }

    public Long getId() { return id; }
    public Campaign getCampaign() { return campaign; }
    public void setCampaign(Campaign campaign) { this.campaign = campaign; }
    public Integer getEventNumber() { return eventNumber; }
    public void setEventNumber(Integer eventNumber) { this.eventNumber = eventNumber; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPlaceName() { return placeName; }
    public void setPlaceName(String placeName) { this.placeName = placeName; }
    public String getDateLabel() { return dateLabel; }
    public void setDateLabel(String dateLabel) { this.dateLabel = dateLabel; }
    public Double getX() { return x; }
    public void setX(Double x) { this.x = x; }
    public Double getY() { return y; }
    public void setY(Double y) { this.y = y; }
}
