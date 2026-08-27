package com.jarurat.mailer.repositories;

import com.jarurat.mailer.models.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    @Query("""
            select c.url as url, count(c) as clicks, count(distinct c.email) as uniqueClicks
            from ClickEvent c
            where c.campaignId = :campaignId
            group by c.url
            order by count(distinct c.email) desc
            """)
    List<LinkStat> linkBreakdown(@Param("campaignId") Long campaignId);

    interface LinkStat {
        String getUrl();
        long getClicks();
        long getUniqueClicks();
    }

    @Transactional
    @Modifying
    void deleteByCampaignId(Long campaignId);
}
