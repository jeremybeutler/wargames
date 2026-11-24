package org.bomwargames;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bomwargames.model.Campaign;
import org.bomwargames.model.CampaignEvent;
import org.bomwargames.model.Place;
import org.bomwargames.model.Route;
import org.bomwargames.repository.CampaignEventRepository;
import org.bomwargames.repository.CampaignRepository;
import org.bomwargames.repository.PlaceRepository;
import org.bomwargames.repository.RouteRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class CampaignDataSeeder implements CommandLineRunner {
    private final CampaignRepository campaigns;
    private final PlaceRepository places;
    private final RouteRepository routes;
    private final CampaignEventRepository events;
    private final ObjectMapper objectMapper;

    public CampaignDataSeeder(CampaignRepository campaigns, PlaceRepository places,
                              RouteRepository routes, CampaignEventRepository events,
                              ObjectMapper objectMapper) {
        this.campaigns = campaigns;
        this.places = places;
        this.routes = routes;
        this.events = events;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) throws IOException {
        MapSeedData mapData = readMapSeedData();
        Campaign bookOfMormon = findOrCreateCampaign(
            "book-of-mormon", "Book of Mormon", "Book of Mormon",
            "Campaign map for the Book of Mormon setting.", "tiles");
        Campaign adventure = findOrCreateCampaign(
            "book-of-mormon-adventure", "Adventure", "Book of Mormon",
            "The same campaign map with alternate place names.", "tiles");
        Campaign middleEarth = findOrCreateCampaign(
            "middle-earth", "War of the Ring", "The Lord of the Rings",
            "Campaign map and reference events for Middle-earth.", "middle-earth");

        List<PlaceSeed> bookOfMormonArtwork = List.of(
            new PlaceSeed("Joshua", 48.3, 6.2, 1, "town"),
            new PlaceSeed("Bountiful", 59.5, 9.3, 1, "town"),
            new PlaceSeed("Mulek", 64.2, 22.9, 1, "town"),
            new PlaceSeed("Ammonihah", 44.8, 25.6, 1, "town"),
            new PlaceSeed("Noah", 44.3, 33.5, 1, "town"),
            new PlaceSeed("Sidom", 49.0, 28.5, 1, "town"),
            new PlaceSeed("Gid", 61.8, 28.8, 1, "town"),
            new PlaceSeed("Aaron", 55.2, 35.6, 1, "town"),
            new PlaceSeed("Omner", 68.9, 35.7, 1, "town"),
            new PlaceSeed("Zarahemla", 45.2, 46.5, 1, "town"),
            new PlaceSeed("Jershon", 60.5, 42.0, 1, "town"),
            new PlaceSeed("Morianton", 69.2, 46.8, 1, "town"),
            new PlaceSeed("Gideon", 48.7, 52.4, 1, "town"),
            new PlaceSeed("Lehi", 70.5, 52.2, 1, "town"),
            new PlaceSeed("Nephihah", 61.5, 55.8, 1, "town"),
            new PlaceSeed("Cumeni", 34.9, 68.2, 1, "town"),
            new PlaceSeed("Zeezrom", 38.9, 70.6, 1, "town"),
            new PlaceSeed("Manti", 42.6, 73.6, 1, "town"),
            new PlaceSeed("Sirion", 63.6, 80.6, 1, "town")
        );

        seedLayer(bookOfMormon, "artwork", bookOfMormonArtwork, mapData.alternateNames(), false);
        seedLayer(bookOfMormon, "tiles", mapData.tilePlaces(), mapData.alternateNames(), false);
        seedLayer(adventure, "artwork", bookOfMormonArtwork, mapData.alternateNames(), true);
        seedLayer(adventure, "tiles", mapData.tilePlaces(), mapData.alternateNames(), true);
        seedLayer(middleEarth, "middle-earth", mapData.middleEarthPlaces(), mapData.alternateNames(), false);
        seedMiddleEarthRoutes(middleEarth);
        seedMiddleEarthEvents(middleEarth);
    }

    private MapSeedData readMapSeedData() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/map-seed-data.json")) {
            if (input == null) throw new IllegalStateException("Missing map-seed-data.json");
            return objectMapper.readValue(input, MapSeedData.class);
        }
    }

    private Campaign findOrCreateCampaign(String slug, String name, String setting,
                                          String description, String mapMode) {
        return campaigns.findBySlug(slug).orElseGet(() ->
            campaigns.save(new Campaign(slug, name, setting, description, mapMode)));
    }

    private void seedLayer(Campaign campaign, String mapLayer, List<PlaceSeed> seedPlaces,
                           Map<String, String> alternateNames, boolean alternateCampaign) {
        List<Place> existing = places.findByCampaign_Id(campaign.getId());
        if (existing.stream().anyMatch(place -> mapLayer.equals(place.getMapLayer()))) return;

        for (PlaceSeed seed : seedPlaces) {
            String name = alternateCampaign
                ? alternateNames.getOrDefault(seed.name(), seed.name())
                : seed.name();
            String alternateName = alternateCampaign
                ? seed.name()
                : alternateNames.getOrDefault(seed.name(), seed.name());

            Place legacyPlace = existing.stream()
                .filter(place -> place.getMapLayer() == null
                    && seed.name().equals(place.getName())
                    && sameCoordinate(place.getX(), seed.x())
                    && sameCoordinate(place.getY(), seed.y()))
                .findFirst()
                .orElse(null);
            if (legacyPlace != null) {
                legacyPlace.setName(name);
                legacyPlace.setAlternateName(alternateName);
                legacyPlace.setKind(seed.kind());
                legacyPlace.setMapLayer(mapLayer);
                legacyPlace.setTier(seed.tier());
                places.save(legacyPlace);
                continue;
            }

            places.save(new Place(campaign, name, alternateName, seed.kind(), mapLayer,
                seed.x(), seed.y(), seed.tier(), null));
        }
    }

    private static boolean sameCoordinate(Double existing, double seed) {
        return existing != null && Math.abs(existing - seed) < 0.0001;
    }

    private void seedMiddleEarthRoutes(Campaign campaign) {
        if (!routes.findByCampaign_Id(campaign.getId()).isEmpty()) return;
        routes.saveAll(List.of(
            route(campaign, "The Fellowship", "#f0d27d",
                new double[][] {{24, 35}, {34, 40}, {39, 36}, {48, 32}, {45, 47}, {58, 44}, {62, 51}}),
            route(campaign, "Frodo and Sam", "#e99b61",
                new double[][] {{62, 51}, {69, 67}, {68, 59}, {74, 61}, {68, 72}, {76, 75}, {83, 67}}),
            route(campaign, "Aragorn, Legolas, and Gimli", "#82c3d5",
                new double[][] {{62, 51}, {63, 58}, {56, 66}, {46, 68}, {44, 60}, {48, 77}, {56, 84}, {59, 78}, {74, 61}}),
            route(campaign, "Gandalf", "#c5c5dd",
                new double[][] {{45, 47}, {63, 58}, {56, 66}, {46, 68}, {59, 78}})
        ));
    }

    private void seedMiddleEarthEvents(Campaign campaign) {
        if (!events.findByCampaign_Id(campaign.getId()).isEmpty()) return;
        events.saveAll(List.of(
            event(campaign, 1, "The Ring leaves the Shire", "Frodo sets out from home with Sam, Merry, and Pippin.", "Hobbiton", 24, 35),
            event(campaign, 2, "The hobbits meet Strider", "At the Prancing Pony, Aragorn joins the journey as their guide.", "Bree", 34, 40),
            event(campaign, 3, "The attack at Weathertop", "The Black Riders find the travelers on the ancient hill.", "Weathertop", 39, 36),
            event(campaign, 4, "The Council of Elrond", "The Ring’s fate is decided and the Fellowship is formed.", "Rivendell", 48, 32),
            event(campaign, 5, "The Fellowship crosses Moria", "Gandalf falls while holding the Balrog at the Bridge of Khazad-dûm.", "Moria", 45, 47),
            event(campaign, 6, "The Fellowship rests in Lothlórien", "Galadriel gives the company counsel and gifts for the road.", "Lothlórien", 58, 44),
            event(campaign, 7, "The Fellowship is broken", "The company scatters; Frodo and Sam continue toward Mordor.", "Amon Hen", 62, 51),
            event(campaign, 8, "The Battle of the Hornburg", "Rohan withstands Saruman’s assault at Helm’s Deep.", "Helm’s Deep", 46, 68),
            event(campaign, 9, "The Ents take Isengard", "Treebeard and the Ents break Saruman’s hold over the tower.", "Isengard", 44, 60),
            event(campaign, 10, "The Battle of the Pelennor Fields", "The armies of Gondor and Rohan break the siege of Minas Tirith.", "Pelennor Fields", 61, 79),
            event(campaign, 11, "The march on the Black Gate", "The West draws Sauron’s attention away from the Ring-bearers.", "Black Gate", 74, 61),
            event(campaign, 12, "The Ring is destroyed", "The Ring reaches the Cracks of Doom and Sauron’s power falls.", "Mount Doom", 83, 67),
            event(campaign, 13, "The Ring-bearers depart", "Frodo and the Keepers of the Rings sail from Middle-earth.", "Grey Havens", 15, 23)
        ));
    }

    private static Route route(Campaign campaign, String name, String color, double[][] coordinates) {
        List<Route.RoutePoint> points = new ArrayList<>();
        for (double[] point : coordinates) points.add(new Route.RoutePoint(point[0], point[1]));
        return new Route(campaign, name, color, null, points);
    }

    private static CampaignEvent event(Campaign campaign, int number, String title,
                                       String description, String placeName, double x, double y) {
        return new CampaignEvent(campaign, number, title, description, placeName, null, x, y);
    }

    private record MapSeedData(Map<String, String> alternateNames, List<PlaceSeed> tilePlaces,
                               List<PlaceSeed> middleEarthPlaces) {}
    private record PlaceSeed(String name, double x, double y, int tier, String kind) {}
}
