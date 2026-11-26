package org.wargames.repository;

import java.util.List;
import org.wargames.model.Place;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlaceRepository extends JpaRepository<Place, Long> {
    List<Place> findByCampaign_Id(Long campaignId);
    List<Place> findByCampaign_SlugOrderByIdAsc(String campaignSlug);
    List<Place> findByCampaign_SlugAndMapLayerOrderByIdAsc(String campaignSlug, String mapLayer);
    boolean existsByCampaign_Id(Long campaignId);
}
