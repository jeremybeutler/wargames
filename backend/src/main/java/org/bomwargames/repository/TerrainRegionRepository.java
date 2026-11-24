package org.bomwargames.repository;

import java.util.List;
import org.bomwargames.model.TerrainRegion;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TerrainRegionRepository extends JpaRepository<TerrainRegion, Long> {
    List<TerrainRegion> findByMapKeyAndMapLayerOrderByIdAsc(String mapKey, String mapLayer);
}
