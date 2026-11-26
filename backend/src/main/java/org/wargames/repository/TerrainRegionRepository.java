package org.wargames.repository;

import java.util.List;
import org.wargames.model.TerrainRegion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerrainRegionRepository extends JpaRepository<TerrainRegion, Long> {
    List<TerrainRegion> findByMapKeyAndMapLayerOrderByIdAsc(String mapKey, String mapLayer);
}
