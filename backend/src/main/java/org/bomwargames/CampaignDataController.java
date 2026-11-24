package org.bomwargames;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.bomwargames.model.Army;
import org.bomwargames.model.Campaign;
import org.bomwargames.model.CampaignEvent;
import org.bomwargames.model.Place;
import org.bomwargames.model.Route;
import org.bomwargames.model.TerrainRegion;
import org.bomwargames.repository.ArmyRepository;
import org.bomwargames.repository.CampaignEventRepository;
import org.bomwargames.repository.CampaignRepository;
import org.bomwargames.repository.PlaceRepository;
import org.bomwargames.repository.RouteRepository;
import org.bomwargames.repository.TerrainRegionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class CampaignDataController {
    private final CampaignRepository campaigns;
    private final PlaceRepository places;
    private final RouteRepository routes;
    private final CampaignEventRepository events;
    private final ArmyRepository armies;
    private final TerrainRegionRepository terrainRegions;

    public CampaignDataController(CampaignRepository campaigns, PlaceRepository places,
                                  RouteRepository routes, CampaignEventRepository events,
                                  ArmyRepository armies, TerrainRegionRepository terrainRegions) {
        this.campaigns = campaigns;
        this.places = places;
        this.routes = routes;
        this.events = events;
        this.armies = armies;
        this.terrainRegions = terrainRegions;
    }

    @GetMapping("/campaigns")
    public List<CampaignView> getCampaigns() {
        return campaigns.findAll().stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/campaigns/{id}")
    public CampaignView getCampaign(@PathVariable Long id) {
        return view(requireCampaign(id));
    }

    @PostMapping("/campaigns")
    @Transactional
    public ResponseEntity<CampaignView> createCampaign(@Valid @RequestBody CampaignRequest request) {
        Campaign campaign = new Campaign(request.slug(), request.name(), request.setting(),
            request.description(), request.mapMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(campaigns.save(campaign)));
    }

    @PutMapping("/campaigns/{id}")
    @Transactional
    public CampaignView updateCampaign(@PathVariable Long id, @Valid @RequestBody CampaignRequest request) {
        Campaign campaign = requireCampaign(id);
        campaign.setSlug(request.slug());
        campaign.setName(request.name());
        campaign.setSetting(request.setting());
        campaign.setDescription(request.description());
        campaign.setMapMode(request.mapMode());
        return view(campaigns.save(campaign));
    }

    @DeleteMapping("/campaigns/{id}")
    @Transactional
    public ResponseEntity<Void> deleteCampaign(@PathVariable Long id) {
        Campaign campaign = requireCampaign(id);
        if (places.existsByCampaign_Id(id) || routes.existsByCampaign_Id(id)
                || events.existsByCampaign_Id(id) || armies.existsByCampaign_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Delete the campaign's places, routes, events, and armies before deleting the campaign");
        }
        campaigns.delete(campaign);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/places")
    public List<PlaceView> getPlaces(@RequestParam(required = false) Long campaignId) {
        return (campaignId == null ? places.findAll() : places.findByCampaign_Id(campaignId))
            .stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/places/{id}")
    public PlaceView getPlace(@PathVariable Long id) {
        return view(requirePlace(id));
    }

    @PostMapping("/places")
    @Transactional
    public ResponseEntity<PlaceView> createPlace(@Valid @RequestBody PlaceRequest request) {
        Campaign campaign = requireCampaign(request.campaignId());
        String mapLayer = request.mapLayer() == null ? campaign.getMapMode() : request.mapLayer();
        Place place = new Place(campaign, request.name(),
            request.alternateName(), request.kind(), mapLayer, request.x(), request.y(),
            request.tier(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(places.save(place)));
    }

    @PutMapping("/places/{id}")
    @Transactional
    public PlaceView updatePlace(@PathVariable Long id, @Valid @RequestBody PlaceRequest request) {
        Place place = requirePlace(id);
        Campaign campaign = requireCampaign(request.campaignId());
        place.setCampaign(campaign);
        place.setName(request.name());
        place.setAlternateName(request.alternateName());
        place.setKind(request.kind());
        place.setMapLayer(request.mapLayer() != null ? request.mapLayer()
            : place.getMapLayer() != null ? place.getMapLayer() : campaign.getMapMode());
        place.setX(request.x());
        place.setY(request.y());
        place.setTier(request.tier());
        place.setDescription(request.description());
        return view(places.save(place));
    }

    @DeleteMapping("/places/{id}")
    public ResponseEntity<Void> deletePlace(@PathVariable Long id) {
        places.delete(requirePlace(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routes")
    @Transactional(readOnly = true)
    public List<RouteView> getRoutes(@RequestParam(required = false) Long campaignId) {
        return (campaignId == null ? routes.findAll() : routes.findByCampaign_Id(campaignId))
            .stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/routes/{id}")
    @Transactional(readOnly = true)
    public RouteView getRoute(@PathVariable Long id) {
        return view(requireRoute(id));
    }

    @PostMapping("/routes")
    @Transactional
    public ResponseEntity<RouteView> createRoute(@Valid @RequestBody RouteRequest request) {
        Route route = new Route(requireCampaign(request.campaignId()), request.name(),
            request.color(), request.description(), routePoints(request.points()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(routes.save(route)));
    }

    @PutMapping("/routes/{id}")
    @Transactional
    public RouteView updateRoute(@PathVariable Long id, @Valid @RequestBody RouteRequest request) {
        Route route = requireRoute(id);
        route.setCampaign(requireCampaign(request.campaignId()));
        route.setName(request.name());
        route.setColor(request.color());
        route.setDescription(request.description());
        route.setPoints(routePoints(request.points()));
        return view(routes.save(route));
    }

    @DeleteMapping("/routes/{id}")
    public ResponseEntity<Void> deleteRoute(@PathVariable Long id) {
        routes.delete(requireRoute(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events")
    public List<EventView> getEvents(@RequestParam(required = false) Long campaignId) {
        return (campaignId == null ? events.findAll() : events.findByCampaign_Id(campaignId))
            .stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/events/{id}")
    public EventView getEvent(@PathVariable Long id) {
        return view(requireEvent(id));
    }

    @PostMapping("/events")
    @Transactional
    public ResponseEntity<EventView> createEvent(@Valid @RequestBody EventRequest request) {
        CampaignEvent event = new CampaignEvent(requireCampaign(request.campaignId()),
            request.eventNumber(), request.title(), request.description(), request.placeName(),
            request.dateLabel(), request.x(), request.y());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(events.save(event)));
    }

    @PutMapping("/events/{id}")
    @Transactional
    public EventView updateEvent(@PathVariable Long id, @Valid @RequestBody EventRequest request) {
        CampaignEvent event = requireEvent(id);
        event.setCampaign(requireCampaign(request.campaignId()));
        event.setEventNumber(request.eventNumber());
        event.setTitle(request.title());
        event.setDescription(request.description());
        event.setPlaceName(request.placeName());
        event.setDateLabel(request.dateLabel());
        event.setX(request.x());
        event.setY(request.y());
        return view(events.save(event));
    }

    @DeleteMapping("/events/{id}")
    public ResponseEntity<Void> deleteEvent(@PathVariable Long id) {
        events.delete(requireEvent(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/armies")
    public List<ArmyView> getArmies(@RequestParam(required = false) Long campaignId) {
        return (campaignId == null ? armies.findAll() : armies.findByCampaign_Id(campaignId))
            .stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/armies/{id}")
    public ArmyView getArmy(@PathVariable Long id) {
        return view(requireArmy(id));
    }

    @PostMapping("/armies")
    @Transactional
    public ResponseEntity<ArmyView> createArmy(@Valid @RequestBody ArmyRequest request) {
        Army army = new Army(requireCampaign(request.campaignId()), request.name(),
            request.faction(), request.side(), request.x(), request.y(),
            request.unitCount(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(view(armies.save(army)));
    }

    @PutMapping("/armies/{id}")
    @Transactional
    public ArmyView updateArmy(@PathVariable Long id, @Valid @RequestBody ArmyRequest request) {
        Army army = requireArmy(id);
        army.setCampaign(requireCampaign(request.campaignId()));
        army.setName(request.name());
        army.setFaction(request.faction());
        army.setSide(request.side());
        army.setX(request.x());
        army.setY(request.y());
        army.setUnitCount(request.unitCount());
        army.setDescription(request.description());
        return view(armies.save(army));
    }

    @DeleteMapping("/armies/{id}")
    public ResponseEntity<Void> deleteArmy(@PathVariable Long id) {
        armies.delete(requireArmy(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/terrain-regions")
    @Transactional(readOnly = true)
    public List<TerrainRegionView> getTerrainRegions(@RequestParam String mapKey,
                                                      @RequestParam String mapLayer) {
        validateTerrainMap(mapKey, mapLayer);
        return terrainRegions.findByMapKeyAndMapLayerOrderByIdAsc(mapKey, mapLayer)
            .stream().map(CampaignDataController::view).toList();
    }

    @GetMapping("/terrain-regions/{id}")
    @Transactional(readOnly = true)
    public TerrainRegionView getTerrainRegion(@PathVariable Long id) {
        return view(requireTerrainRegion(id));
    }

    @PostMapping("/terrain-regions")
    @Transactional
    public ResponseEntity<TerrainRegionView> createTerrainRegion(
            @Valid @RequestBody TerrainRegionRequest request) {
        validateTerrainMap(request.mapKey(), request.mapLayer());
        TerrainRegion region = new TerrainRegion(request.mapKey(), request.mapLayer(),
            request.name(), request.terrainType(), request.brushSize(), terrainPoints(request.points()));
        return ResponseEntity.status(HttpStatus.CREATED).body(view(terrainRegions.save(region)));
    }

    @PutMapping("/terrain-regions/{id}")
    @Transactional
    public TerrainRegionView updateTerrainRegion(@PathVariable Long id,
                                                   @Valid @RequestBody TerrainRegionRequest request) {
        validateTerrainMap(request.mapKey(), request.mapLayer());
        TerrainRegion region = requireTerrainRegion(id);
        region.setMapKey(request.mapKey());
        region.setMapLayer(request.mapLayer());
        region.setName(request.name());
        region.setTerrainType(request.terrainType());
        region.setBrushSize(request.brushSize());
        region.setPoints(terrainPoints(request.points()));
        return view(terrainRegions.save(region));
    }

    @DeleteMapping("/terrain-regions/{id}")
    public ResponseEntity<Void> deleteTerrainRegion(@PathVariable Long id) {
        terrainRegions.delete(requireTerrainRegion(id));
        return ResponseEntity.noContent().build();
    }

    private Campaign requireCampaign(Long id) {
        return campaigns.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Campaign not found"));
    }

    private Place requirePlace(Long id) {
        return places.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Place not found"));
    }

    private Route requireRoute(Long id) {
        return routes.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Route not found"));
    }

    private CampaignEvent requireEvent(Long id) {
        return events.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    private Army requireArmy(Long id) {
        return armies.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Army not found"));
    }

    private TerrainRegion requireTerrainRegion(Long id) {
        return terrainRegions.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terrain region not found"));
    }

    private static List<Route.RoutePoint> routePoints(List<RoutePointRequest> points) {
        return points.stream().map(point -> new Route.RoutePoint(point.x(), point.y())).toList();
    }

    private static List<TerrainRegion.RegionPoint> terrainPoints(List<TerrainPointRequest> points) {
        return points.stream().map(point -> new TerrainRegion.RegionPoint(point.x(), point.y())).toList();
    }

    private static void validateTerrainMap(String mapKey, String mapLayer) {
        boolean valid = "ces".equals(mapKey)
            ? "tiles".equals(mapLayer) || "artwork".equals(mapLayer)
            : "middle-earth".equals(mapKey) && "middle-earth".equals(mapLayer);
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Map layer does not belong to the requested terrain map");
        }
    }

    private static CampaignView view(Campaign campaign) {
        return new CampaignView(campaign.getId(), campaign.getSlug(), campaign.getName(),
            campaign.getSetting(), campaign.getDescription(), campaign.getMapMode());
    }

    private static PlaceView view(Place place) {
        return new PlaceView(place.getId(), place.getCampaign().getId(), place.getName(),
            place.getAlternateName(), place.getKind(), place.getMapLayer(), place.getX(), place.getY(),
            place.getTier(), place.getDescription());
    }

    private static RouteView view(Route route) {
        List<RoutePointView> points = route.getPoints().stream()
            .map(point -> new RoutePointView(point.getX(), point.getY())).toList();
        return new RouteView(route.getId(), route.getCampaign().getId(), route.getName(),
            route.getColor(), route.getDescription(), points);
    }

    private static EventView view(CampaignEvent event) {
        return new EventView(event.getId(), event.getCampaign().getId(), event.getEventNumber(),
            event.getTitle(), event.getDescription(), event.getPlaceName(), event.getDateLabel(),
            event.getX(), event.getY());
    }

    private static ArmyView view(Army army) {
        return new ArmyView(army.getId(), army.getCampaign().getId(), army.getName(),
            army.getFaction(), army.getSide(), army.getX(), army.getY(),
            army.getUnitCount(), army.getDescription());
    }

    private static TerrainRegionView view(TerrainRegion region) {
        List<TerrainPointView> points = region.getPoints().stream()
            .map(point -> new TerrainPointView(point.getX(), point.getY())).toList();
        return new TerrainRegionView(region.getId(), region.getMapKey(), region.getMapLayer(),
            region.getName(), region.getTerrainType(), region.getBrushSize(), points);
    }

    public record CampaignRequest(
        @NotBlank String slug,
        @NotBlank String name,
        @NotBlank String setting,
        String description,
        String mapMode
    ) {}

    public record PlaceRequest(
        @NotNull Long campaignId,
        @NotBlank String name,
        String alternateName,
        @NotBlank String kind,
        String mapLayer,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double y,
        @NotNull @Min(0) Integer tier,
        String description
    ) {}

    public record RouteRequest(
        @NotNull Long campaignId,
        @NotBlank String name,
        String color,
        String description,
        @NotEmpty @Size(min = 2) List<@Valid RoutePointRequest> points
    ) {}

    public record RoutePointRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double y
    ) {}

    public record EventRequest(
        @NotNull Long campaignId,
        @Min(1) Integer eventNumber,
        @NotBlank String title,
        String description,
        String placeName,
        String dateLabel,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double y
    ) {}

    public record ArmyRequest(
        @NotNull Long campaignId,
        @NotBlank String name,
        String faction,
        @NotBlank String side,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double y,
        @NotNull @Min(0) Integer unitCount,
        String description
    ) {}

    public record TerrainRegionRequest(
        @NotBlank @Pattern(regexp = "^(ces|middle-earth)$") String mapKey,
        @NotBlank @Pattern(regexp = "^(tiles|artwork|middle-earth)$") String mapLayer,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "^(mountain|river|forest|other)$") String terrainType,
        @NotNull @DecimalMin("0.5") @DecimalMax("15.0") Double brushSize,
        @NotEmpty @Size(max = 2000) List<@Valid TerrainPointRequest> points
    ) {}

    public record TerrainPointRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double x,
        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") Double y
    ) {}

    public record CampaignView(Long id, String slug, String name, String setting,
                               String description, String mapMode) {}
    public record PlaceView(Long id, Long campaignId, String name, String alternateName,
                            String kind, String mapLayer, Double x, Double y, Integer tier, String description) {}
    public record RoutePointView(double x, double y) {}
    public record RouteView(Long id, Long campaignId, String name, String color,
                            String description, List<RoutePointView> points) {}
    public record EventView(Long id, Long campaignId, Integer eventNumber, String title,
                            String description, String placeName, String dateLabel,
                            Double x, Double y) {}
    public record ArmyView(Long id, Long campaignId, String name, String faction, String side,
                           Double x, Double y, Integer unitCount, String description) {}
    public record TerrainPointView(double x, double y) {}
    public record TerrainRegionView(Long id, String mapKey, String mapLayer, String name,
                                    String terrainType, double brushSize, List<TerrainPointView> points) {}
}
