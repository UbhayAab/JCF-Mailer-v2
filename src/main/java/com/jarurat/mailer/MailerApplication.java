package com.jarurat.mailer;

import com.jarurat.mailer.models.Contact;
import com.jarurat.mailer.repositories.ContactRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class MailerApplication {

    public static void main(String[] args) {
        SpringApplication.run(MailerApplication.class, args);
    }

    @Bean
    public CommandLineRunner runSetup(ContactRepository contactRepository) {
        return args -> {
            System.out.println("===========================================");
            // System.out.println("💾 INJECTING TEST CONTACT...");
            // contactRepository.save(new Contact("jaruratcare@gmail.com", "Dr. Kishan Test", "CLEAN"));
            
            System.out.println("✅ Database ready. Waiting for web API trigger...");
            System.out.println("===========================================");
        };
    }
}