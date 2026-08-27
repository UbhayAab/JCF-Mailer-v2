package com.jarurat.mailer.services;

import com.jarurat.mailer.models.ListMember;
import com.jarurat.mailer.models.Subscriber;
import com.jarurat.mailer.repositories.GlobalSuppressionRepository;
import com.jarurat.mailer.repositories.ListMemberRepository;
import com.jarurat.mailer.repositories.SubscriberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class SubscriberService {

    private final SubscriberRepository subscriberRepository;
    private final ListMemberRepository listMemberRepository;
    private final GlobalSuppressionRepository suppressionRepository;

    public SubscriberService(SubscriberRepository subscriberRepository,
                             ListMemberRepository listMemberRepository,
                             GlobalSuppressionRepository suppressionRepository) {
        this.subscriberRepository = subscriberRepository;
        this.listMemberRepository = listMemberRepository;
        this.suppressionRepository = suppressionRepository;
    }

    public record ImportResult(int created, int updated, int addedToList, int alreadyOnList,
                               int suppressed, int invalid, int duplicateInFile) {}

    /**
     * Upserts people globally and then attaches them to a list. Running the same
     * file twice enriches the existing rows instead of creating a second copy of
     * everyone, which is what the old per-campaign model did.
     */
    @Transactional
    public ImportResult importCsv(MultipartFile file, Long listId, String source) throws Exception {
        int created = 0, updated = 0, addedToList = 0, alreadyOnList = 0;
        int suppressed = 0, invalid = 0, duplicateInFile = 0;
        Set<String> seenInFile = new HashSet<>();

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            HeaderMap header = null;
            boolean first = true;

            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cells = parseCsvLine(line);

                if (first) {
                    first = false;
                    header = HeaderMap.detect(cells);
                    if (header != null) continue;
                    header = HeaderMap.positional();
                }

                String email = header.get(cells, header.email);
                if (email == null) { invalid++; continue; }
                email = email.trim().toLowerCase();
                if (!SesSender.EMAIL_OK.matcher(email).matches()) { invalid++; continue; }
                if (!seenInFile.add(email)) { duplicateInFile++; continue; }
                if (suppressionRepository.existsById(email)) { suppressed++; continue; }

                String firstName = header.get(cells, header.firstName);
                String lastName = header.get(cells, header.lastName);
                if (firstName != null && lastName == null && firstName.contains(" ")) {
                    int space = firstName.trim().indexOf(' ');
                    lastName = firstName.trim().substring(space + 1).trim();
                    firstName = firstName.trim().substring(0, space).trim();
                }

                Optional<Subscriber> existing = subscriberRepository.findByEmail(email);
                Subscriber subscriber;
                if (existing.isPresent()) {
                    subscriber = existing.get();
                    boolean changed = false;
                    if (isBlank(subscriber.getFirstName()) && !isBlank(firstName)) { subscriber.setFirstName(firstName); changed = true; }
                    if (isBlank(subscriber.getLastName()) && !isBlank(lastName)) { subscriber.setLastName(lastName); changed = true; }
                    String phone = header.get(cells, header.phone);
                    String company = header.get(cells, header.company);
                    if (isBlank(subscriber.getPhone()) && !isBlank(phone)) { subscriber.setPhone(phone); changed = true; }
                    if (isBlank(subscriber.getCompany()) && !isBlank(company)) { subscriber.setCompany(company); changed = true; }
                    if (changed) {
                        subscriber.setUpdatedAt(LocalDateTime.now());
                        subscriberRepository.save(subscriber);
                        updated++;
                    }
                } else {
                    subscriber = new Subscriber(email, firstName, lastName, source);
                    subscriber.setPhone(header.get(cells, header.phone));
                    subscriber.setCompany(header.get(cells, header.company));
                    subscriber = subscriberRepository.save(subscriber);
                    created++;
                }

                if (listId != null) {
                    if (listMemberRepository.existsByListIdAndSubscriberId(listId, subscriber.getId())) {
                        alreadyOnList++;
                    } else {
                        listMemberRepository.save(new ListMember(listId, subscriber.getId()));
                        addedToList++;
                    }
                }
            }
        }
        return new ImportResult(created, updated, addedToList, alreadyOnList, suppressed, invalid, duplicateInFile);
    }

    @Transactional
    public Subscriber upsert(String email, String firstName, String lastName, String source) {
        String clean = email.trim().toLowerCase();
        return subscriberRepository.findByEmail(clean).orElseGet(
                () -> subscriberRepository.save(new Subscriber(clean, firstName, lastName, source)));
    }

    @Transactional
    public boolean addToList(Long listId, Long subscriberId) {
        if (listMemberRepository.existsByListIdAndSubscriberId(listId, subscriberId)) return false;
        listMemberRepository.save(new ListMember(listId, subscriberId));
        return true;
    }

    @Transactional
    public void removeFromList(Long listId, Long subscriberId) {
        listMemberRepository.deleteByListIdAndSubscriberId(listId, subscriberId);
    }

    @Transactional
    public void delete(Long subscriberId) {
        listMemberRepository.deleteBySubscriberId(subscriberId);
        subscriberRepository.deleteById(subscriberId);
    }

    // ------------------------------------------------------------------
    // CSV plumbing
    // ------------------------------------------------------------------

    /** Column positions, resolved from whatever headers the file happens to use. */
    private static class HeaderMap {
        int email = -1, firstName = -1, lastName = -1, phone = -1, company = -1;

        static HeaderMap positional() {
            HeaderMap h = new HeaderMap();
            h.firstName = 0;
            h.email = 1;
            return h;
        }

        static HeaderMap detect(List<String> cells) {
            HeaderMap h = new HeaderMap();
            for (int i = 0; i < cells.size(); i++) {
                String c = cells.get(i).trim().toLowerCase().replace("﻿", "");
                if (h.email < 0 && (c.equals("email") || c.contains("e-mail")
                        || c.contains("email") || c.equals("mail"))) h.email = i;
                else if (h.firstName < 0 && (c.equals("first name") || c.equals("firstname")
                        || c.equals("first_name") || c.equals("name") || c.equals("full name"))) h.firstName = i;
                else if (h.lastName < 0 && (c.equals("last name") || c.equals("lastname")
                        || c.equals("last_name") || c.equals("surname"))) h.lastName = i;
                else if (h.phone < 0 && (c.contains("phone") || c.contains("mobile")
                        || c.contains("contact number"))) h.phone = i;
                else if (h.company < 0 && (c.contains("company") || c.contains("organisation")
                        || c.contains("organization") || c.contains("hospital")
                        || c.contains("institute"))) h.company = i;
            }
            return h.email < 0 ? null : h;
        }

        String get(List<String> cells, int index) {
            if (index < 0 || index >= cells.size()) return null;
            String v = cells.get(index).trim();
            return v.isEmpty() ? null : v;
        }
    }

    /** Minimal RFC-4180 splitter, so a quoted "Sharma, Anil" stays one cell. */
    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') inQuotes = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
