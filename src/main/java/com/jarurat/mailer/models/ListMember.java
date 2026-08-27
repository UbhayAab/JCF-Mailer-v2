package com.jarurat.mailer.models;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** Join row. One subscriber can sit on many lists without being duplicated. */
@Entity
@Table(name = "list_member",
        uniqueConstraints = @UniqueConstraint(name = "uk_list_subscriber", columnNames = {"listId", "subscriberId"}),
        indexes = {
                @Index(name = "idx_member_list", columnList = "listId"),
                @Index(name = "idx_member_subscriber", columnList = "subscriberId")
        })
public class ListMember {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listId;

    @Column(nullable = false)
    private Long subscriberId;

    private LocalDateTime addedAt = LocalDateTime.now();

    public ListMember() {}

    public ListMember(Long listId, Long subscriberId) {
        this.listId = listId;
        this.subscriberId = subscriberId;
    }

    public Long getId() { return id; }
    public Long getListId() { return listId; }
    public Long getSubscriberId() { return subscriberId; }
    public LocalDateTime getAddedAt() { return addedAt; }
}
