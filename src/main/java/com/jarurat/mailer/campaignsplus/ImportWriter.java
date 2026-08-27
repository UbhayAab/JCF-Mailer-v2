package com.jarurat.mailer.campaignsplus;

import com.jarurat.mailer.models.ListMember;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The database half of a CSV import, kept separate from the parser so each
 * chunk commits on its own transaction. A 40k row file in one transaction holds
 * locks for minutes and loses everything if row 39,998 conflicts.
 */
@Service
public class ImportWriter {

    /** A row that survived parsing and validation, waiting to be written. */
    public record PendingRow(int row, String email, String firstName, String lastName,
                             String phone, String company) {}

    public record ChunkResult(int created, int updated, int addedToList, int alreadyOnList) {

        ChunkResult plus(ChunkResult other) {
            return new ChunkResult(created + other.created, updated + other.updated,
                    addedToList + other.addedToList, alreadyOnList + other.alreadyOnList);
        }

        static ChunkResult empty() { return new ChunkResult(0, 0, 0, 0); }
    }

    private final SubscriberRepository subscribers;
    private final ListMemberRepository members;

    public ImportWriter(SubscriberRepository subscribers, ListMemberRepository members) {
        this.subscribers = subscribers;
        this.members = members;
    }

    @Transactional
    public ChunkResult write(List<PendingRow> rows, Long listId, String source) {
        return apply(rows, listId, source, true);
    }

    /** One row on its own transaction, used to rescue a chunk that hit a conflict. */
    @Transactional
    public ChunkResult writeOne(PendingRow row, Long listId, String source) {
        return apply(List.of(row), listId, source, true);
    }

    /** Same arithmetic, no writes, so a dry run reports the real numbers. */
    @Transactional(readOnly = true)
    public ChunkResult preview(List<PendingRow> rows, Long listId) {
        return apply(rows, listId, null, false);
    }

    private ChunkResult apply(List<PendingRow> rows, Long listId, String source, boolean write) {
        int created = 0, updated = 0, addedToList = 0, alreadyOnList = 0;

        for (PendingRow row : rows) {
            Optional<Subscriber> existing = subscribers.findByEmail(row.email());

            if (existing.isPresent()) {
                Subscriber subscriber = existing.get();
                if (enrich(subscriber, row, write)) {
                    if (write) {
                        subscriber.setUpdatedAt(LocalDateTime.now());
                        subscribers.save(subscriber);
                    }
                    updated++;
                }
                if (listId != null) {
                    if (members.existsByListIdAndSubscriberId(listId, subscriber.getId())) {
                        alreadyOnList++;
                    } else {
                        if (write) members.save(new ListMember(listId, subscriber.getId()));
                        addedToList++;
                    }
                }
            } else {
                created++;
                // A brand new address cannot already be on the list, so the dry
                // run can count it without an id.
                if (listId != null) addedToList++;
                if (write) {
                    Subscriber subscriber = new Subscriber(row.email(), row.firstName(), row.lastName(), source);
                    subscriber.setPhone(row.phone());
                    subscriber.setCompany(row.company());
                    subscriber = subscribers.save(subscriber);
                    if (listId != null) members.save(new ListMember(listId, subscriber.getId()));
                }
            }
        }
        return new ChunkResult(created, updated, addedToList, alreadyOnList);
    }

    /**
     * Only fills blanks. A CSV is never allowed to overwrite a name or a phone
     * number that someone already corrected by hand, and it never touches
     * status: re-importing an old file must not resurrect an unsubscribe.
     */
    private boolean enrich(Subscriber subscriber, PendingRow row, boolean write) {
        boolean changed = false;
        if (isBlank(subscriber.getFirstName()) && !isBlank(row.firstName())) {
            if (write) subscriber.setFirstName(row.firstName());
            changed = true;
        }
        if (isBlank(subscriber.getLastName()) && !isBlank(row.lastName())) {
            if (write) subscriber.setLastName(row.lastName());
            changed = true;
        }
        if (isBlank(subscriber.getPhone()) && !isBlank(row.phone())) {
            if (write) subscriber.setPhone(row.phone());
            changed = true;
        }
        if (isBlank(subscriber.getCompany()) && !isBlank(row.company())) {
            if (write) subscriber.setCompany(row.company());
            changed = true;
        }
        return changed;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
