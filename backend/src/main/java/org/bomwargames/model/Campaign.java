package org.bomwargames.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "campaigns")
public class Campaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String setting;

    private String description;
    private String mapMode;

    protected Campaign() {}

    public Campaign(String slug, String name, String setting, String description, String mapMode) {
        this.slug = slug;
        this.name = name;
        this.setting = setting;
        this.description = description;
        this.mapMode = mapMode;
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSetting() { return setting; }
    public void setSetting(String setting) { this.setting = setting; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMapMode() { return mapMode; }
    public void setMapMode(String mapMode) { this.mapMode = mapMode; }
}
