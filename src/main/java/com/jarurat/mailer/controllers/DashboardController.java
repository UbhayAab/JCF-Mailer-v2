package com.jarurat.mailer.controllers;

import com.jarurat.mailer.models.*;
import com.jarurat.mailer.repositories.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class DashboardController {

    private final ContactRepository contactRepository;
    private final CampaignRepository campaignRepository;
    private final GlobalSuppressionRepository globalSuppressionRepository;
    private final DeliveryLogRepository deliveryLogRepository;

    public DashboardController(ContactRepository contactRepository, CampaignRepository campaignRepository, GlobalSuppressionRepository globalSuppressionRepository, DeliveryLogRepository deliveryLogRepository) {
        this.contactRepository = contactRepository; this.campaignRepository = campaignRepository; this.globalSuppressionRepository = globalSuppressionRepository; this.deliveryLogRepository = deliveryLogRepository;
    }

    // --- NEW: AUTHENTICATION & ROUTING --- //

    @GetMapping("/")
    public String landing() {
        return "landing"; 
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @PostMapping("/login")
    public String doLogin(@RequestParam String email, @RequestParam String password, HttpSession session, Model model) {
        if ("mailer@jarurat.care".equals(email) && "Mailer123@".equals(password)) {
            session.setAttribute("admin", true);
            return "redirect:/dashboard";
        }
        model.addAttribute("error", "Invalid credentials. Please try again.");
        return "login";
    }

    @GetMapping("/logout")
    public String logout(HttpSession session) {
        session.invalidate();
        return "redirect:/";
    }

    @GetMapping("/dashboard")
    public String showDashboard(HttpSession session, Model model) {
        // Protect the dashboard route
        if (session.getAttribute("admin") == null) {
            return "redirect:/login";
        }
        
        model.addAttribute("totalUnsubscribed", globalSuppressionRepository.countByReason("UNSUBSCRIBED"));
        model.addAttribute("totalBounces", globalSuppressionRepository.countByReason("BOUNCE"));
        model.addAttribute("campaigns", campaignRepository.findAll());
        model.addAttribute("recentLogs", deliveryLogRepository.findTop10ByOrderByTimestampDesc());
        return "dashboard"; 
    }

    // --- EXISTING APIs (Unchanged) --- //

    @GetMapping("/api/campaign/{name}")
    @ResponseBody
    public Map<String, Object> getCampaignDetails(@PathVariable String name) {
        Campaign camp = campaignRepository.findById(name).orElse(new Campaign(name, "", ""));
        long cleanCount = contactRepository.countByCampaignNameAndStatus(name, "CLEAN");
        long sentCount = contactRepository.countByCampaignNameAndStatus(name, "SENT");

        Map<String, Object> response = new HashMap<>();
        response.put("subject", camp.getSubject());
        response.put("htmlBody", camp.getHtmlBody());
        response.put("cleanCount", cleanCount);
        response.put("sentCount", sentCount);
        return response;
    }

    @PostMapping("/template/save")
    public ResponseEntity<String> saveTemplate(@RequestParam("campaignName") String campaignName, @RequestParam("subject") String subject, @RequestParam("htmlBody") String htmlBody) {
        campaignRepository.save(new Campaign(campaignName, subject, htmlBody));
        return ResponseEntity.ok("Campaign saved!");
    }

    @Transactional
    @PostMapping("/api/campaign/delete")
    public ResponseEntity<String> deleteCampaign(@RequestParam("campaignName") String campaignName) {
        contactRepository.deleteByCampaignName(campaignName);
        campaignRepository.deleteById(campaignName);
        return ResponseEntity.ok("Campaign deleted successfully.");
    }

    @GetMapping("/api/export")
    public void exportCsv(@RequestParam("campaignName") String campaignName, @RequestParam("type") String type, HttpServletResponse response) throws Exception {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + campaignName + "_" + type + ".csv\"");
        List<Contact> contacts = type.equals("CLICKED") 
                ? contactRepository.findByCampaignNameAndClickedUrlIsNotNullAndStatusNot(campaignName, "UNSUBSCRIBED")
                : contactRepository.findByCampaignNameAndClickedUrlIsNullAndStatusNot(campaignName, "UNSUBSCRIBED");

        PrintWriter writer = response.getWriter();
        writer.println("Name,Email,Clicked_URL");
        for (Contact c : contacts) {
            writer.println(c.getName() + "," + c.getEmail() + "," + (c.getClickedUrl() != null ? c.getClickedUrl() : "None"));
        }
    }
}