package org.bomwargames;

import java.util.List;
import org.bomwargames.model.Place;
import org.bomwargames.repository.PlaceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MapController {
    public record MapLocation(String name, double x, double y) {}

    private final PlaceRepository places;

    public MapController(PlaceRepository places) {
        this.places = places;
    }

    @GetMapping("/locations")
    public List<MapLocation> locations() {
        return places.findByCampaign_SlugAndMapLayerOrderByIdAsc("book-of-mormon", "artwork").stream()
            .filter(place -> place.getX() != null && place.getY() != null)
            .map(MapController::toLocation)
            .toList();
    }

    private static MapLocation toLocation(Place place) {
        return new MapLocation(place.getName(), place.getX(), place.getY());
    }
}
