package org.wargames.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.wargames.model.ChatMessage;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    /** The newest messages first; callers reverse them. */
    List<ChatMessage> findTop100ByGameCodeOrderByIdDesc(String gameCode);

    List<ChatMessage> findTop100ByGameCodeAndIdGreaterThanOrderByIdAsc(String gameCode, Long id);

    List<ChatMessage> findTop200ByGameCodeOrderByIdDesc(String gameCode);

    long countByGameCode(String gameCode);

    /** Deletes a game's messages older than {@code keepFromId}. */
    @Modifying
    @Query("delete from ChatMessage m where m.gameCode = :code and m.id < :keepFromId")
    int deleteOlder(@Param("code") String code, @Param("keepFromId") Long keepFromId);
}
