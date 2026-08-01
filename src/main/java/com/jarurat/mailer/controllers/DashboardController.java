package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.Template;
import com.jarurat.mailer.repositories.ContactRepository;
import com.jarurat.mailer.repositories.DeliveryLogRepository;
import com.jarurat.mailer.repositories.TemplateRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DashboardController {

    private final ContactRepository contactRepository;
    private final DeliveryLogRepository deliveryLogRepository;
    private final TemplateRepository templateRepository;

    public DashboardController(ContactRepository contactRepository, DeliveryLogRepository deliveryLogRepository, TemplateRepository templateRepository) {
        this.contactRepository = contactRepository;
        this.deliveryLogRepository = deliveryLogRepository;
        this.templateRepository = templateRepository;
    }

    @GetMapping("/")
    public String showDashboard(Model model) {
        // Fetch Stats
        model.addAttribute("totalClean", contactRepository.countByStatus("CLEAN"));
        model.addAttribute("totalSuppressed", contactRepository.countByStatus("SUPPRESSED"));
        model.addAttribute("totalUnsubscribed", contactRepository.countByStatus("UNSUBSCRIBED"));
        
        // Fetch active template (or create a default one if DB is empty)
        Template activeTemplate = templateRepository.findById(1L).orElseGet(() -> {
            String defaultHtml = "<h2>Hello {{NAME}},</h2><p>Welcome to the Summit.</p><br><a href='{{UNSUBSCRIBE_LINK}}'>Click here to unsubscribe</a>";
            return templateRepository.save(new Template(1L, "Horizon Summit Update", defaultHtml));
        });
        model.addAttribute("template", activeTemplate);

        // Fetch recent logs
        model.addAttribute("recentLogs", deliveryLogRepository.findTop10ByOrderByTimestampDesc());

        return "dashboard"; 
    }

    // NEW: Save Template Endpoint
    @PostMapping("/template/save")
    public ResponseEntity<String> saveTemplate(
            @RequestParam("subject") String subject, 
            @RequestParam("htmlBody") String htmlBody) {
        
        Template template = templateRepository.findById(1L).orElse(new Template(1L, "", ""));
        template.setSubject(subject);
        template.setHtmlBody(htmlBody);
        templateRepository.save(template);
        
        return ResponseEntity.ok("Template saved successfully!");
    }
}