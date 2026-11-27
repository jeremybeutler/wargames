package org.wargames.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** One line of a game's text chat. Kept apart from the game state so chatting never races a move. */
@Entity
@Table(name = "chat_messages", indexes = @Index(name = "chat_messages_game", columnList = "gameCode, id"))
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 6, nullable = false)
    private String gameCode;

    /** The seat that sent it: "host", a character or a house id. */
    @Column(length = 20, nullable = false)
    private String seat;

    @Column(length = 60, nullable = false)
    private String author;

    /** The sender's side (a team or house name), for coloring; null for the host. */
    @Column(length = 40)
    private String side;

    @Column(length = 300, nullable = false)
    private String text;

    @Column(nullable = false)
    private Instant sentAt;

    protected ChatMessage() {}

    public ChatMessage(String gameCode, String seat, String author, String side, String text, Instant sentAt) {
        this.gameCode = gameCode;
        this.seat = seat;
        this.author = author;
        this.side = side;
        this.text = text;
        this.sentAt = sentAt;
    }

    public Long getId() { return id; }
    public String getGameCode() { return gameCode; }
    public String getSeat() { return seat; }
    public String getAuthor() { return author; }
    public String getSide() { return side; }
    public String getText() { return text; }
    public Instant getSentAt() { return sentAt; }
}
